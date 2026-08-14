#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * LOG_TAG = "AegisLocalLlm";
constexpr uint64_t MODEL_MAGIC = 0x41454749534d4f44ULL;
constexpr uint64_t GENERATION_MAGIC = 0x414547495347454eULL;

struct ModelHandle {
    uint64_t magic = MODEL_MAGIC;
    llama_model * model = nullptr;
};

struct GenerationHandle {
    uint64_t magic = GENERATION_MAGIC;
    ModelHandle * owner = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    std::vector<llama_token> prompt_tokens;
    llama_token pending_token = LLAMA_TOKEN_NULL;
    std::atomic_bool cancelled{false};
    int max_tokens = 0;
    int generated_tokens = 0;
    bool prompt_decoded = false;
    bool finished = false;
    bool pending_flush = false;
    std::string utf8_pending;
    std::chrono::steady_clock::time_point started_at = std::chrono::steady_clock::now();
};

std::once_flag backend_once;

void log_callback(enum ggml_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    // llama.cpp messages never receive model prompts from this boundary.
    __android_log_write(priority, LOG_TAG, text);
}

void ensure_backend() {
    std::call_once(backend_once, [] {
        llama_log_set(log_callback, nullptr);
        llama_backend_init();
    });
}

void throw_java(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

void append_utf8(std::string & output, uint32_t code_point) {
    if (code_point <= 0x7f) {
        output.push_back(static_cast<char>(code_point));
    } else if (code_point <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else if (code_point <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
    }
}

std::string java_string_to_utf8(JNIEnv * env, jstring value) {
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string output;
    output.reserve(static_cast<size_t>(length) * 3);
    for (jsize index = 0; index < length; ++index) {
        uint32_t code_point = chars[index];
        if (code_point >= 0xd800 && code_point <= 0xdbff && index + 1 < length) {
            const uint32_t low = chars[index + 1];
            if (low >= 0xdc00 && low <= 0xdfff) {
                code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
                ++index;
            } else {
                code_point = 0xfffd;
            }
        } else if (code_point >= 0xdc00 && code_point <= 0xdfff) {
            code_point = 0xfffd;
        }
        append_utf8(output, code_point);
    }
    env->ReleaseStringChars(value, chars);
    return output;
}

ModelHandle * require_model(JNIEnv * env, jlong raw) {
    auto * handle = reinterpret_cast<ModelHandle *>(raw);
    if (handle == nullptr || handle->magic != MODEL_MAGIC || handle->model == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Invalid or unloaded llama.cpp model handle");
        return nullptr;
    }
    return handle;
}

GenerationHandle * require_generation(JNIEnv * env, jlong raw) {
    auto * handle = reinterpret_cast<GenerationHandle *>(raw);
    if (handle == nullptr || handle->magic != GENERATION_MAGIC || handle->context == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Invalid or released llama.cpp generation handle");
        return nullptr;
    }
    return handle;
}

bool abort_decode(void * data) {
    auto * handle = static_cast<GenerationHandle *>(data);
    return handle == nullptr || handle->cancelled.load(std::memory_order_relaxed);
}

bool complete_valid_utf8(const std::string & value) {
    size_t index = 0;
    while (index < value.size()) {
        const auto first = static_cast<unsigned char>(value[index]);
        size_t length = 0;
        if (first <= 0x7f) length = 1;
        else if ((first & 0xe0) == 0xc0) length = 2;
        else if ((first & 0xf0) == 0xe0) length = 3;
        else if ((first & 0xf8) == 0xf0) length = 4;
        else return false;
        if (index + length > value.size()) return false;
        for (size_t offset = 1; offset < length; ++offset) {
            if ((static_cast<unsigned char>(value[index + offset]) & 0xc0) != 0x80) return false;
        }
        index += length;
    }
    return true;
}

jbyteArray to_byte_array(JNIEnv * env, const std::string & value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result != nullptr && !value.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
        );
    }
    return result;
}

