cmake_minimum_required(VERSION 3.22.1)
project("rawcurveeditor" LANGUAGES C CXX)

option(USE_LIBRAW "Fetch and build LibRaw from source for full 16-bit RAW decoding" ON)

include(FetchContent)

set(EXTRA_LIBS "")
set(COMPILE_DEFS "")

if(USE_LIBRAW)
    message(STATUS "Configuring LibRaw via FetchContent...")
    FetchContent_Declare(
        libraw_src
        GIT_REPOSITORY https://github.com/LibRaw/LibRaw.git
        GIT_TAG        0.21.2
        GIT_SHALLOW    TRUE
    )
    FetchContent_GetProperties(libraw_src)
    if(NOT libraw_src_POPULATED)
        FetchContent_Populate(libraw_src)
    endif()

    # Collect LibRaw source files directly to avoid autotools dependency on Android NDK
    file(GLOB LIBRAW_SOURCES
        "${libraw_src_SOURCE_DIR}/src/decoders/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/decompressors/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/demosaic/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/integration/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/metadata/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/postprocessing/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/preprocessing/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/tables/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/utils/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/write/*.cpp"
        "${libraw_src_SOURCE_DIR}/src/x3f/*.cpp"
    )

    add_library(raw_static STATIC ${LIBRAW_SOURCES})
    target_include_directories(raw_static PUBLIC "${libraw_src_SOURCE_DIR}")
    target_compile_definitions(raw_static PUBLIC
        LIBRAW_NODLL
        LIBRAW_NOTHREADS
    )
    target_compile_options(raw_static PRIVATE -w -O3 -ffast-math)

    list(APPEND EXTRA_LIBS raw_static)
    list(APPEND COMPILE_DEFS HAVE_LIBRAW=1)
endif()

add_library(
    rawcurve
    SHARED
    native-lib.cpp
)

target_compile_definitions(rawcurve PRIVATE ${COMPILE_DEFS})

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    rawcurve
    ${EXTRA_LIBS}
    ${log-lib}
    ${jnigraphics-lib}
)
