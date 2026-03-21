package com.simple.auto.register

import android.content.Context
import androidx.startup.Initializer

/**
 * Android Startup Initializer for AutoRegister.
 * This automatically triggers the module discovery and registration process
 * during the application startup.
 */
class AutoRegisterInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        AutoRegisterManager.loadModules()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}
