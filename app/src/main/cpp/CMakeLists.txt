cmake_minimum_required(VERSION 3.22.1)
project(nexusagent)

set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 头文件路径：llm.hpp 在 cpp 根，MNN 头文件在 cpp/MNN_include
include_directories(
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/MNN_include
)

# 预编译的 MNN 库（从 jniLibs 引入）
add_library(llm SHARED IMPORTED)
set_target_properties(llm PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libllm.so)

add_library(MNN SHARED IMPORTED)
set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libMNN.so)

add_library(MNN_Express SHARED IMPORTED)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libMNN_Express.so)

# JNI 库
add_library(mnn_recognizer SHARED mnn_recognizer.cpp)
target_link_libraries(mnn_recognizer llm MNN MNN_Express log)
