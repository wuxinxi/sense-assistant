cmake_minimum_required(VERSION 3.22.1)
project("sense_assistant")

# 引入头文件目录
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

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

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libggml-base.so
)

# CPU and OpenCL are MODULE plugins, packaged by jniLibs and loaded by name.
# MODULEs have no SONAME; linking an imported one records a host absolute path.
# OpenCL must also stay optional for phones without the vendor GPU library.

# 编译应用专属的 JNI 桥接库 (llama.cpp 交互)
add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

# 链接预编译库与系统日志库
target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        ggml
        ggml-base
        log)
