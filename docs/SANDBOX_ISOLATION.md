# Sandbox isolation contract

Backends must report only isolation the implementation actually provides.

| Backend level | Separate Android UID | Separate native process | Filesystem namespaces/chroot | Network namespace | Container claim |
|---|---:|---:|---:|---:|---:|
| Android app sandbox | no | optional | no | no | no |
| Android isolated process | yes | yes | no | no | no |
| Rust runtime boundary | yes | yes | no | no | no |
| PRoot userspace | Termux UID, not Aegis UID | yes | userspace path translation only | no | no |
| Remote sandbox | provider-defined | provider-defined | provider-defined | provider-defined | only when attested by provider |

The Rust backend uses a non-exported Android `isolatedProcess` service and a
Rust child process. It clears the inherited environment, accepts only explicit
working-directory descriptors, and does not receive the Aegis database or
provider credentials. It is not a container and does not provide kernel
namespaces, cgroups, seccomp, or chroot.

The PRoot backend is disabled until `proot_backend_enabled` is explicitly set.
It uses Termux's permission-protected `RUN_COMMAND` service and requires the
user to grant `com.termux.permission.RUN_COMMAND` and enable Termux's
`allow-external-apps` setting. Guest commands run with PRoot-Distro's
`--minimal` mode, bounded result transport, an environment allowlist, and no
Aegis provider secrets. PRoot is syscall/path translation based on `ptrace`;
it provides no PID, network, or IPC isolation, cgroups, or seccomp.

References:

- https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
- https://github.com/termux/proot-distro#limitations
