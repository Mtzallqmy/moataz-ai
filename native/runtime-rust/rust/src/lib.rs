use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;
use std::io::{Read, Write};
use std::os::fd::{FromRawFd, OwnedFd};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const MAX_REQUEST_BYTES: usize = 64 * 1024;
const MAX_ARGUMENTS: usize = 64;
const MAX_ARGUMENT_BYTES: usize = 4 * 1024;
const MAX_STDIN_BYTES: usize = 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 1024 * 1024;
const MAX_TIMEOUT_MS: u64 = 5 * 60 * 1000;
const ALLOWED_PROGRAMS: &[&str] = &["/system/bin/sh", "/system/bin/toybox"];
const ALLOWED_ENVIRONMENT: &[&str] = &["LANG", "LC_ALL", "TZ"];

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static EXECUTIONS: OnceLock<Mutex<HashMap<String, Arc<Execution>>>> = OnceLock::new();

fn executions() -> &'static Mutex<HashMap<String, Arc<Execution>>> {
    EXECUTIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct ExecutionRequest {
    program: String,
    #[serde(default)]
    arguments: Vec<String>,
    #[serde(default)]
    environment: HashMap<String, String>,
    stdin: Option<String>,
    timeout_ms: u64,
    stdout_limit_bytes: usize,
    stderr_limit_bytes: usize,
}

impl ExecutionRequest {
    fn validate(&self) -> Result<(), String> {
        if !ALLOWED_PROGRAMS.contains(&self.program.as_str()) {
            return Err("program is outside the native runtime allowlist".to_string());
        }
        if self.arguments.len() > MAX_ARGUMENTS {
            return Err(format!("argument count exceeds {MAX_ARGUMENTS}"));
        }
        if self
            .arguments
            .iter()
            .any(|argument| argument.as_bytes().len() > MAX_ARGUMENT_BYTES)
        {
            return Err(format!("an argument exceeds {MAX_ARGUMENT_BYTES} bytes"));
        }
        if self.stdin.as_ref().map_or(0, |value| value.as_bytes().len()) > MAX_STDIN_BYTES {
            return Err(format!("stdin exceeds {MAX_STDIN_BYTES} bytes"));
        }
        if !(1..=MAX_TIMEOUT_MS).contains(&self.timeout_ms) {
            return Err(format!("timeout_ms must be in 1..={MAX_TIMEOUT_MS}"));
        }
        if !(1..=MAX_OUTPUT_BYTES).contains(&self.stdout_limit_bytes)
            || !(1..=MAX_OUTPUT_BYTES).contains(&self.stderr_limit_bytes)
        {
            return Err(format!("output limits must be in 1..={MAX_OUTPUT_BYTES}"));
        }
        if self.environment.len() > ALLOWED_ENVIRONMENT.len() {
            return Err("too many environment values".to_string());
        }
        for (key, value) in &self.environment {
            let upper = key.to_ascii_uppercase();
            if !ALLOWED_ENVIRONMENT.contains(&upper.as_str()) {
                return Err(format!("environment key is not allowed: {key}"));
            }
            if is_secret_key(&upper) {
                return Err("secret-like environment keys are forbidden".to_string());
            }
            if value.as_bytes().len() > MAX_ARGUMENT_BYTES {
                return Err(format!("environment value for {key} is too large"));
            }
        }
        Ok(())
    }
}

fn is_secret_key(key: &str) -> bool {
    ["KEY", "TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH"]
        .iter()
        .any(|needle| key.contains(needle))
}

#[derive(Clone, Debug, Serialize)]
struct ExecutionResult {
    status: String,
    execution_id: String,
    exit_code: Option<i32>,
    stdout: String,
    stderr: String,
    stdout_truncated: bool,
    stderr_truncated: bool,
    duration_ms: u64,
    error: Option<String>,
}

struct Execution {
    result: Mutex<Option<ExecutionResult>>,
    completed: Condvar,
    cancelled: AtomicBool,
    process_id: u32,
}

fn new_execution_id() -> String {
    let epoch = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    let sequence = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    format!("rust-{epoch:x}-{sequence:x}")
}

