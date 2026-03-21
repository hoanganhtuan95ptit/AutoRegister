package com.simple.auto.register

object AutoRegisterManager {

    private val services = mutableMapOf<String, String>()

    fun register(api: String, impl: String) {
        services[api] = impl
    }

    fun get(api: String): String? {
        return services[api]
    }
}