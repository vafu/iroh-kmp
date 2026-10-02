@file:kotlin.jvm.JvmName("IrohAndroidApi")

package io.github.vafu.iroh

import android.content.Context

/**
 * Installs the application context required by Iroh's Android DNS runtime.
 *
 * AndroidX Startup performs this automatically. Call this only when automatic
 * initialization has been disabled in the application's manifest.
 */
fun initializeAndroidIroh(context: Context) {
    AndroidNativeBindings.installAndroidContext(context.applicationContext)
}
