cmake_minimum_required(VERSION 3.18)

project(AndroidSysVSHM C)
message("Building ${PROJECT_NAME}")

# NDK 27's flags.cmake uses IN_LIST if() operator, which requires
# CMake >= 3.12 with policy CMP0057 set to NEW. Modern AGP installs
# CMake 3.22 by default which is fine, but the policy must be set
# explicitly to suppress the warning + error.
if(POLICY CMP0057)
    cmake_policy(SET CMP0057 NEW)
endif()

set(CMAKE_VERBOSE_MAKEFILE on)
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -std=gnu99 -fPIC")

MESSAGE(STATUS "Compiler options: ${CMAKE_C_FLAGS}")

add_library(android-sysvshm SHARED android_sysvshm.c)
# Ensure we don't depend on libdl/libc pthread etc explicitly — the
# NDK toolchain will pick those up automatically.
target_link_libraries(android-sysvshm log)
