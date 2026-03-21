package com.simple.feature.dynamic

import com.simple.auto.register.AutoRegister
import com.simple.feature.library.TestService

@AutoRegister(apis = [TestService::class])
class DynamicTestService : TestService {
    override fun getMessage(): String = "Hello from Dynamic Feature!"
}
