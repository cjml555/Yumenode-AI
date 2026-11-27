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
JLC=${JLC:-/home/jhon/Yumenode-AI/build/jlc/llama/llama}
LLAMA_SRC=${LLAMA_SRC:-/home/jhon/Yumenode-AI/build/llama.cpp}
BUILD=${BUILD:-/home/jhon/Yumenode-AI/build/jlc-build-vk}
OUT=${OUT:-/home/jhon/Yumenode-AI/build/jllama-vulkan}
REPO=${REPO:-$(cd "$(dirname "$0")/.." && pwd)}
THIRDPARTY="$REPO/app/src/main/cpp/thirdparty/include"
MAVEN_DIR="$REPO/local-maven/net/ladenthin/llama-android-vulkan/6.0.0"
TEMPLATE_VER="5.1.0"

# ── Clonar fuentes si no estan ──
if [ ! -f "$JLC/CMakeLists.txt" ]; then
    echo "clonando java-llama.cpp v6.0.0 -> $(dirname "$JLC")"
    git clone --depth 1 --branch v6.0.0 https://github.com/bernardladenthin/java-llama.cpp "$(dirname "$JLC")"
fi
if [ ! -d "$LLAMA_SRC/.git" ]; then
    echo "clonando llama.cpp b11887 (commit 6050b467c07a95c7b87debd4ddac2f8ba8d9b807) -> $LLAMA_SRC"
    git clone https://github.com/ggml-org/llama.cpp "$LLAMA_SRC"
    cd "$LLAMA_SRC" && git checkout 6050b467c07a95c7b87debd4ddac2f8ba8d9b807
else
    # Resetear a commit exacto para evitar conflictos de parches
    cd "$LLAMA_SRC" && git fetch origin && git reset --hard 6050b467c07a95c7b87debd4ddac2f8ba8d9b807
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
# 0001 y 0002 YA están en upstream (b11887 / java-llama.cpp v6.0.0):
# - 0001 (dispatcher Vulkan + BDA guard): líneas 4786-4815 en ggml-vulkan.cpp
# - 0002 (JNI casts): upstream v6.0.0 ya tiene casts correctos en GetEnv/AttachCurrentThread
#   Única excepción: get_jni_env_attaching() línea 397 usa reinterpret_cast<void**> para
#   AttachCurrentThreadAsDaemon; parcheado manualmente en el source ya.
# java-llama.cpp trae sus propios parches (0018 AMX fix, etc.); se aplican con su script.
# Nuestros parches custom (0022, 0023, 0024) están en $JLC/patches/ y se aplican automáticamente.
cmake -DPATCH_DIR="$JLC/patches" -DLLAMA_SRC="$LLAMA_SRC" \
    -P "$JLC/cmake/apply-llama-patches.cmake"
# NO aplicamos 0001 ni 0002: ya integrados/fixeados

cmake -S "$JLC" -B "$BUILD" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DJAVA_HOME="$JAVA_HOME" \
    -DJNI_INCLUDE_DIRS="$JAVA_HOME/include:$JAVA_HOME/include/linux" \
    -DOS_NAME="Linux-Android" \
    -DOS_ARCH=arm64-v8a \
    -DGGML_VULKAN=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DJLLAMA_BACKEND_DL=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DCMAKE_CXX_STANDARD=17 \
    -DVulkan_GLSLC_EXECUTABLE="$NDK/shader-tools/linux-x86_64/glslc" \
    -DSPIRV-Headers_DIR="$SPIRV_CFG" \
    -DFETCHCONTENT_SOURCE_DIR_LLAMA.CPP="$LLAMA_SRC" \
    -DCMAKE_C_FLAGS="-I$THIRDPARTY -I$SPIRV_INC -march=armv8.2-a+dotprod+fp16 -O3" \
    -DCMAKE_CXX_FLAGS="-I$THIRDPARTY -I$SPIRV_INC -DJNIEnv_=_JNIEnv -march=armv8.2-a+dotprod+fp16 -O3" \
    -DCMAKE_SHARED_LINKER_FLAGS="-march=armv8.2-a+dotprod+fp16" \
    2>&1 | tail -5

cmake --build "$BUILD" --target jllama -j2 2>&1 | tail -10

SO="$JLC/src/main/resources_linux_vulkan/net/ladenthin/llama/Android/arm64-v8a/libjllama.so"
[ -f "$SO" ] || SO=$(find "$JLC/src/main/natives" -name "libjllama.so" 2>/dev/null | head -1)
[ -f "$SO" ] || SO=$(find "$BUILD" -name "libjllama.so" 2>/dev/null | head -1)
[ -n "$SO" ] || { echo "NO SE HA GENERADO libjllama.so"; exit 1; }
cp "$SO" "$OUT/libjllama.so"
"$STRIP" --strip-all "$OUT/libjllama.so"
echo "=== $OUT/libjllama.so ==="
ls -la "$OUT/libjllama.so"
echo "backend vulkan: $(nm -D "$OUT/libjllama.so" | grep -ciE 'ggml_vk|ggml_backend_vk') simbolos"
readelf -d "$OUT/libjllama.so" | grep NEEDED

# ── Empaquetar el AAR en local-maven/ ──
# ATENCIÓN: la plantilla (classes.jar) TIENE que ser de la MISMA versión que el
# .so. En 6.0.0 se usó la plantilla de 5.1.0 y el JNI_OnLoad del .so abortaba
# al no encontrar net.ladenthin.llama.exception.InvalidRequestException: la app
# moría al cargar cualquier GGUF (v1.4.4/v1.4.5). Si vuelves a empaquetar,
# arranca de local-maven/net/ladenthin/llama-android/<VER>/ con la versión
# equivalente, no de una plantilla vieja.
# que ya trae este pipeline en el historial (b26ce0b, versión 5.1.0); solo se
# sustituye la libreria nativa por la recien compilada. Si la plantilla no
# existe, el script falla: no se inventa un AAR.
AAR_TMP=$(mktemp -d)
git -C "$REPO" show b26ce0b:local-maven/net/ladenthin/llama-android-vulkan/$TEMPLATE_VER/llama-android-vulkan-$TEMPLATE_VER.aar > "$AAR_TMP/base.aar" \
    || { echo "NO SE PUDO EXTRAER LA PLANTILLA DEL AAR"; exit 1; }
mkdir -p "$AAR_TMP/aar/jni/arm64-v8a"
cd "$AAR_TMP/aar" && unzip -q "$AAR_TMP/base.aar"
cp "$OUT/libjllama.so" jni/arm64-v8a/libjllama.so
zip -qr "$AAR_TMP/llama-android-vulkan-6.0.0.aar" . -x "*.DS_Store"
mkdir -p "$MAVEN_DIR"
cp "$AAR_TMP/llama-android-vulkan-6.0.0.aar" "$MAVEN_DIR/"
git -C "$REPO" show b26ce0b:local-maven/net/ladenthin/llama-android-vulkan/$TEMPLATE_VER/llama-android-vulkan-$TEMPLATE_VER.pom > "$MAVEN_DIR/llama-android-vulkan-6.0.0.pom" \
    || { echo "NO SE PUDO EXTRAER EL POM"; exit 1; }
# Actualizar versión en POM de 5.1.0 a 6.0.0
sed -i 's|<version>5.1.0</version>|<version>6.0.0</version>|' "$MAVEN_DIR/llama-android-vulkan-6.0.0.pom"
rm -rf "$AAR_TMP"
echo "=== AAR listo: $MAVEN_DIR/llama-android-vulkan-6.0.0.aar ==="
ls -la "$MAVEN_DIR/"
echo "BUILD OK"
