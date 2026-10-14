cmake_minimum_required(VERSION 3.22.1)
project(roomnode CXX)

# node.h pulls in V8's headers, which need C++17; the NDK's default is older.
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The shim that lets the app process call node::Start, and nothing else. The
# runtime itself is the prebuilt libnode.so beside it.
add_library(roomnode SHARED node_jni.cpp)

set(LIBNODE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(LIBNODE_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/nodejs-mobile/include/node)

# BOTH HALVES ARE DOWNLOADED BY CI, so a checkout without them still configures.
# The x86 ABI is the reason this is a condition rather than a requirement: the
# pinned release ships arm64-v8a, armeabi-v7a and x86_64 and no 32-bit x86, and
# a debug build with no RB_RELEASE_ABIS set still asks for x86. Linking against
# a file that is not there would fail the whole build for an ABI the runtime was
# never built for; leaving it unlinked instead yields a shim whose start call
# answers "unavailable", which is what the tool then reports.
if(EXISTS ${LIBNODE_DIR}/libnode.so)
    include_directories(${LIBNODE_INCLUDE})
    add_library(libnode SHARED IMPORTED)
    set_target_properties(libnode PROPERTIES IMPORTED_LOCATION ${LIBNODE_DIR}/libnode.so)
    target_compile_definitions(roomnode PRIVATE ROOMNODE_HAVE_LIBNODE=1)
    # libnode.so lists libc++_shared.so among its DT_NEEDED entries, so it has
    # to be named here too: that is what puts it in the APK beside the runtime.
    target_link_libraries(roomnode libnode c++_shared)
endif()

find_library(log-lib log)
target_link_libraries(roomnode ${log-lib})
