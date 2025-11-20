cmake_minimum_required(VERSION 3.22.1)

project(kotopogoda_enhance)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wall -Wextra -Wno-unused-parameter")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -Ofast -ffast-math")

# NCNN путь и заголовки
set(NCNN_ROOT ${CMAKE_SOURCE_DIR}/ncnn)
set(NCNN_LIB_DIR ${CMAKE_SOURCE_DIR}/ncnn-lib/${ANDROID_ABI})

# NCNN статическая библиотека
add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES 
    IMPORTED_LOCATION ${NCNN_LIB_DIR}/libncnn.a
)

# Находим системные библиотеки Android
find_library(log-lib log REQUIRED)
find_library(android-lib android REQUIRED)
find_library(jnigraphics-lib jnigraphics REQUIRED)

# Основная библиотека
add_library(kotopogoda_enhance SHARED
    native_enhance_jni.cpp
    ncnn_engine.cpp
    restormer_backend.cpp
    zerodce_backend.cpp
    tile_processor.cpp
    sha256_verifier.cpp
    hann_window.cpp
)

# Включаем директории
target_include_directories(kotopogoda_enhance PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${NCNN_ROOT}/include
)


# Включаем OpenMP поддержку. Используем статическую линковку, чтобы не
# зависеть от наличия libomp.so в APK и избежать ошибок загрузки. Флаг
# `-fopenmp` также добавляем на стадию линковки, иначе clang не подтягивает
# libomp, из-за чего сборка падала с ошибками `undefined symbol __kmpc_*`.
target_compile_options(kotopogoda_enhance PRIVATE -fopenmp)
target_link_options(kotopogoda_enhance PRIVATE -fopenmp -static-openmp)

# Полностью отключаем Vulkan/GLSL путь в NCNN: энхансер теперь всегда
# использует CPU backend, поэтому не нужно притягивать символы glslang.
# Это избавляет от линковки с большим стеком GPU-зависимостей.
target_compile_definitions(kotopogoda_enhance PRIVATE
    NCNN_VULKAN=0
)

# Линкуем библиотеки
target_link_libraries(kotopogoda_enhance
    ncnn
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

# Опции компиляции для ARM NEON
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(kotopogoda_enhance PRIVATE
        -march=armv8-a
        -mtune=cortex-a53
    )
endif()

# Опции для x86_64 (эмулятор)
if(ANDROID_ABI STREQUAL "x86_64")
    target_compile_options(kotopogoda_enhance PRIVATE
        -msse4.2
        -mpopcnt
    )
endif()
