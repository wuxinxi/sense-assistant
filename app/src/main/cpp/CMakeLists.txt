cmake_minimum_required(VERSION 3.22.1)
project("sense_assistant")

# 引入头文件目录
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)

# 引入预编译的 llama.cpp 核心动态链接库
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so
)

# 引入预编译的 ggml 动态链接库
add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml.so
)

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-cpu.so
)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so
)

add_library(omp SHARED IMPORTED)
set_target_properties(omp PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libomp.so
)

# 编译应用专属的 JNI 桥接库
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

# 链接预编译库与系统日志库
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        ggml
        ggml-cpu
        ggml-base
        omp
        log)
