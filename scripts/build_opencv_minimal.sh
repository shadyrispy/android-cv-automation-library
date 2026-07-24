#!/usr/bin/env bash
# 编译精简版 OpenCV 4.12.0 for Android (多 ABI)
#
# 参考 nihui/opencv-mobile 的精简思路,但针对本项目 Java/Kotlin 使用场景做了调整:
#   - 保留 Java 绑定 (BUILD_opencv_java=ON, BUILD_FAT_JAVA_LIB=ON)
#   - 保留 imgcodecs + 内置 jpeg/png/zlib (项目用 Imgcodecs.imread/imwrite)
#   - 关闭所有非必要模块 (calib3d/dnn/features2d/flann/gapi/highgui/ml/objdetect/photo/video/videoio)
#   - 关闭 tiff/webp/openjpeg
#
# 优化:
#   - 启用 CAROTENE (ARM NEON SIMD, 仅 arm64-v8a / armeabi-v7a 生效)
#     对 matchTemplate / cvtColor / threshold 等 SIMD 友好路径有 20-40% 加速
#
# 产出: app/src/main/jniLibs/<abi>/libopencv_java4.so
#   - arm64-v8a    ~8.4MB (CAROTENE ON)
#   - armeabi-v7a  ~6.5MB (CAROTENE ON, NEON)
#   - x86_64       ~9.5MB (CAROTENE OFF, 仅供模拟器使用)
#
# 用法:
#   ./scripts/build_opencv_minimal.sh                 # 默认构建三个 ABI
#   ./scripts/build_opencv_minimal.sh arm64-v8a       # 仅构建指定 ABI
#   ./scripts/build_opencv_minimal.sh "arm64-v8a x86_64"
#
# 可用环境变量覆盖默认值:
#   OPENCV_VERSION=4.12.0
#   ANDROID_NDK=/opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973
#   ANDROID_CMAKE=/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/cmake
#   NINJA_BIN=/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/ninja
#   ANDROID_PLATFORM=android-24
#   BUILD_ROOT=/tmp/ocv-min                           # 各 ABI 构建目录将位于 ${BUILD_ROOT}/build-<abi>
#   JAVA_HOME=/opt/homebrew/opt/openjdk@17            # 构建 Java 绑定需要 JDK
#   ANT_BIN=/tmp/apache-ant-1.10.15/bin               # 生成 javadoc 可选
#
# 依赖:
#   - Android NDK r27+ (含 clang 18)
#   - Android SDK CMake 3.22.1
#   - JDK 17 (构建 Java 绑定需要 javac)
#
# 详细对比说明见 .learnings/LEARNINGS.md LRN-20260724-014

set -euo pipefail

# ============ 配置 (可用环境变量覆盖) ============
OPENCV_VERSION="${OPENCV_VERSION:-4.12.0}"
ANDROID_NDK="${ANDROID_NDK:-/opt/homebrew/share/android-commandlinetools/ndk/27.0.12077973}"
ANDROID_CMAKE="${ANDROID_CMAKE:-/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/cmake}"
NINJA_BIN="${NINJA_BIN:-/opt/homebrew/share/android-commandlinetools/cmake/3.22.1/bin/ninja}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-24}"
BUILD_ROOT="${BUILD_ROOT:-/tmp/ocv-min}"
SRC_DIR="${SRC_DIR:-${BUILD_ROOT}/opencv-${OPENCV_VERSION}}"
SRC_ZIP="${SRC_ZIP:-${BUILD_ROOT}/opencv-${OPENCV_VERSION}.zip}"

# 默认构建所有支持的 ABI;可通过命令行参数覆盖
SUPPORTED_ABIS=(arm64-v8a armeabi-v7a x86_64)
if [ "$#" -gt 0 ] && [ -n "$1" ]; then
    SUPPORTED_ABIS=($1)
fi

# 项目根目录 (脚本所在目录的上一级)
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_LIBS_BASE="${PROJECT_ROOT}/app/src/main/jniLibs"

# Java / Ant (构建 Java 绑定需要)
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="${ANT_BIN:-/tmp/apache-ant-1.10.15/bin}:${PATH}"

