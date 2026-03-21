package com.simple.auto.register.dynamic.feature

import android.content.Context
import androidx.startup.Initializer
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.simple.auto.register.AutoRegisterInitializer
import com.simple.auto.register.AutoRegisterManager

/**
 * Automatically listens for dynamic feature installation and triggers
 * module discovery when a new feature is installed.
 */
class DynamicAutoRegisterInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        val splitInstallManager = SplitInstallManagerFactory.create(context)

        // Load already installed modules
        splitInstallManager.installedModules.forEach { moduleName ->
            AutoRegisterManager.loadDynamicModule(moduleName)
        }

        splitInstallManager.registerListener { state ->
            if (state.status() == SplitInstallSessionStatus.INSTALLED) {
                // For each installed module, notify AutoRegisterManager
                state.moduleNames().forEach { moduleName ->
                    AutoRegisterManager.loadDynamicModule(moduleName)
                }
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(AutoRegisterInitializer::class.java)
    }
}
