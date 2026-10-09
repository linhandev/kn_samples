@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import capi.alignment.probe.CapiAlignment_MissingApi
import kotlinx.cinterop.*
import platform.posix.*
import platform.PerformanceAnalysisKit.HiLog.*

fun main() {
    memScoped {
        val time = alloc<timespec>()
        check(clock_gettime(CLOCK_MONOTONIC.toInt(), time.ptr) == 0)
        OH_LOG_Print(LOG_APP, platform.PerformanceAnalysisKit.HiLog.LOG_INFO, 0x1234u, "capi_alignment", "%{public}s", "before missing API".cstr.ptr)
    }
    repeat(3) {
        val error = try {
            CapiAlignment_MissingApi()
            null
        } catch (e: IllegalStateException) {
            e
        }
        check(error != null) { "缺失 API 没有抛出 IllegalStateException" }
        check(error.message?.contains("CapiAlignment_MissingApi") == true) { error.toString() }
        println("CAPI_MISSING_CAUGHT $it: ${error.message}")
    }
    memScoped {
        val time = alloc<timespec>()
        check(clock_gettime(CLOCK_MONOTONIC.toInt(), time.ptr) == 0)
    }
    println("CAPI_PROTECTION_PASS")
}
