cmake_minimum_required(VERSION 3.14)
project(aegis_llama LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

# Reproducible upstream boundary. Dependency updates must deliberately change
# this immutable commit and pass the native build gate.
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 1d2869c6e54d5003f3927a79efbca0fefa034a6d
    GIT_SHALLOW FALSE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(aegis_llama SHARED llama_jni.cpp)
target_include_directories(aegis_llama PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
)
target_compile_options(aegis_llama PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(aegis_llama PRIVATE llama android log)
