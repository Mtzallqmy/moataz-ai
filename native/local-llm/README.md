# Aegis local LLM runtime

This Android library is the production boundary for on-device GGUF text inference:

`Kotlin LocalModelBackend -> JNI -> C++ -> llama.cpp`

The native dependency is pinned to llama.cpp commit
`1d2869c6e54d5003f3927a79efbca0fefa034a6d`. The CMake build compiles the real
CPU backend for `arm64-v8a` and `x86_64`; 32-bit processes are deliberately not
advertised as supported.

Before native model load, `LlamaCppLocalModelBackend` requires a fresh, single-use
assessment. It verifies that the canonical file remains inside an explicitly
configured model root, parses bounded GGUF metadata, computes SHA-256, checks an
optional expected checksum, reports disk and RAM, estimates peak memory, validates
the ABI, and requires explicit acknowledgement for non-blocking memory/context
warnings. The checksum is recomputed immediately before load to prevent stale
assessment reuse.

Generation is pull-streamed token by token. Cancellation sets an atomic native flag
that is also registered as llama.cpp's CPU decode abort callback. Model, context,
sampler, and generation handles have explicit load/unload/free lifecycles.

This module provides in-process native inference. It is not a sandbox and does not
claim process, container, or remote isolation.