# ============ 前置检查 ============
echo "==> 检查依赖..."
[ -d "$ANDROID_NDK" ] || { echo "[ERROR] NDK 不存在: $ANDROID_NDK"; exit 1; }
[ -f "${ANDROID_CMAKE}" ] || { echo "[ERROR] CMake 不存在: ${ANDROID_CMAKE}"; exit 1; }
[ -f "${NINJA_BIN}" ] || { echo "[ERROR] Ninja 不存在: ${NINJA_BIN}"; exit 1; }
[ -d "$JAVA_HOME" ] || { echo "[ERROR] JAVA_HOME 不存在: $JAVA_HOME"; exit 1; }

mkdir -p "$BUILD_ROOT"

# ============ 1. 下载并解压 OpenCV 源码 ============
if [ ! -d "$SRC_DIR" ]; then
    if [ ! -f "$SRC_ZIP" ]; then
        echo "==> 下载 OpenCV ${OPENCV_VERSION} 源码..."
        curl -fL -o "$SRC_ZIP" \
            "https://github.com/opencv/opencv/archive/refs/tags/${OPENCV_VERSION}.zip"
    fi
    echo "==> 解压到 ${SRC_DIR}..."
    unzip -q "$SRC_ZIP" -d "$(dirname "$SRC_DIR")"
fi

# ant 路径 (构建 Java 绑定必须):OpenCV 的 find_host_program 在 Android toolchain 下
# 经常找不到 PATH 里的 ant,这里显式指定 ANT_EXECUTABLE 避免检测失败导致
# opencv_java 模块被 force-disabled。
ANT_BIN_DEFAULT="/tmp/apache-ant-1.10.15/bin"
ANT_BIN_RESOLVED="${ANT_BIN:-${ANT_BIN_DEFAULT}}"
ANT_EXECUTABLE_PATH="${ANT_EXECUTABLE:-${ANT_BIN_RESOLVED}/ant}"

# ============ ABI 特定配置 ============
# 为每个 ABI 返回额外的 CMake 参数(以空格分隔,调用方需用 word splitting)
get_abi_extra_flags() {
    local abi="$1"
    case "$abi" in
        arm64-v8a)
            # CAROTENE: ARM NEON SIMD 优化, matchTemplate/cvtColor/threshold 等加速 20-40%
            echo "-DWITH_CAROTENE=ON"
            ;;
        armeabi-v7a)
            # CAROTENE 在 v7a 需要 NEON + VFPv3 启用
            # minSdk 24+ 的设备 100% 支持 NEON, 无兼容性风险
            echo "-DWITH_CAROTENE=ON -DENABLE_NEON=ON -DENABLE_VFPV3=ON"
            ;;
        x86_64)
            # x86_64 不支持 CAROTENE, OpenCV 会自动忽略
            echo "-DWITH_CAROTENE=OFF"
            ;;
        *)
            echo "[ERROR] 不支持的 ABI: $abi" >&2
            exit 1
            ;;
    esac
}

