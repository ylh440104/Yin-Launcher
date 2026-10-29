cmake_minimum_required(VERSION 3.18)
project(jvm_bridge C)

# JVM 桥：dlopen(libjvm.so) + JNI_CreateJavaVM，全程不 execve
add_library(jvm_bridge SHARED jvm_bridge.c)
target_link_libraries(jvm_bridge dl android log)

# linkerhook：hook android_dlopen_ext，把 sphal/vendor/default namespace 暴露出来。
# libpojavexec.so 内部 dlopen("/system/lib64/libEGL.so") 时依赖它，缺了会拿到
# 空函数指针，进而在 Render thread 上跳地址 0（SEGV_MAPERR addr 0x0）。
add_library(linkerhook SHARED pojav/hook.c pojav/nsbypass.c)
target_link_libraries(linkerhook dl android log)
# 与 Pojav 的 Android.mk 一致：-z global 让符号进全局表
target_link_options(linkerhook PRIVATE "-Wl,-z,global")