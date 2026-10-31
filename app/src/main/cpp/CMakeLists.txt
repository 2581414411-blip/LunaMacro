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

# MNN 自带的轻量图像库（不是 OpenCV）。libllm.so 的 DT_NEEDED 里写死了它，
# 并且真的调用了 MNN::CV::imread / MNN::CV::resize。0.6.22 漏拷这一个，
# 导致 dlopen("libllm.so") 直接失败、视觉模型整整几轮装不上 —— 别再删。
# 由 MNN 3.0.3 源码编译（-DMNN_BUILD_OPENCV=ON -DMNN_IMGCODECS=ON）。
add_library(MNNOpenCV SHARED IMPORTED)
set_target_properties(MNNOpenCV PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libMNNOpenCV.so)

# JNI 库
add_library(mnn_recognizer SHARED mnn_recognizer.cpp)
target_link_libraries(mnn_recognizer llm MNN MNN_Express MNNOpenCV log)
