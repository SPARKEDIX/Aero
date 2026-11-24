cmake_minimum_required(VERSION 3.22.1)
project("aero-mirage")

add_library(aero_mirage SHARED mirage_jni.cpp)

add_library(cactus STATIC IMPORTED)
set_target_properties(cactus PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libcactus.a)

target_include_directories(aero_mirage PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(aero_mirage cactus log)
