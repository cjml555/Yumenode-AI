#!/bin/bash
# Recompila libjllama.so (java-llama.cpp v5.1.0) para Android arm64 con el
# backend Vulkan y lo deja listo para empaquetar como AAR.
#
# Pasos (ver README en la cabecera del script):
#   1) clonar llama.cpp b10682 y aplicar los parches de java-llama.cpp
#   2) parchear jllama.cpp (casts de JNI) y ggml-vulkan.cpp (BDA)
#   3) compilar con el NDK, usando el glslc y las cabeceras SPIRV del NDK
#   4) strip y copiar al AAR
#
# El bootstrap de Vulkan (glslc + SPIRV-Headers) es el mismo que ya usa el
# build de sd.cpp en este proyecto: el NDK trae glslc y las cabeceras
# spirv, solo hay que generar el paquete CMake minimo que ggml-vulkan
# busca con find_package(SPIRV-Headers CONFIG).
set -e

NDK=${NDK:-/home/jhon/Android/Sdk/ndk/29.0.14206865}
JLC=${JLC:-/tmp/jlc/llama/llama}
LLAMA_SRC=${LLAMA_SRC:-/tmp/llama.cpp}
BUILD=${BUILD:-/tmp/jlc-build-vk}
OUT=${OUT:-/tmp/jllama-vulkan}
REPO=${REPO:-$(cd "$(dirname "$0")/.." && pwd)}
THIRDPARTY="$REPO/app/src/main/cpp/thirdparty/include"
MAVEN_DIR="$REPO/local-maven/net/ladenthin/llama-android-vulkan/5.1.0"

# ── Clonar fuentes si no estan ──
if [ ! -f "$JLC/CMakeLists.txt" ]; then
    echo "clonando java-llama.cpp v5.1.0 -> $(dirname "$JLC")"
    git clone --depth 1 --branch v5.1.0 https://github.com/bernardladenthin/java-llama.cpp "$(dirname "$JLC")"
fi
if [ ! -d "$LLAMA_SRC/.git" ]; then
    echo "clonando llama.cpp b10682 -> $LLAMA_SRC"
    git clone --depth 1 --branch b10682 https://github.com/ggml-org/llama.cpp "$LLAMA_SRC"
fi
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

export ANDROID_NDK="$NDK"
export ANDROID_NDK_HOME="$NDK"

SPIRV_INC="$NDK/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
SPIRV_CFG="$BUILD/yumenode-spirv-headers"
mkdir -p "$SPIRV_CFG"
cat > "$SPIRV_CFG/SPIRV-HeadersConfig.cmake" <<EOF
set(SPIRV-Headers_FOUND TRUE)
set(SPIRV-Headers_INCLUDE_DIR "${SPIRV_INC}")
set(SPIRV-Headers_INCLUDE_DIRS "${SPIRV_INC}")
if(NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${SPIRV_INC}")
endif()
EOF

JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which javac))))}"
mkdir -p "$BUILD" "$OUT"

# ── Parches propios (tools/patches/) ──
# 0001: ggml-vulkan en Android (dispatcher + buffer device address)
# 0002: jllama.cpp (casts de JNI que el NDK moderno exige)
apply_patch() { # $1=repo $2=patch
    if git -C "$1" apply --check "$2" 2>/dev/null; then
        git -C "$1" apply "$2"
    elif git -C "$1" apply --reverse --check "$2" 2>/dev/null; then
        echo "  (ya aplicado)"
    else
        echo "  NO SE PUDO APLICAR $2 en $1"; exit 1
    fi
}
# 0001 toca ggml-vulkan (llama.cpp); 0002 toca jllama.cpp (java-llama.cpp)
# java-llama.cpp trae sus propios parches para llama.cpp (patches/): con
# FETCHCONTENT_SOURCE_DIR_LLAMA.CPP el PATCH_COMMAND de FetchContent no corre,
# asi que se aplican con su propio script (stamp idempotente). Van ANTES que
# el 0001 propio: su script exige arbol pristino o stamp coincidente.
cmake -DPATCH_DIR="$JLC/patches" -DLLAMA_SRC="$LLAMA_SRC" \
    -P "$JLC/cmake/apply-llama-patches.cmake"