fn start_execution(request_json: &str, working_directory_fd: jint) -> serde_json::Value {
    if request_json.as_bytes().len() > MAX_REQUEST_BYTES {
        close_raw_fd(working_directory_fd);
        return json!({"status":"error", "error":"request exceeds native size limit"});
    }
    let request: ExecutionRequest = match serde_json::from_str(request_json) {
        Ok(value) => value,
        Err(error) => {
            close_raw_fd(working_directory_fd);
            return json!({"status":"error", "error":format!("invalid request: {error}")});
        }
    };
    if let Err(error) = request.validate() {
        close_raw_fd(working_directory_fd);
        return json!({"status":"error", "error":error});
    }

    let directory_handle = if working_directory_fd >= 0 {
        Some(unsafe { OwnedFd::from_raw_fd(working_directory_fd) })
    } else {
        None
    };
    let working_directory = match working_directory(&directory_handle) {
        Ok(path) => path,
        Err(error) => return json!({"status":"error", "error":error}),
    };

    let mut command = Command::new(&request.program);
    command
        .args(&request.arguments)
        .current_dir(working_directory)
        .env_clear()
        .env("HOME", "/")
        .env("PATH", "/system/bin")
        .stdin(if request.stdin.is_some() { Stdio::piped() } else { Stdio::null() })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    for (key, value) in &request.environment {
        command.env(key.to_ascii_uppercase(), value);
    }

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => return json!({"status":"error", "error":format!("spawn failed: {error}")}),
    };
    drop(directory_handle);

    let execution_id = new_execution_id();
    let execution = Arc::new(Execution {
        result: Mutex::new(None),
        completed: Condvar::new(),
        cancelled: AtomicBool::new(false),
        process_id: child.id(),
    });
    executions()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .insert(execution_id.clone(), execution.clone());

    if let (Some(stdin_value), Some(mut stdin)) = (request.stdin, child.stdin.take()) {
        thread::spawn(move || {
            let _ = stdin.write_all(stdin_value.as_bytes());
        });
    }
    let stdout = child.stdout.take().expect("stdout was configured as piped");
    let stderr = child.stderr.take().expect("stderr was configured as piped");
    let stdout_limit = request.stdout_limit_bytes;
    let stderr_limit = request.stderr_limit_bytes;
    let timeout = Duration::from_millis(request.timeout_ms);
    let monitor_id = execution_id.clone();
    let process_id = execution.process_id;
    let monitor_execution = execution.clone();
    thread::spawn(move || {
        let started = Instant::now();
        let stdout_reader = thread::spawn(move || read_bounded(stdout, stdout_limit));
        let stderr_reader = thread::spawn(move || read_bounded(stderr, stderr_limit));
        let (status, exit_code, error) = loop {
            if monitor_execution.cancelled.load(Ordering::Acquire) {
                let _ = child.kill();
                let _ = child.wait();
                break ("cancelled", None, None);
            }
            if started.elapsed() >= timeout {
                let _ = child.kill();
                let _ = child.wait();
                break ("timed_out", None, Some("process exceeded timeout".to_string()));
            }
            match child.try_wait() {
                Ok(Some(exit)) => break ("completed", exit.code(), None),
                Ok(None) => thread::sleep(Duration::from_millis(10)),
                Err(wait_error) => {
                    let _ = child.kill();
                    let _ = child.wait();
                    break ("failed", None, Some(format!("process wait failed: {wait_error}")));
                }
            }
        };
        let (stdout, stdout_truncated) = stdout_reader.join().unwrap_or_default();
        let (stderr, stderr_truncated) = stderr_reader.join().unwrap_or_default();
        let result = ExecutionResult {
            status: status.to_string(),
            execution_id: monitor_id,
            exit_code,
            stdout,
            stderr,
            stdout_truncated,
            stderr_truncated,
            duration_ms: started.elapsed().as_millis().try_into().unwrap_or(u64::MAX),
            error,
        };
        let mut slot = monitor_execution
            .result
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        *slot = Some(result);
        monitor_execution.completed.notify_all();
    });

    json!({
        "status":"started",
        "execution_id":execution_id,
        "process_id":process_id,
    })
}

fn working_directory(handle: &Option<OwnedFd>) -> Result<PathBuf, String> {
    match handle {
        None => Ok(PathBuf::from("/")),
        Some(fd) => {
            use std::os::fd::AsRawFd;
            let path = PathBuf::from(format!("/proc/self/fd/{}", fd.as_raw_fd()));
            let metadata = std::fs::metadata(&path)
                .map_err(|error| format!("working directory reference is invalid: {error}"))?;
            if !metadata.is_dir() {
                return Err("working directory reference is not a directory".to_string());
            }
            Ok(path)
        }
    }
}

fn close_raw_fd(fd: jint) {
    if fd >= 0 {
        unsafe { libc::close(fd) };
    }
}

fn read_bounded<R: Read>(mut reader: R, limit: usize) -> (String, bool) {
    let mut kept = Vec::with_capacity(limit.min(16 * 1024));
    let mut total = 0usize;
    let mut buffer = [0u8; 4096];
    loop {
        let count = match reader.read(&mut buffer) {
            Ok(0) | Err(_) => break,
            Ok(count) => count,
        };
        total = total.saturating_add(count);
        if kept.len() < limit {
            let remaining = limit - kept.len();
            kept.extend_from_slice(&buffer[..count.min(remaining)]);
        }
    }
    (String::from_utf8_lossy(&kept).into_owned(), total > limit)
}

