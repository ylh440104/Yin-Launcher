cmake_minimum_required(VERSION 3.18)
project(jvm_bridge C)

# JVM 桥：dlopen(libjvm.so) + JNI_CreateJavaVM，全程不 execve
add_library(jvm_bridge SHARED jvm_bridge.c)
target_link_libraries(jvm_bridge dl android log)

# 注意：liblinkerhook.so 直接使用 Pojav payload 里自带的那份
# （CI 从 payload/extract/lib/arm64-v8a/*.so 拷进 jniLibs），
# 这里再用 CMake 编一份会和它冲突：mergeDebugNativeLibs 报
# "2 files found with path 'lib/arm64-v8a/liblinkerhook.so'"。