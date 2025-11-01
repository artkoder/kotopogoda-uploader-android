#!/bin/bash
set -e

NCNN_VERSION="20240820"
NCNN_URL="https://github.com/Tencent/ncnn/releases/download/${NCNN_VERSION}/ncnn-${NCNN_VERSION}-android-vulkan.zip"
TARGET_DIR="app/src/main/cpp/ncnn-lib"

echo "Загрузка NCNN ${NCNN_VERSION} для Android с Vulkan..."

mkdir -p "${TARGET_DIR}"
cd "${TARGET_DIR}"

if [ -f "ncnn-${NCNN_VERSION}-android-vulkan.zip" ]; then
    echo "Архив уже загружен, пропускаем загрузку"
else
    curl -L -o "ncnn-${NCNN_VERSION}-android-vulkan.zip" "${NCNN_URL}"
fi

echo "Распаковка архива..."
unzip -q -o "ncnn-${NCNN_VERSION}-android-vulkan.zip"

echo "Копирование библиотек..."
mkdir -p arm64-v8a x86_64

if [ -d "ncnn-${NCNN_VERSION}-android-vulkan/arm64-v8a" ]; then
    cp -r ncnn-${NCNN_VERSION}-android-vulkan/arm64-v8a/lib/*.so arm64-v8a/ || true
    cp -r ncnn-${NCNN_VERSION}-android-vulkan/arm64-v8a/lib/*.a arm64-v8a/ || true
fi

if [ -d "ncnn-${NCNN_VERSION}-android-vulkan/x86_64" ]; then
    cp -r ncnn-${NCNN_VERSION}-android-vulkan/x86_64/lib/*.so x86_64/ || true
    cp -r ncnn-${NCNN_VERSION}-android-vulkan/x86_64/lib/*.a x86_64/ || true
fi

mkdir -p include
if [ -d "ncnn-${NCNN_VERSION}-android-vulkan/arm64-v8a/include" ]; then
    cp -r ncnn-${NCNN_VERSION}-android-vulkan/arm64-v8a/include/ncnn include/
fi

echo "Очистка временных файлов..."
rm -rf "ncnn-${NCNN_VERSION}-android-vulkan"
rm -f "ncnn-${NCNN_VERSION}-android-vulkan.zip"

echo "NCNN успешно установлен в ${TARGET_DIR}"
