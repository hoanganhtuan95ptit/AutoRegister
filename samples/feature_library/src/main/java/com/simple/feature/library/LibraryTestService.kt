package com.simple.feature.library

import com.simple.auto.register.AutoRegister

@AutoRegister(apis = [TestService::class])
class LibraryTestService : TestService {
    override fun getMessage(): String = "Hello from Library!"
}
