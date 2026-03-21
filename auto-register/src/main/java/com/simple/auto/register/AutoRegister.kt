package com.simple.auto.register

import kotlin.reflect.KClass

/**
 * Annotation to mark a class for automatic registration.
 *
 * @param apis The list of interface or abstract class types that this class implements
 *             and should be registered under.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AutoRegister(val apis: Array<KClass<*>>)
