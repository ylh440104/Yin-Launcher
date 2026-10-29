cmake_minimum_required(VERSION 3.18)
project(jvm_bridge C)

add_library(jvm_bridge SHARED jvm_bridge.c)
target_link_libraries(jvm_bridge dl android log)
