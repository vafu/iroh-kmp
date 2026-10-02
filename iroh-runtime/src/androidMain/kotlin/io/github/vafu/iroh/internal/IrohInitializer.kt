package io.github.vafu.iroh.internal

import android.content.Context
import androidx.startup.Initializer
import io.github.vafu.iroh.initializeAndroidIroh

/** Installs Android process state before an endpoint can initialize its DNS resolver. */
class IrohInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        initializeAndroidIroh(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
