#!/usr/bin/env bash
# Rebuild the actual llama/ggml libraries, not just the application's JNI bridge.
set -euo pipefail
TASK_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28.2.13676358}"
TASK_BUILD="$TASK_ROOT/build/native-runtime"
TASK_HOST_TAG="${NDK_HOST_TAG:-darwin-x86_64}"
TASK_CMAKE="${CMAKE_BIN:-cmake}"
TASK_NINJA="${NINJA_BIN:-ninja}"
TASK_LLAMA_REF=f95b0d95394d5e311ba8228689972843178c5e28
TASK_HEADERS_REF=30bc20a8e90468e231d7c639805ae61ad1fefa4f
TASK_LOADER_REF=5192c84f8059e5f703e5452929b613f9487f6e4c

fetch_pinned() {
    local url="$1" dir="$2" ref="$3"
    if [[ ! -d "$dir/.git" ]]; then
        git init "$dir"
        git -C "$dir" remote add origin "$url"
        git -C "$dir" fetch --depth 1 origin "$ref"
        git -C "$dir" checkout --detach FETCH_HEAD
    fi
    [[ "$(git -C "$dir" rev-parse HEAD)" == "$ref" ]] || {
        echo "Unexpected checkout in $dir; refusing to overwrite it" >&2; exit 1;
    }
    [[ -z "$(git -C "$dir" status --porcelain)" ]] || {
        echo "Modified checkout in $dir; refusing to overwrite it" >&2; exit 1;
    }
}
mkdir -p "$TASK_BUILD"
fetch_pinned https://github.com/ggml-org/llama.cpp.git "$TASK_BUILD/source" "$TASK_LLAMA_REF"
fetch_pinned https://github.com/KhronosGroup/OpenCL-Headers.git "$TASK_BUILD/headers" "$TASK_HEADERS_REF"
fetch_pinned https://github.com/KhronosGroup/OpenCL-ICD-Loader.git "$TASK_BUILD/loader" "$TASK_LOADER_REF"
TASK_ANDROID_ARGS=(
    -G Ninja "-DCMAKE_MAKE_PROGRAM=$TASK_NINJA"
    "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
    -DCMAKE_BUILD_TYPE=Release '-DCMAKE_C_FLAGS_RELEASE=-O3 -DNDEBUG' '-DCMAKE_CXX_FLAGS_RELEASE=-O3 -DNDEBUG'
)
# Link-time stub only. Never distribute the ICD loader or a copied vendor driver
# in the APK: the optional public libOpenCL.so must come from the phone's OS.
"$TASK_CMAKE" -S "$TASK_BUILD/loader" -B "$TASK_BUILD/link-loader" "${TASK_ANDROID_ARGS[@]}" \
    "-DOPENCL_ICD_LOADER_HEADERS_DIR=$TASK_BUILD/headers" -DENABLE_OPENCL_LAYERS=OFF -DBUILD_TESTING=OFF
"$TASK_CMAKE" --build "$TASK_BUILD/link-loader" --target OpenCL -j "${BUILD_JOBS:-8}"
"$TASK_CMAKE" -S "$TASK_BUILD/source" -B "$TASK_BUILD/runtime" "${TASK_ANDROID_ARGS[@]}" \
    -DGGML_NATIVE=OFF -DGGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod \
    -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_BACKEND_DL=ON \
    -DGGML_OPENCL=ON -DGGML_OPENCL_EMBED_KERNELS=ON -DGGML_OPENCL_USE_ADRENO_KERNELS=ON \
    -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF \
    "-DOpenCL_INCLUDE_DIR=$TASK_BUILD/headers" "-DOpenCL_LIBRARY=$TASK_BUILD/link-loader/libOpenCL.so"
"$TASK_CMAKE" --build "$TASK_BUILD/runtime" --target llama llama-bench -j "${BUILD_JOBS:-8}"
TASK_STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$TASK_HOST_TAG/bin/llvm-strip"
for lib in llama ggml ggml-base ggml-cpu ggml-opencl; do
    cp "$TASK_BUILD/runtime/bin/lib$lib.so" "$TASK_ROOT/app/src/main/jniLibs/arm64-v8a/lib$lib.so"
    "$TASK_STRIP" --strip-unneeded "$TASK_ROOT/app/src/main/jniLibs/arm64-v8a/lib$lib.so"
done
cp "$TASK_BUILD/source/include/llama.h" "$TASK_ROOT/app/src/main/cpp/include/llama.h"
cp "$TASK_BUILD/source/ggml/include/"*.h "$TASK_ROOT/app/src/main/cpp/include/"
cp "$TASK_BUILD/source/LICENSE" "$TASK_ROOT/app/src/main/assets/llama-runtime-LICENSE.txt"
echo "Packaged optimized CPU + optional OpenCL runtime. Bench: $TASK_BUILD/runtime/bin/llama-bench"