apply_patch "$LLAMA_SRC" "$REPO/tools/patches/0001-ggml-vulkan-android-dispatcher-and-bda.patch"
apply_patch "$(dirname "$JLC")" "$REPO/tools/patches/0002-jllama-jni-casts-for-modern-ndk.patch"

cmake -S "$JLC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DJNI_INCLUDE_DIRS="$JAVA_HOME/include:$JAVA_HOME/include/linux" \
    -DOS_NAME=Android \
    -DOS_ARCH=arm64-v8a \
    -DGGML_VULKAN=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DJLLAMA_BACKEND_DL=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DVulkan_GLSLC_EXECUTABLE="$NDK/shader-tools/linux-x86_64/glslc" \
    -DSPIRV-Headers_DIR="$SPIRV_CFG" \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA.CPP="$LLAMA_SRC" \
    -DCMAKE_C_FLAGS="-I$THIRDPARTY -I$SPIRV_INC -march=armv8.2-a+dotprod+fp16 -O3" \
    -DCMAKE_CXX_FLAGS="-I$THIRDPARTY -I$SPIRV_INC -DJNIEnv_=_JNIEnv -march=armv8.2-a+dotprod+fp16 -O3" \
    -DCMAKE_SHARED_LINKER_FLAGS="-march=armv8.2-a+dotprod+fp16" \
    2>&1 | tail -5

cmake --build "$BUILD" --target jllama -j2 2>&1 | tail -10

SO="$JLC/src/main/resources_linux_vulkan/net/ladenthin/llama/Android/arm64-v8a/libjllama.so"
[ -f "$SO" ] || SO=$(find "$BUILD" -name "libjllama.so" 2>/dev/null | head -1)
[ -n "$SO" ] || { echo "NO SE HA GENERADO libjllama.so"; exit 1; }
cp "$SO" "$OUT/libjllama.so"
"$STRIP" --strip-all "$OUT/libjllama.so"
echo "=== $OUT/libjllama.so ==="
ls -la "$OUT/libjllama.so"
echo "backend vulkan: $(nm -D "$OUT/libjllama.so" | grep -ciE 'ggml_vk|ggml_backend_vk') simbolos"
readelf -d "$OUT/libjllama.so" | grep NEEDED

# ── Empaquetar el AAR en local-maven/ ──
# La plantilla (classes.jar, AndroidManifest, R.txt, proguard) es la del AAR
# que ya trae este pipeline en el historial (b26ce0b); solo se sustituye la
# libreria nativa por la recien compilada. Si la plantilla no existe, el
# script falla: no se inventa un AAR.
AAR_TMP=$(mktemp -d)
git -C "$REPO" show b26ce0b:local-maven/net/ladenthin/llama-android-vulkan/5.1.0/llama-android-vulkan-5.1.0.aar > "$AAR_TMP/base.aar" \
    || { echo "NO SE PUDO EXTRAER LA PLANTILLA DEL AAR"; exit 1; }
mkdir -p "$AAR_TMP/aar/jni/arm64-v8a"
cd "$AAR_TMP/aar" && unzip -q "$AAR_TMP/base.aar"
cp "$OUT/libjllama.so" jni/arm64-v8a/libjllama.so
zip -qr "$AAR_TMP/llama-android-vulkan-5.1.0.aar" . -x "*.DS_Store"
mkdir -p "$MAVEN_DIR"
cp "$AAR_TMP/llama-android-vulkan-5.1.0.aar" "$MAVEN_DIR/"
git -C "$REPO" show b26ce0b:local-maven/net/ladenthin/llama-android-vulkan/5.1.0/llama-android-vulkan-5.1.0.pom > "$MAVEN_DIR/llama-android-vulkan-5.1.0.pom" \
    || { echo "NO SE PUDO EXTRAER EL POM"; exit 1; }
rm -rf "$AAR_TMP"
echo "=== AAR listo: $MAVEN_DIR/llama-android-vulkan-5.1.0.aar ==="
ls -la "$MAVEN_DIR/"
echo "BUILD OK"
