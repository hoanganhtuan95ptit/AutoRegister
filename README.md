# AutoRegister

AutoRegister is a lightweight, KSP-based library for automatic service registration in Android projects. It simplifies dependency management by allowing you to register implementations via annotations, supporting standard libraries, application modules, and dynamic features.

## Features

- **Zero Configuration**: Automatically initializes itself using Android Startup. No manual initialization needed in your `Application` class.
- **Dynamic Feature Support**: Automatically discovers and registers services when a new dynamic feature is installed.
- **Automatic Service Registration**: No more manual `register()` calls.
- **Support for All Module Types**: Smart detection for App, Library, and Dynamic Features.
- **KSP Powered**: High performance at compile-time with no reflection overhead at runtime.
- **Instance Caching**: Built-in singleton-like behavior for service instances.
- **Reactive API**: Full support for Kotlin Coroutines Flow for both class names and instances.

## Installation

### 1. Add JitPack repository
Add it in your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 2. Add dependencies
In your module `build.gradle`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.21-1.0.25" // Match your Kotlin version
}

dependencies {
    // Core library (Required)
    implementation("com.github.hoanganhtuan95ptit.AutoRegister:auto-register:1.1.0")
    ksp("com.github.hoanganhtuan95ptit.AutoRegister:auto-register-processor:1.1.0")

    // For Dynamic Feature support (Optional)
    implementation("com.github.hoanganhtuan95ptit.AutoRegister:auto-register-dynamic-feature:1.1.0")
}
```

## Usage

### 1. Define and Annotate

```kotlin
interface MyService

@AutoRegister(apis = [MyService::class])
class MyServiceImpl : MyService
```

### 2. Accessing Services

`AutoRegisterManager` provides two types of APIs: **Instance-based** (returns objects) and **String-based** (returns class names). All services are automatically discovered and registered at app startup.

#### Instance-based API (Recommended)
Automatically instantiates and caches your services.

```kotlin
// 1. Synchronous - Get all current instances
val instances: Set<MyService> = AutoRegisterManager.getAll(MyService::class.java)

// 2. Asynchronous - Get full set and updates
AutoRegisterManager.getAllAsync(MyService::class.java).collect { allInstances ->
    // Emits the full Set whenever a new implementation is registered (e.g., after a Dynamic Feature is installed)
}

// 3. Subscription - Get only new instances
AutoRegisterManager.subscribe(MyService::class.java).collect { newInstances ->
    // Emits only the newly discovered instances
}
```

### 3. Dynamic Feature Support

If your project uses Dynamic Features, simply include the `auto-register-dynamic-feature` dependency in your app module. It uses Android Startup to automatically listen for `SplitInstallSessionStatus.INSTALLED` events and triggers module discovery for the new feature.

No additional code is required! Once a feature is installed, its `@AutoRegister` annotated classes will be immediately available in `AutoRegisterManager`.

## Advanced Options

Force a module type or name via KSP arguments in `build.gradle`:

```kotlin
ksp {
    arg("moduleName", "MyCustomModule")
    arg("moduleType", "app") // Options: "app", "library", "dynamic"
}
```

## Sample Project
Check the `samples/` directory for a complete working example:
- `:samples:feature_library`: Standard Android library with auto-registration.
- `:samples:feature_dynamic`: Dynamic feature module integration.

## Github Link
[https://github.com/hoanganhtuan95ptit/AutoRegister](https://github.com/hoanganhtuan95ptit/AutoRegister)

## License
```
Copyright 2024 Hoang Anh Tuan
```
