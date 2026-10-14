// The one native function this app needs: hand a command line to node::Start.
//
// nodejs-mobile ships no JNI of its own -- the C++ entry point is
// `node::Start(int, char**)` in libnode.so -- so this shim is the whole bridge.
// Everything above it is Kotlin, and everything the runtime does is JavaScript
// in the bridge script; nothing else is native, which keeps the surface a
// reviewer has to read down to this one function.

#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#ifdef ROOMNODE_HAVE_LIBNODE
#include <node.h>
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_roombrowser_node_NodeBridge_startNode(
        JNIEnv *env, jclass, jobjectArray arguments) {
#ifdef ROOMNODE_HAVE_LIBNODE
    const jsize count = env->GetArrayLength(arguments);
    std::vector<std::string> parts;
    parts.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; i++) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        if (item == nullptr) {
            parts.emplace_back();
            continue;
        }
        const char *chars = env->GetStringUTFChars(item, nullptr);
        parts.emplace_back(chars == nullptr ? "" : chars);
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(item, chars);
        }
        env->DeleteLocalRef(item);
    }

    // libuv reads the whole command line as one contiguous block, so the
    // strings are copied into a single buffer and the pointers point into it.
    // Handing it an array of separate std::string buffers is the documented way
    // to get a runtime that starts and then reads garbage as its argv.
    size_t total = 0;
    for (const auto &part : parts) {
        total += part.size() + 1;
    }
    std::vector<char> block(total);
    std::vector<char *> argv;
    argv.reserve(parts.size() + 1);
    size_t at = 0;
    for (const auto &part : parts) {
        std::memcpy(&block[at], part.c_str(), part.size() + 1);
        argv.push_back(&block[at]);
        at += part.size() + 1;
    }
    argv.push_back(nullptr);

    return static_cast<jint>(node::Start(static_cast<int>(parts.size()), argv.data()));
#else
    // No libnode.so for this ABI: the caller is told so by the return value and
    // never reaches the runtime.
    (void) env;
    (void) arguments;
    return -1;
#endif
}
