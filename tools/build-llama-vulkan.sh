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
VER="6.0.0"                     # version de java-llama.cpp Y del AAR oficial (deben coincidir)
MAVEN_URL="https://repo1.maven.org/maven2/net/ladenthin/llama-android/$VER/llama-android-$VER.aar"

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
# NDK r29: AttachCurrentThreadAsDaemon declara JNIEnv** (las NDK viejas, void**).
# java-llama.cpp v6.0.0 pasa un reinterpret_cast<void**> y el compilador lo rechaza.
python3 - "$JLC/src/main/cpp/jllama.cpp" <<'PYFIX'
import sys
p = sys.argv[1]
s = open(p, encoding="utf-8").read()
n = s.count("AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env)")
s = s.replace("AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env)",
              "AttachCurrentThreadAsDaemon(&env")
open(p, "w", encoding="utf-8").write(s)
print("jllama.cpp: casts AttachCurrentThreadAsDaemon corregidos:", n)
PYFIX
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
# Base = AAR OFICIAL de la MISMA version (classes.jar, manifest, R.txt, proguard).
# Se cachea para no bajarlo en cada build.
BASE_AAR="$BUILD/llama-android-$VER.aar"
if [ ! -f "$BASE_AAR" ]; then
    echo "descargando AAR oficial $VER -> $BASE_AAR"
    curl -fsSL "$MAVEN_URL" -o "$BASE_AAR" || { echo "NO SE PUDO DESCARGAR EL AAR OFICIAL"; exit 1; }
fi
mkdir -p "$AAR_TMP/aar"
cd "$AAR_TMP/aar" && unzip -q "$BASE_AAR"
# Solo arm64-v8a (el APK filtra esa ABI) y con NUESTRO libjllama con Vulkan.
rm -rf jni && mkdir -p jni/arm64-v8a
cp "$OUT/libjllama.so" jni/arm64-v8a/libjllama.so
# Reempaquetar (zip no está garantizado en todas las distros: python3 siempre está)
python3 - "$AAR_TMP" "$AAR_TMP/llama-android-vulkan-$VER.aar" <<'PYZIP'
import os, sys, zipfile
root, out = sys.argv[1] + "/aar", sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _dirs, files in os.walk(root):
        for f in files:
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, root))
print("aar:", out)
PYZIP
mkdir -p "$MAVEN_DIR"
cp "$AAR_TMP/llama-android-vulkan-$VER.aar" "$MAVEN_DIR/"
# POM: el oficial con nuestras coordenadas (mismas dependencias que las clases)
curl -fsSL "https://repo1.maven.org/maven2/net/ladenthin/llama-android/$VER/llama-android-$VER.pom" \
    | sed "s|<artifactId>llama-android</artifactId>|<artifactId>llama-android-vulkan</artifactId>|" \
    > "$MAVEN_DIR/llama-android-vulkan-$VER.pom" || { echo "NO SE PUDO GENERAR EL POM"; exit 1; }
rm -rf "$AAR_TMP"
echo "=== AAR listo: $MAVEN_DIR/llama-android-vulkan-6.0.0.aar ==="
ls -la "$MAVEN_DIR/"
echo "BUILD OK"
