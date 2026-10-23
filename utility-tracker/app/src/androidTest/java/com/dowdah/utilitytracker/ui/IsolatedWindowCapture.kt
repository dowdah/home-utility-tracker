package com.dowdah.utilitytracker.ui

import android.app.Activity
import android.app.UiAutomation
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File

/** Input/surface APIs do not require registering an accessibility automation service. */
internal fun inputAutomation(): UiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
    UiAutomation.FLAG_DONT_USE_ACCESSIBILITY or UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES,
)

internal fun isolatedForegroundActivity(): Activity {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val pkg = instrumentation.targetContext.packageName
    check(pkg.contains(".acceptance"))
    var activity: Activity? = null
    instrumentation.runOnMainSync {
        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .singleOrNull { it.packageName == pkg && it.window.decorView.hasWindowFocus() }
    }
    return checkNotNull(activity) { "Only capture/inject while the isolated application owns the focused window" }
}

/** Capture the app surface, never the whole display or another application's pixels. */
internal fun captureIsolatedWindow(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val window = isolatedForegroundActivity().window
    val bitmap = requireNotNull(inputAutomation().takeScreenshot(window))
    try {
        File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    } finally { bitmap.recycle() }
}
