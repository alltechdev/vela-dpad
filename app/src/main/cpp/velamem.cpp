// Native-allocator purge. The ONLY reason this module exists.
//
// Issue #83 gave every big holder a release() and fanned OS trims out to them (MemoryPressure), but
// a Kotlin release only hands pages back to the ALLOCATOR, not to the kernel. Scudo keeps them on
// its free lists, so RSS/PSS barely moves and the OOM killer still sees a fat process.
//
// Measured on the M5 (2.9 GB, Android 13, app.vela.debug, PR #85 build, all 8 listeners firing):
// a full TRIM_MEMORY_COMPLETE moved scudo:primary only 56,578 -> 54,978 KB while mallinfo reported
// a 442 MB arena holding just 46 MB live. That gap is what mallopt() reclaims and nothing on the
// Java side can touch.
//
// bionic exposes exactly one lever for it, and only through libc:
//   M_PURGE     (API 28+) release free memory in the calling thread's arena
//   M_PURGE_ALL (API 34+) walk every arena; documented as able to take 2x+ a plain M_PURGE
//
// The values are ABI-stable, so they are spelled out rather than taken from <malloc.h>, which
// keeps the build independent of NDK header vintage. An unsupported option makes mallopt() return
// 0, so calling M_PURGE_ALL on an API 33 device is a harmless no-op that falls through to M_PURGE.

#include <jni.h>
#include <malloc.h>

#ifndef M_PURGE
#define M_PURGE (-101)
#endif
#ifndef M_PURGE_ALL
#define M_PURGE_ALL (-104)
#endif

// Returns which lever actually took, so the Kotlin side can log it and a device that supports
// neither is visible in logcat instead of silently doing nothing:
//   2 = M_PURGE_ALL, 1 = M_PURGE, 0 = neither supported
extern "C" JNIEXPORT jint JNICALL
Java_app_vela_ui_MemoryPressure_nativePurge(JNIEnv*, jobject, jboolean all) {
    if (all && mallopt(M_PURGE_ALL, 0) != 0) return 2;
    if (mallopt(M_PURGE, 0) != 0) return 1;
    return 0;
}
