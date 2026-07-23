plugins {

    id("java-library")
    id("org.jetbrains.kotlin.jvm")
    id("maven-publish")
}

java {

    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {

    compilerOptions {

        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {

    // KSP API
    implementation(libs.symbol.processing.api)

    // KotlinPoet
    implementation("com.squareup:kotlinpoet:1.15.3")

    // KotlinPoet KSP extension (QUAN TRỌNG - cho toClassName)
    implementation("com.squareup:kotlinpoet-ksp:1.15.3")
}

publishing {

    publications {

        create<MavenPublication>("maven") {

            from(components["java"])
            groupId = project.group.toString()
            artifactId = "auto-register-processor"
            version = project.version.toString()
        }
    }
    repositories {

        mavenLocal()
    }
}
