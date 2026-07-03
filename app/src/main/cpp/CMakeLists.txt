cmake_minimum_required(VERSION 3.22.1)
project("fqsigner")

add_library(fqsigner SHARED fqsigner.cpp)
target_link_libraries(fqsigner log dl)