bool decode_prompt(GenerationHandle * generation) {
    constexpr int batch_limit = 512;
    size_t offset = 0;
    while (offset < generation->prompt_tokens.size()) {
        if (generation->cancelled.load(std::memory_order_relaxed)) return false;
        const auto count = static_cast<int>(std::min(
            generation->prompt_tokens.size() - offset,
            static_cast<size_t>(batch_limit)
        ));
        auto batch = llama_batch_get_one(generation->prompt_tokens.data() + offset, count);
        if (llama_decode(generation->context, batch) != 0) return false;
        offset += static_cast<size_t>(count);
    }
    generation->prompt_decoded = true;
    return true;
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, true);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int>(buffer.size()), 0, true);
    }
    if (count < 0) throw std::runtime_error("llama_token_to_piece failed");
    return {buffer.data(), static_cast<size_t>(count)};
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_loadModel(
    JNIEnv * env,
    jobject,
    jstring path,
    jboolean use_memory_map
) {
    ensure_backend();
    if (path == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Model path is required");
        return 0;
    }
    const std::string model_path = java_string_to_utf8(env, path);
    if (env->ExceptionCheck()) return 0;
    llama_model_params params = llama_model_default_params();
    params.load_mode = use_memory_map == JNI_TRUE && llama_supports_mmap()
        ? LLAMA_LOAD_MODE_MMAP
        : LLAMA_LOAD_MODE_NONE;
    params.check_tensors = true;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), params);
    if (model == nullptr) {
        throw_java(env, "java/io/IOException", "llama.cpp could not load or validate the GGUF model");
        return 0;
    }
    auto handle = std::make_unique<ModelHandle>();
    handle->model = model;
    return reinterpret_cast<jlong>(handle.release());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_modelDescription(
    JNIEnv * env,
    jobject,
    jlong raw_model
) {
    auto * handle = require_model(env, raw_model);
    if (handle == nullptr) return nullptr;
    std::vector<char> buffer(512);
    const int count = llama_model_desc(handle->model, buffer.data(), buffer.size());
    if (count < 0) return env->NewStringUTF("Unknown GGUF model");
    return env->NewStringUTF(buffer.data());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_modelParameterCount(
    JNIEnv * env,
    jobject,
    jlong raw_model
) {
    auto * handle = require_model(env, raw_model);
    if (handle == nullptr) return 0;
    return static_cast<jlong>(llama_model_n_params(handle->model));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_modelTensorBytes(
    JNIEnv * env,
    jobject,
    jlong raw_model
) {
    auto * handle = require_model(env, raw_model);
    if (handle == nullptr) return 0;
    return static_cast<jlong>(llama_model_size(handle->model));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_modelEmbeddingDimension(
    JNIEnv * env,
    jobject,
    jlong raw_model
) {
    auto * handle = require_model(env, raw_model);
    if (handle == nullptr) return 0;
    return static_cast<jint>(llama_model_n_embd_out(handle->model));
}

extern "C" JNIEXPORT void JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_unloadModel(
    JNIEnv * env,
    jobject,
    jlong raw_model
) {
    auto * handle = require_model(env, raw_model);
    if (handle == nullptr) return;
    handle->magic = 0;
    llama_model_free(handle->model);
    handle->model = nullptr;
    delete handle;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_startGeneration(
    JNIEnv * env,
    jobject,
    jlong raw_model,
    jstring prompt,
    jint context_size,
    jint threads,
    jint max_tokens,
    jfloat temperature,
    jint seed
) {
    auto * model = require_model(env, raw_model);
    if (model == nullptr) return 0;
    if (prompt == nullptr || context_size < 256 || threads < 1 || max_tokens < 1) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid generation parameters");
        return 0;
    }
    if (llama_model_has_encoder(model->model)) {
        throw_java(env, "java/lang/UnsupportedOperationException", "Encoder models are not supported by this text generation backend");
        return 0;
    }

    auto generation = std::make_unique<GenerationHandle>();
    generation->owner = model;
    generation->max_tokens = max_tokens;

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_size);
    context_params.n_batch = static_cast<uint32_t>(std::min(context_size, 512));
    context_params.n_ubatch = context_params.n_batch;
    context_params.n_threads = threads;
    context_params.n_threads_batch = threads;
    context_params.abort_callback = abort_decode;
    context_params.abort_callback_data = generation.get();
    context_params.no_perf = false;
    generation->context = llama_init_from_model(model->model, context_params);
    if (generation->context == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not allocate the requested context");
        return 0;
    }

    const std::string prompt_utf8 = java_string_to_utf8(env, prompt);
    if (env->ExceptionCheck()) {
        llama_free(generation->context);
        generation->context = nullptr;
        return 0;
    }
    if (prompt_utf8.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        llama_free(generation->context);
        generation->context = nullptr;
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt is too large");
        return 0;
    }
    const auto prompt_length = static_cast<int32_t>(prompt_utf8.size());
    const llama_vocab * vocab = llama_model_get_vocab(model->model);
    int token_count = -llama_tokenize(vocab, prompt_utf8.c_str(), prompt_length, nullptr, 0, true, true);
    if (token_count <= 0 || token_count >= context_size) {
        llama_free(generation->context);
        generation->context = nullptr;
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt is empty or does not fit in the requested context");
        return 0;
    }
    generation->prompt_tokens.resize(static_cast<size_t>(token_count));
    token_count = llama_tokenize(
        vocab,
        prompt_utf8.c_str(),
        prompt_length,
        generation->prompt_tokens.data(),
        token_count,
        true,
        true
    );
    if (token_count < 0) {
        llama_free(generation->context);
        generation->context = nullptr;
        throw_java(env, "java/lang/IllegalArgumentException", "Prompt tokenization failed");
        return 0;
    }

    auto sampler_params = llama_sampler_chain_default_params();
    sampler_params.no_perf = false;
    generation->sampler = llama_sampler_chain_init(sampler_params);
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(generation->sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(generation->sampler, llama_sampler_init_temp(temperature));
        const uint32_t native_seed = seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);
        llama_sampler_chain_add(generation->sampler, llama_sampler_init_dist(native_seed));
    }
    return reinterpret_cast<jlong>(generation.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_embed(
    JNIEnv * env,
    jobject,
    jlong raw_model,
    jstring text,
    jint context_size,
    jint threads,
    jboolean normalize
) {
    auto * model = require_model(env, raw_model);
    if (model == nullptr) return nullptr;
    if (text == nullptr || context_size < 64 || threads < 1) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid embedding parameters");
        return nullptr;
    }

    const std::string input = java_string_to_utf8(env, text);
    if (env->ExceptionCheck()) return nullptr;
    if (input.empty() || input.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw_java(env, "java/lang/IllegalArgumentException", "Embedding input is empty or too large");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(model->model);
    int token_count = -llama_tokenize(
        vocab, input.c_str(), static_cast<int32_t>(input.size()), nullptr, 0, true, true
    );
    if (token_count <= 0 || token_count > context_size) {
        throw_java(env, "java/lang/IllegalArgumentException", "Embedding input does not fit the context");
        return nullptr;
    }
    std::vector<llama_token> tokens(static_cast<size_t>(token_count));
    token_count = llama_tokenize(
        vocab,
        input.c_str(),
        static_cast<int32_t>(input.size()),
        tokens.data(),
        token_count,
        true,
        true
    );
    if (token_count <= 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Embedding tokenization failed");
        return nullptr;
    }

    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context_size);
    params.n_batch = static_cast<uint32_t>(context_size);
    params.n_ubatch = static_cast<uint32_t>(std::min(context_size, 512));
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.embeddings = true;
    params.pooling_type = LLAMA_POOLING_TYPE_MEAN;
    params.attention_type = LLAMA_ATTENTION_TYPE_NON_CAUSAL;
    llama_context * context = llama_init_from_model(model->model, params);
    if (context == nullptr) {
        throw_java(env, "java/lang/OutOfMemoryError", "llama.cpp could not allocate embedding context");
        return nullptr;
    }

    auto batch = llama_batch_get_one(tokens.data(), token_count);
    const int status = llama_model_has_encoder(model->model)
        ? llama_encode(context, batch)
        : llama_decode(context, batch);
    if (status != 0) {
        llama_free(context);
        throw_java(env, "java/lang/IllegalStateException", "llama.cpp embedding evaluation failed");
        return nullptr;
    }

    const float * embedding = llama_get_embeddings_seq(context, 0);
    if (embedding == nullptr) embedding = llama_get_embeddings_ith(context, -1);
    const int dimension = llama_model_n_embd_out(model->model);
    if (embedding == nullptr || dimension <= 0) {
        llama_free(context);
        throw_java(env, "java/lang/UnsupportedOperationException", "Loaded GGUF model has no embedding output");
        return nullptr;
    }

    std::vector<float> values(embedding, embedding + dimension);
    if (normalize == JNI_TRUE) {
        double norm_squared = 0.0;
        for (float value : values) norm_squared += static_cast<double>(value) * value;
        const double norm = std::sqrt(norm_squared);
        if (norm > 0.0) {
            for (float & value : values) value = static_cast<float>(value / norm);
        }
    }
    llama_free(context);

    auto result = env->NewFloatArray(dimension);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, dimension, values.data());
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_nextToken(
    JNIEnv * env,
    jobject,
    jlong raw_generation
) {
    auto * generation = require_generation(env, raw_generation);
    if (generation == nullptr) return nullptr;
    if (generation->cancelled.load(std::memory_order_relaxed)) return nullptr;
    try {
        if (generation->finished) {
            if (generation->pending_flush) {
                generation->pending_flush = false;
                auto output = generation->utf8_pending;
                generation->utf8_pending.clear();
                return to_byte_array(env, output);
            }
            return nullptr;
        }
        if (!generation->prompt_decoded && !decode_prompt(generation)) {
            if (!generation->cancelled.load(std::memory_order_relaxed)) {
                throw std::runtime_error("llama_decode failed while processing the prompt");
            }
            return nullptr;
        }
        if (generation->pending_token != LLAMA_TOKEN_NULL) {
            auto batch = llama_batch_get_one(&generation->pending_token, 1);
            if (llama_decode(generation->context, batch) != 0) {
                if (generation->cancelled.load(std::memory_order_relaxed)) return nullptr;
                throw std::runtime_error("llama_decode failed during generation");
            }
        }

        const llama_vocab * vocab = llama_model_get_vocab(generation->owner->model);
        const llama_token token = llama_sampler_sample(generation->sampler, generation->context, -1);
        if (llama_vocab_is_eog(vocab, token) || generation->generated_tokens >= generation->max_tokens) {
            generation->finished = true;
            generation->pending_flush = !generation->utf8_pending.empty();
            return generation->pending_flush ? Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_nextToken(
                env, nullptr, raw_generation
            ) : nullptr;
        }
        generation->pending_token = token;
        generation->generated_tokens += 1;
        generation->utf8_pending += token_piece(vocab, token);
        if (!complete_valid_utf8(generation->utf8_pending)) return to_byte_array(env, "");
        auto output = generation->utf8_pending;
        generation->utf8_pending.clear();
        return to_byte_array(env, output);
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_cancelGeneration(
    JNIEnv * env,
    jobject,
    jlong raw_generation
) {
    auto * generation = require_generation(env, raw_generation);
    if (generation != nullptr) generation->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_generationUsage(
    JNIEnv * env,
    jobject,
    jlong raw_generation
) {
    auto * generation = require_generation(env, raw_generation);
    if (generation == nullptr) return nullptr;
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - generation->started_at
    ).count();
    const jlong values[] = {
        static_cast<jlong>(generation->prompt_tokens.size()),
        static_cast<jlong>(generation->generated_tokens),
        static_cast<jlong>(elapsed),
    };
    auto result = env->NewLongArray(3);
    if (result != nullptr) env->SetLongArrayRegion(result, 0, 3, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mtzallqmy_aiagent_local_1llm_internal_LlamaCppJniBridge_freeGeneration(
    JNIEnv * env,
    jobject,
    jlong raw_generation
) {
    auto * generation = require_generation(env, raw_generation);
    if (generation == nullptr) return;
    generation->cancelled.store(true, std::memory_order_relaxed);
    generation->magic = 0;
    llama_sampler_free(generation->sampler);
    generation->sampler = nullptr;
    llama_free(generation->context);
    generation->context = nullptr;
    delete generation;
}
