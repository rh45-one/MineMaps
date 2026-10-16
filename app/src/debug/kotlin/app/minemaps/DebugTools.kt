package app.minemaps

import leakcanary.AppWatcher
import leakcanary.LeakCanary
import shark.AndroidReferenceMatchers

/** Debug builds only: LeakCanary watching, heap dumps and notifications are on only while "Debug mode" is switched on. */
object DebugTools {
    private val carBinder = AndroidReferenceMatchers.instanceFieldLeak(
        className = "androidx.car.app.CarAppBinder",
        fieldName = "mService",
        description = "Car App Library binder: held by the Android Auto host until it drops its proxy after Service#onDestroy; not an app leak and it does not accumulate.",
    )

    fun apply(enabled: Boolean) {
        AppWatcher.config = AppWatcher.config.copy(enabled = enabled)
        LeakCanary.config = LeakCanary.config.copy(
            dumpHeap = enabled,
            referenceMatchers = AndroidReferenceMatchers.appDefaults + carBinder,
        )
    }
}
