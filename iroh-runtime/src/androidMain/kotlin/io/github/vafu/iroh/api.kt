@file:kotlin.jvm.JvmName("IrohAndroidApi")

package io.github.vafu.iroh

import android.content.Context

/** Installs the application context required by Iroh's Android socket runtime. */
fun initializeAndroidIroh(context: Context) {
    AndroidNativeBindings.installAndroidContext(context.applicationContext)
}
