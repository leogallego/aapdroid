import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.atomicfu)
}

val generateVersionResource by tasks.registering {
    val versionName = providers.gradleProperty("appVersionName").get()
    val outputDir = layout.buildDirectory.dir("generated/resources/jvmMain")
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$versionName\n")
    }
}

kotlin {
    android {
        namespace = "io.github.leogallego.ansiblejane.shared"
        compileSdk = 37
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }

    jvm()

    // iOS targets added in Phase 8 (requires macOS + Kotlin/Native)
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.datetime)

            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.ktor.client.logging)

            implementation(libs.androidx.datastore.preferences)

            implementation(libs.koin.core)

            implementation(libs.koog.openai.client)
            implementation(libs.koog.google.client)
            implementation(libs.koog.http.ktor)
            implementation(libs.mcp.sdk.client)
            implementation(libs.kotlinx.atomicfu)

            implementation(libs.cryptography.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.turbine)
            implementation(libs.ktor.client.mock)
        }

        val jvmTest by getting {
            dependencies {
                implementation(libs.mockwebserver)
                implementation(libs.ktor.client.okhttp)
            }
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.tink.android) // Kept for TinkMigration — remove after one release cycle
            implementation(libs.androidx.work.runtime)
            implementation(libs.cryptography.provider.jdk)
            implementation(libs.litert.lm.android)
        }

        jvmMain {
            resources.srcDir(generateVersionResource.map { it.outputs.files.singleFile })
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.cryptography.provider.jdk)
                implementation(libs.litert.lm.jvm)
            }
        }

        // iosMain.dependencies added in Phase 8
        // iosMain.dependencies {
        //     implementation(libs.ktor.client.darwin)
        // }
    }
}
