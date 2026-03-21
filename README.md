# AutoRegister

AutoRegister is a lightweight, KSP-based library for automatic service registration in Android projects. It simplifies dependency management by allowing you to register implementations via annotations, supporting standard libraries, application modules, and dynamic features.

## Features

- **Zero Configuration**: Automatically initializes itself using Android Startup. No manual initialization needed in your `Application` class.
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
    // Replace [TAG] with the latest release version or commit hash (e.g., 1.0.0)
    implementation("com.github.hoanganhtuan95ptit.AutoRegister:auto-register:[TAG]")
    ksp("com.github.hoanganhtuan95ptit.AutoRegister:auto-register-processor:[TAG]")
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
    // Emits the full Set whenever a new implementation is registered (e.g., from dynamic features)
}

// 3. Subscription - Get only new instances
AutoRegisterManager.subscribe(MyService::class.java).collect { newInstances ->
    // Emits only the newly discovered instances
}
```

#### String-based API
Useful if you want to handle instantiation manually or need custom API names.

```kotlin
// Using Class name
val names: Set<String> = AutoRegisterManager.getAllNames(MyService::class.java.name)

// Using custom API name
val customNames = AutoRegisterManager.getAllNames("my_custom_api_id")
```

## Advanced Options

Force a module type or name via KSP arguments in `build.gradle`:

```kotlin
ksp {
    arg("moduleName", "MyCustomModule")
    arg("moduleType", "app") // Options: "app", "library", "dynamic"
}
```

## Github Link
[https://github.com/hoanganhtuan95ptit/AutoRegister](https://github.com/hoanganhtuan95ptit/AutoRegister)

## License
```
Copyright 2024 Hoang Anh Tuan
```
