cmake_minimum_required(VERSION 3.18)
project(hdrstudio_ncnn)

add_library(hdrstudio_ncnn SHARED ncnn_sr.cpp)
find_package(ncnn REQUIRED)
target_compile_features(hdrstudio_ncnn PRIVATE cxx_std_17)
target_link_options(hdrstudio_ncnn PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(hdrstudio_ncnn ncnn android log)
