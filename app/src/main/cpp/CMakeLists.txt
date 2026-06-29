cmake_minimum_required(VERSION 3.18)
project(evgrab C)

# Self-contained evdev grabber, built as a normal JNI shared library (libevgrab.so). It is loaded with
# System.load() inside the Shizuku UserService's privileged process (KeyGrabberService) — no external
# deps, no /data/local/tmp copy, no exec. Raw kernel ioctls + JNI callbacks.
add_library(evgrab SHARED evgrab.c)
target_compile_options(evgrab PRIVATE -Wall -Wextra -Wno-unused-parameter -O2)
target_link_libraries(evgrab log)
