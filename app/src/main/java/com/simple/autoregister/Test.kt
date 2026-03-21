package com.simple.autoregister

import com.simple.auto.register.AutoRegister

interface Test

@AutoRegister([Test::class])
class TestImpl : Test