package com.simple.auto.register

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoRegisterManagerTest {

    interface TestService
    class TestServiceImpl1 : TestService
    class TestServiceImpl2 : TestService

    @Before
    fun setup() {
        // Reset the manager state before each test
        AutoRegisterManager.clear()
    }

    @Test
    fun testRegisterAndGetAll() {
        val api = TestService::class.java
        val impl1 = TestServiceImpl1::class.java.name
        val impl2 = TestServiceImpl2::class.java.name

        AutoRegisterManager.register(api.name, impl1)
        AutoRegisterManager.register(api.name, impl2)

        val allNames = AutoRegisterManager.getAllNames(api.name)
        assertEquals(2, allNames.size)
        assertTrue(allNames.contains(impl1))
        assertTrue(allNames.contains(impl2))

        val allInstances = AutoRegisterManager.getAll(api)
        assertEquals(2, allInstances.size)
        assertTrue(allInstances.any { it is TestServiceImpl1 })
        assertTrue(allInstances.any { it is TestServiceImpl2 })
    }

    @Test
    fun testInstanceCaching() {
        val api = TestService::class.java
        val impl = TestServiceImpl1::class.java.name

        AutoRegisterManager.register(api.name, impl)

        val firstLoad = AutoRegisterManager.getAll(api).first()
        val secondLoad = AutoRegisterManager.getAll(api).first()

        // Verify that instances are cached (referential equality)
        assertTrue(firstLoad === secondLoad)
    }

    @Test
    fun testGetAllAsync() = runTest {
        val api = TestService::class.java
        val impl1 = TestServiceImpl1::class.java.name
        
        AutoRegisterManager.register(api.name, impl1)

        // getAllAsync should emit current state on collection start
        val flow = AutoRegisterManager.getAllAsync(api)
        val firstEmission = flow.first()
        
        assertEquals(1, firstEmission.size)
        assertTrue(firstEmission.any { it is TestServiceImpl1 })
    }

    @Test
    fun testSubscribe() = runTest {
        val api = TestService::class.java
        val impl1 = TestServiceImpl1::class.java.name
        val impl2 = TestServiceImpl2::class.java.name

        // 1. Register first implementation
        AutoRegisterManager.register(api.name, impl1)

        val results = mutableListOf<Set<TestService>>()
        val job = launch {
            AutoRegisterManager.subscribe(api).collect {
                results.add(it)
            }
        }
        
        // Ensure the initial emission from onStart is collected
        advanceUntilIdle()
        assertEquals("Should collect initial registration", 1, results.size)
        assertTrue(results[0].any { it is TestServiceImpl1 })

        // 2. Register second implementation
        AutoRegisterManager.register(api.name, impl2)
        
        // Ensure the registrationFlow emission is collected
        advanceUntilIdle()

        assertEquals("Should collect second registration", 2, results.size)
        assertTrue("Second emission should contain impl2", results[1].any { it is TestServiceImpl2 })
        assertEquals("Second emission should ONLY contain new items", 1, results[1].size)

        job.cancel()
    }
}
