# 🚀 AutoRegister

AutoRegister is a lightweight, KSP-based library for automatic service registration in Android projects. It simplifies dependency management by allowing you to register implementations via annotations, supporting standard libraries, application modules, and dynamic features.

## 🛠 Why AutoRegister? (Comparison with Google AutoService)

While Google's **AutoService** (using standard Java `ServiceLoader`) is great for static modules, it has significant limitations when working with **Android Dynamic Features**:

1.  **🔍 Dynamic Class Loading**: Standard `ServiceLoader` often fails to discover implementations in newly installed dynamic modules because they are loaded by a different ClassLoader at runtime.
2.  **⚡ Lifecycle Awareness**: AutoService has no built-in mechanism to "wake up" and register new services immediately when a Dynamic Feature is installed. Usually, an app restart or complex manual ClassLoader management is required.
3.  **🌊 Reactive Support**: AutoRegister provides native **Kotlin Coroutines Flow** support, allowing your app to reactively receive new service implementations the moment a Dynamic Feature finishes installing—**without restarting the app**.

## ✨ Features

-   **🎯 Zero Configuration**: Automatically initializes itself using Android Startup. No manual initialization needed.
-   **📱 Dynamic Feature First**: A robust alternative to AutoService specifically designed for the modern Android modular architecture.
-   **🤖 Automatic Service Registration**: No more manual `register()` calls or `ServiceLoader` boilerplate.
-   **💾 Instance Caching**: Built-in singleton-like behavior for service instances.
-   **🔄 Reactive API**: Full support for Kotlin Coroutines Flow for both class names and instances.
-   **⚡ High Performance**: KSP-powered code generation with zero reflection overhead at runtime.

## 📦 Installation

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

    // For Dynamic Feature support (Recommended for DF projects)
    implementation("com.github.hoanganhtuan95ptit.AutoRegister:auto-register-dynamic-feature:1.1.0")
}
```

## 🚀 Usage

### 1. Define and Annotate

```kotlin
interface MyService

@AutoRegister(apis = [MyService::class])
class MyServiceImpl : MyService
```

### 2. Accessing Services

#### Instance-based API (Recommended)
Automatically instantiates and caches your services.

```kotlin
// 1. Synchronous - Get all current instances
val instances: Set<MyService> = AutoRegisterManager.getAll(MyService::class.java)

// 2. Asynchronous - Get full set and updates (Perfect for Dynamic Features!)
AutoRegisterManager.getAllAsync(MyService::class.java).collect { allInstances ->
    // This emits the full Set whenever a new implementation is registered 
    // (e.g., immediately after a Dynamic Feature is installed).
}
```

### 3. 🧩 Dynamic Feature Support

If your project uses Dynamic Features, include the `auto-register-dynamic-feature` dependency. It uses Android Startup to:
-   **🔄 Pre-load**: Automatically scans and loads all `@AutoRegister` implementations from already installed dynamic modules on app startup.
-   **🎧 Listen**: Automatically listens for new module installations (`SplitInstallSessionStatus.INSTALLED`) and triggers discovery immediately.

**No additional code is required!** Your app stays reactive and modular.

## ⚙️ Advanced Options

Force a module type or name via KSP arguments in `build.gradle`:

```kotlin
ksp {
    arg("moduleName", "MyCustomModule")
    arg("moduleType", "app") // Options: "app", "library", "dynamic"
}
```

## 🔗 Github Link
[https://github.com/hoanganhtuan95ptit/AutoRegister](https://github.com/hoanganhtuan95ptit/AutoRegister)

## 📄 License
```
Copyright 2024 Hoang Anh Tuan
```