# ============ 2. 构建单个 ABI ============
build_one_abi() {
    local abi="$1"
    local build_dir="${BUILD_ROOT}/build-${abi}"
    local jni_libs_dir="${JNI_LIBS_BASE}/${abi}"

    echo ""
    echo "========================================================"
    echo "==> 构建 ABI: ${abi}"
    echo "    Build Dir: ${build_dir}"
    echo "    Output   : ${jni_libs_dir}/libopencv_java4.so"
    echo "========================================================"

    echo "==> CMake configure..."
    rm -rf "$build_dir"
    mkdir -p "$build_dir"

    # ABI 特定参数
    local extra_flags
    extra_flags="$(get_abi_extra_flags "$abi")"

    # shellcheck disable=SC2086
    "${ANDROID_CMAKE}" \
        -G Ninja \
        -S "$SRC_DIR" \
        -B "$build_dir" \
        -DCMAKE_MAKE_PROGRAM="${NINJA_BIN}" \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
        -DANDROID_NDK="${ANDROID_NDK}" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DBUILD_FAT_JAVA_LIB=ON \
        -DBUILD_LIST= \
        -DBUILD_opencv_core=ON \
        -DBUILD_opencv_imgproc=ON \
        -DBUILD_opencv_imgcodecs=ON \
        -DBUILD_opencv_java=ON \
        -DBUILD_opencv_java_bindings_generator=ON \
        -DBUILD_opencv_calib3d=OFF \
        -DBUILD_opencv_dnn=OFF \
        -DBUILD_opencv_features2d=OFF \
        -DBUILD_opencv_flann=OFF \
        -DBUILD_opencv_gapi=OFF \
        -DBUILD_opencv_highgui=OFF \
        -DBUILD_opencv_ml=OFF \
        -DBUILD_opencv_objdetect=OFF \
        -DBUILD_opencv_photo=OFF \
        -DBUILD_opencv_stitching=OFF \
        -DBUILD_opencv_video=OFF \
        -DBUILD_opencv_videoio=OFF \
        -DBUILD_JPEG=ON \
        -DBUILD_PNG=ON \
        -DBUILD_ZLIB=ON \
        -DBUILD_TIFF=OFF \
        -DBUILD_WEBP=OFF \
        -DBUILD_OPENJPEG=OFF \
        -DBUILD_OPENEXR=OFF \
        -DBUILD_JASPER=OFF \
        -DWITH_JPEG=ON \
        -DWITH_PNG=ON \
        -DWITH_TIFF=OFF \
        -DWITH_WEBP=OFF \
        -DWITH_OPENJPEG=OFF \
        -DWITH_OPENEXR=OFF \
        -DWITH_JASPER=OFF \
        -DWITH_PROTOBUF=OFF \
        -DWITH_FLATBUFFERS=OFF \
        -DWITH_IMGCODEC_HDR=OFF \
        -DWITH_IMGCODEC_SUNRASTER=OFF \
        -DWITH_IMGCODEC_PXM=OFF \
        -DWITH_IMGCODEC_PFM=OFF \
        -DWITH_ANDROID_NATIVE_CAMERA=ON \
        -DWITH_CPUFEATURES=ON \
        -DWITH_OPENMP=OFF \
        -DANT_EXECUTABLE="${ANT_EXECUTABLE_PATH}" \
        -DANDROID_BUILD_BASE_DIR="${build_dir}" \
        -DANDROID_TMP_INSTALL_BASE_DIR="${build_dir}/CMakeFiles/install/opencv_android" \
        -DBUILD_TESTS=OFF \
        -DBUILD_PERF_TESTS=OFF \
        -DBUILD_EXAMPLES=OFF \
        -DBUILD_DOCS=OFF \
        -DBUILD_opencv_apps=OFF \
        -DBUILD_ANDROID_PROJECTS=OFF \
        -DBUILD_ANDROID_EXAMPLES=OFF \
        -DBUILD_PACKAGE=OFF \
        $extra_flags

    echo "==> Ninja build opencv_java target..."
    "${NINJA_BIN}" -C "$build_dir" -j"$(sysctl -n hw.ncpu)" opencv_java

    local built_so="${build_dir}/jni/${abi}/libopencv_java4.so"
    [ -f "$built_so" ] || { echo "[ERROR] 构建失败: ${built_so} 不存在"; exit 1; }

    mkdir -p "$jni_libs_dir"
    cp -f "$built_so" "${jni_libs_dir}/libopencv_java4.so"

    echo "==> ${abi} 完成"
    ls -lh "${jni_libs_dir}/libopencv_java4.so"
}

# ============ 3. 循环构建所有 ABI ============
for abi in "${SUPPORTED_ABIS[@]}"; do
    build_one_abi "$abi"
done

# ============ 4. 汇总 ============
echo ""
echo "========================================================"
echo "==> 全部构建完成"
echo "========================================================"
for abi in "${SUPPORTED_ABIS[@]}"; do
    so_path="${JNI_LIBS_BASE}/${abi}/libopencv_java4.so"
    if [ -f "$so_path" ]; then
        size=$(ls -lh "$so_path" | awk '{print $5}')
        echo "  ${abi}: ${size}  (${so_path})"
    fi
done
echo ""
echo "如需同步 Java 源码 (org/opencv/*), 可从 ${SRC_DIR}/modules/java/android_sdk/srcgen 拷贝"
