cmake_minimum_required(VERSION 3.22.1)
project(rk_dl_jni)

add_library(rk_dl_jni SHARED rk_dl_jni.cpp)

target_include_directories(rk_dl_jni PRIVATE ${CMAKE_SOURCE_DIR}/include)

# 16 KB ELF LOAD 对齐（兼容 4 KB / 16 KB 页设备）
target_link_options(rk_dl_jni PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(rk_dl_jni
    ${log-lib}
    ${dl-lib}
)
