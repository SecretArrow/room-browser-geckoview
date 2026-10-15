cmake_minimum_required(VERSION 3.16)
project(RoomBrowserDesktop C)
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(ROOMBROWSER_VERSION "1.0.86" CACHE STRING "Room Browser release version")
option(RB_BUILD_TESTS "Build the core unit tests" OFF)
# The GTK / WebView2 GUI needs a full desktop toolkit installed.  Turning it
# off builds rb_core and the tests alone, which is what the core-only
# development loop and the sanitizer job use.
option(RB_BUILD_GUI "Build the platform GUI executables (GTK / Win32)" ON)
configure_file(src/rb_version.h.in ${CMAKE_CURRENT_BINARY_DIR}/rb_version.h @ONLY)
add_library(rb_core STATIC
    src/core/rb_str.c
    src/core/rb_json.c
    src/core/rb_notes.c
    src/core/rb_totp.c
    src/core/rb_url.c
    src/core/rb_search.c
    src/core/rb_paths.c
    src/core/rb_settings.c
    src/core/rb_prefs.c
    src/core/rb_profile.c
    src/core/rb_tabs.c
    src/core/rb_history.c
    src/core/rb_bookmarks.c
    src/core/rb_ua.c
    src/core/rb_theme.c
    src/core/rb_filters.c
    src/core/rb_filterlist.c
    src/core/rb_https.c
    src/core/rb_downloads.c
    src/core/rb_dns.c
    src/core/rb_ipconflict.c
    src/core/rb_devices.c
    src/core/rb_device.c
    src/core/rb_switch.c)
target_include_directories(rb_core PUBLIC src ${CMAKE_CURRENT_BINARY_DIR})

if(RB_BUILD_GUI AND WIN32)
    set(WEBVIEW2_INCLUDE_DIR "" CACHE PATH "Path to the WebView2 SDK include directory")
    if(NOT WEBVIEW2_INCLUDE_DIR)
        message(FATAL_ERROR "WEBVIEW2_INCLUDE_DIR must be set to the WebView2 SDK include directory")
    endif()
    string(REPLACE "." "," RB_VERSION_COMMA "${ROOMBROWSER_VERSION}")
    configure_file(src/win/app.rc.in ${CMAKE_CURRENT_BINARY_DIR}/app.rc @ONLY)
    configure_file(src/win/app.manifest ${CMAKE_CURRENT_BINARY_DIR}/app.manifest COPYONLY)
    configure_file(assets/icon.ico ${CMAKE_CURRENT_BINARY_DIR}/icon.ico COPYONLY)
    add_executable(RoomBrowser WIN32
        src/win/main.c
        src/win/chrome.c
        src/win/downloads.c
        src/win/prefs.c
        src/win/webview.c
        src/win/notes.c
        src/win/totp.c
        ${CMAKE_CURRENT_BINARY_DIR}/app.rc)
    target_include_directories(RoomBrowser PRIVATE ${WEBVIEW2_INCLUDE_DIR} src/win)
    target_link_libraries(RoomBrowser PRIVATE rb_core user32 gdi32 comctl32 dwmapi shell32 ole32 oleaut32)
    # The manifest is embedded ONCE via the rc resource (1 RT_MANIFEST).
    # MSVC's linker would additionally auto-generate AND embed its own
    # manifest -> CVTRES CVT1100 "duplicate resource MANIFEST/1" (CI-proven).
    # Disable the linker manifest; the rc-embedded one stays authoritative.
    if(MSVC)
        target_link_options(RoomBrowser PRIVATE "/MANIFEST:NO")
    endif()
endif()

if(RB_BUILD_GUI AND UNIX AND NOT APPLE)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(GTK REQUIRED IMPORTED_TARGET gtk+-3.0 webkit2gtk-4.1)
    add_executable(roombrowser
        src/gtk/main.c
        src/gtk/chrome.c
        src/gtk/webview.c
        src/gtk/notes.c
        src/gtk/totp.c)
    target_include_directories(roombrowser PRIVATE src/gtk)
    target_link_libraries(roombrowser PRIVATE rb_core PkgConfig::GTK)
endif()

if(RB_BUILD_TESTS)
    add_executable(rb_tests tests/test_core.c)
    target_link_libraries(rb_tests PRIVATE rb_core)
endif()
