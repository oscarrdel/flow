cmake_minimum_required(VERSION 3.22.1)
project(flowmp3 LANGUAGES C)

set(LAME_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/lame")
file(GLOB LAME_SOURCES CONFIGURE_DEPENDS "${LAME_ROOT}/libmp3lame/*.c")
add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC "${LAME_ROOT}/include" PRIVATE "${LAME_ROOT}/libmp3lame")
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS HAVE_STDINT_H HAVE_LIMITS_H
    HAVE_ERRNO_H HAVE_FCNTL_H ieee754_float32_t=float)
target_link_libraries(mp3lame PRIVATE m)

add_library(flowmp3 SHARED flowmp3.c)
target_link_libraries(flowmp3 PRIVATE mp3lame)

foreach(target mp3lame flowmp3)
    target_compile_options(${target} PRIVATE -O3)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
endforeach()