fn await_result(execution_id: &str, wait_timeout_ms: jlong) -> ExecutionResult {
    let execution = {
        let map = executions()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        map.get(execution_id).cloned()
    };
    let Some(execution) = execution else {
        return error_result(execution_id, "not_found", "execution does not exist");
    };
    let wait = Duration::from_millis(wait_timeout_ms.max(1) as u64);
    let result = execution
        .result
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let (result, _) = execution
        .completed
        .wait_timeout_while(result, wait, |value| value.is_none())
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    result.clone().unwrap_or_else(|| error_result(execution_id, "waiting", "result is not ready"))
}

fn error_result(execution_id: &str, status: &str, message: &str) -> ExecutionResult {
    ExecutionResult {
        status: status.to_string(),
        execution_id: execution_id.to_string(),
        exit_code: None,
        stdout: String::new(),
        stderr: String::new(),
        stdout_truncated: false,
        stderr_truncated: false,
        duration_ms: 0,
        error: Some(message.to_string()),
    }
}

fn jstring_input(env: &mut JNIEnv, input: JString) -> Result<String, String> {
    env.get_string(&input)
        .map(|value| value.into())
        .map_err(|error| format!("invalid JNI string: {error}"))
}

fn jstring_output(env: &mut JNIEnv, output: String) -> jstring {
    env.new_string(output)
        .map(|value| value.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_mtzallqmy_aiagent_native_1runtime_RustRuntimeNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
    working_directory_fd: jint,
) -> jstring {
    let output = match jstring_input(&mut env, request_json) {
        Ok(request) => start_execution(&request, working_directory_fd),
        Err(error) => {
            close_raw_fd(working_directory_fd);
            json!({"status":"error", "error":error})
        }
    };
    jstring_output(&mut env, output.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_mtzallqmy_aiagent_native_1runtime_RustRuntimeNative_nativeAwaitResult(
    mut env: JNIEnv,
    _class: JClass,
    execution_id: JString,
    wait_timeout_ms: jlong,
) -> jstring {
    let result = match jstring_input(&mut env, execution_id) {
        Ok(id) => await_result(&id, wait_timeout_ms),
        Err(error) => error_result("", "error", &error),
    };
    jstring_output(&mut env, serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string()))
}

#[no_mangle]
pub extern "system" fn Java_com_mtzallqmy_aiagent_native_1runtime_RustRuntimeNative_nativeCancel(
    mut env: JNIEnv,
    _class: JClass,
    execution_id: JString,
) -> jboolean {
    let Ok(id) = jstring_input(&mut env, execution_id) else {
        return JNI_FALSE;
    };
    let execution = executions()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .get(&id)
        .cloned();
    match execution {
        Some(value) => {
            value.cancelled.store(true, Ordering::Release);
            JNI_TRUE
        }
        None => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mtzallqmy_aiagent_native_1runtime_RustRuntimeNative_nativeRelease(
    mut env: JNIEnv,
    _class: JClass,
    execution_id: JString,
) -> jboolean {
    let Ok(id) = jstring_input(&mut env, execution_id) else {
        return JNI_FALSE;
    };
    let mut map = executions()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let releasable = map.get(&id).is_some_and(|execution| {
        execution
            .result
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .is_some()
    });
    if releasable {
        map.remove(&id);
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_mtzallqmy_aiagent_native_1runtime_RustRuntimeNative_nativeCapabilities(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    jstring_output(
        &mut env,
        json!({
            "isolation_level":"android_isolated_process+rust_child_process",
            "android_isolated_process":true,
            "rust_process_boundary":true,
            "container_isolation":false,
            "filesystem_namespaces":false,
            "environment_inheritance":false,
            "explicit_working_directory_fd":true
        })
        .to_string(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn valid_request() -> ExecutionRequest {
        ExecutionRequest {
            program: "/system/bin/sh".to_string(),
            arguments: vec!["-c".to_string(), "echo ok".to_string()],
            environment: HashMap::new(),
            stdin: None,
            timeout_ms: 1_000,
            stdout_limit_bytes: 1024,
            stderr_limit_bytes: 1024,
        }
    }

    #[test]
    fn validates_minimal_request() {
        assert!(valid_request().validate().is_ok());
    }

    #[test]
    fn rejects_program_outside_allowlist() {
        let mut request = valid_request();
        request.program = "/data/local/tmp/untrusted".to_string();
        assert!(request.validate().is_err());
    }

    #[test]
    fn rejects_provider_secret_environment() {
        let mut request = valid_request();
        request
            .environment
            .insert("OPENAI_API_KEY".to_string(), "secret".to_string());
        assert!(request.validate().is_err());
    }

    #[test]
    fn output_reader_drains_and_truncates() {
        let (value, truncated) = read_bounded(Cursor::new(b"123456789"), 4);
        assert_eq!(value, "1234");
        assert!(truncated);
    }
}
