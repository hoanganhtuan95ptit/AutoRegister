package com.simple.auto.register

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transform
import java.util.ServiceLoader

/**
 * Manager for handling automatic service registration and discovery.
 * Supports multiple implementations for a single API.
 */
object AutoRegisterManager {

    // Store a Set of implementation class names for each API
    private val services = mutableMapOf<String, MutableSet<String>>()

    // Cache for instantiated implementations to ensure singleton-like behavior
    private val instances = mutableMapOf<String, Any>()

    // SharedFlow to notify listeners when any new service is registered.
    // Use DROP_OLDEST to ensure tryEmit never fails and listeners always get the latest state.
    private val registrationFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Clear all registered services and instances. (Primarily for testing)
     */
    fun clear() {
        services.clear()
        instances.clear()
    }

    /**
     * Automatically discovers and initializes all generated ModuleInitializers
     * using the ServiceLoader (SPI) mechanism.
     * This is called automatically by AutoRegisterInitializer on app startup.
     */
    fun loadModules() {
        try {
            val loader = ServiceLoader.load(ModuleInitializer::class.java, ModuleInitializer::class.java.classLoader)
            for (initializer in loader) {
                initializer.create()
            }
        } catch (e: Exception) {
            // Handle or log initialization error
        }
    }

    /**
     * Registers an implementation class for a specific API interface/class.
     * This is typically called by the generated loader code.
     */
    fun register(api: String, impl: String) {
        val implementations = services.getOrPut(api) { mutableSetOf() }
        if (implementations.add(impl)) {
            // Emit the API name to notify that its implementations have changed
            registrationFlow.tryEmit(api)
        }
    }

    // --- String-based API (Class Names) ---

    /**
     * Synchronously gets all implementation class names for a given API.
     */
    fun getAllNames(api: String): Set<String> {
        return services[api]?.toSet() ?: emptySet()
    }

    /**
     * Asynchronously gets all implementation class names for an API.
     */
    fun getAllNamesAsync(api: String): Flow<Set<String>> {
        return registrationFlow
            .filter { it == api }
            .map { getAllNames(api) }
            .onStart {
                emit(getAllNames(api))
            }
    }

    /**
     * Subscribes to new implementation class names of an API.
     */
    fun subscribeNames(api: String): Flow<Set<String>> {
        val seenClassNames = mutableSetOf<String>()
        return getAllNamesAsync(api).transform { all ->
            val newItems = all.filter { seenClassNames.add(it) }.toSet()
            if (newItems.isNotEmpty()) {
                emit(newItems)
            }
        }
    }

    // --- Instance-based API (Instantiated Objects) ---

    /**
     * Synchronously gets all implementation instances for a given API class.
     * Use this when the API name matches the class name.
     */
    fun <T : Any> getAll(api: Class<T>): Set<T> {
        return getAll(api.name, api)
    }

    /**
     * Synchronously gets all implementation instances for a given API name and class.
     * Use this if the API was registered with a custom name.
     */
    fun <T : Any> getAll(apiName: String, apiClass: Class<T>): Set<T> {
        return getAllNames(apiName).mapNotNull { it.getInstance(apiClass) }.toSet()
    }

    /**
     * Asynchronously gets all implementation instances for an API class.
     * Use this when the API name matches the class name.
     */
    fun <T : Any> getAllAsync(api: Class<T>): Flow<Set<T>> {
        return getAllAsync(api.name, api)
    }

    /**
     * Asynchronously gets all implementation instances for a given API name and class.
     * Use this if the API was registered with a custom name.
     */
    fun <T : Any> getAllAsync(apiName: String, apiClass: Class<T>): Flow<Set<T>> {
        return getAllNamesAsync(apiName).map { names ->
            names.mapNotNull { it.getInstance(apiClass) }.toSet()
        }
    }

    /**
     * Subscribes to new implementation instances of an API class.
     * Use this when the API name matches the class name.
     */
    fun <T : Any> subscribe(api: Class<T>): Flow<Set<T>> {
        return subscribe(api.name, api)
    }

    /**
     * Subscribes to new implementation instances of a given API name and class.
     * Use this if the API was registered with a custom name.
     */
    fun <T : Any> subscribe(apiName: String, apiClass: Class<T>): Flow<Set<T>> {
        return subscribeNames(apiName).map { names ->
            names.mapNotNull { it.getInstance(apiClass) }.toSet()
        }
    }

    /**
     * Helper to instantiate a class by its name and cache it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> String.getInstance(apiClass: Class<T>): T? {
        return try {
            val instance = instances.getOrPut(this) {
                Class.forName(this).getDeclaredConstructor().newInstance()
            }
            if (apiClass.isInstance(instance)) instance as T else null
        } catch (e: Exception) {
            null
        }
    }
}
