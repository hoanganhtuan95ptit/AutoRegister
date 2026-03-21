# AutoRegister

AutoRegister is a lightweight, KSP-based library for automatic service registration in Android projects. It simplifies dependency management by allowing you to register implementations via annotations, supporting standard libraries, application modules, and dynamic features.

## Features

- **Automatic Service Registration**: No more manual `register()` calls.
- **Support for All Module Types**: Smart detection for App, Library, and Dynamic Features.
- **KSP Powered**: High performance at compile-time with no reflection overhead at runtime.
- **SPI Support**: Automatic `META-INF/services` generation for library modules.

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

Define your interface and annotate the implementation using `@AutoRegister`:

```kotlin
interface MyService

@AutoRegister(apis = [MyService::class])
class MyServiceImpl : MyService
```

### 2. Generated Code

Based on the module type, the library automatically generates a loader class:
- **Application Module**: `[ModuleName]Loader.kt`
- **Library Module**: `[ModuleName]LibraryLoader.kt`
- **Dynamic Feature**: `[ModuleName]DynamicFeatureLoader.kt`

### 3. Initialize at Runtime

To trigger the registration, you need to call the `create()` method of the generated loaders.

```kotlin
// Example: Manual initialization in Application class
AppLoader().create()

// Now you can get the implementation class name
val implClassName = AutoRegisterManager.get(MyService::class.java.name)
```

## Advanced Options

You can force a module type or name via KSP arguments in `build.gradle`:

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
