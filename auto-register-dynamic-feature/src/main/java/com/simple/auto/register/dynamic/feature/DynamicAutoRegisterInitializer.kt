package com.simple.auto.register.dynamic.feature

import android.content.Context
import androidx.startup.Initializer
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import com.simple.auto.register.AutoRegisterInitializer
import com.simple.auto.register.AutoRegisterManager

/**
 * [DynamicAutoRegisterInitializer] is a specialized initializer for handling Dynamic Features (DF).
 *
 * This provides an alternative solution to Google's **AutoService** (and standard Java **ServiceLoader**),
 * which often struggles with Dynamic Features because:
 * 1. **Class Loading:** Standard ServiceLoader might not automatically discover implementations in
 *    newly installed dynamic modules without manual classloader management.
 * 2. **Lifecycle:** Dynamic modules are installed at runtime. AutoService doesn't provide a built-in
 *    mechanism to "wake up" and register new services immediately upon installation.
 *
 * This initializer solves these problems by:
 * - Scanning and loading all **already installed** dynamic modules on app startup.
 * - Registering a listener to **automatically detect and load** newly installed dynamic modules
 *   the moment they become available, ensuring services are registered without an app restart.
 *
 * It ensures that any `@AutoRegister` implementations inside a Dynamic Feature are seamlessly
 * integrated into [AutoRegisterManager].
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
