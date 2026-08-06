import org.gradle.api.provider.Property

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
    alias(libs.plugins.baselineprofile)
    jacoco
}

abstract class PrepareRobolectricDeps : DefaultTask() {
    @get:Input abstract val m2Path: Property<String>
    @get:OutputDirectory abstract val destDir: DirectoryProperty

    @TaskAction
    fun execute() {
        val dest = destDir.get().asFile
        dest.mkdirs()
        if (dest.listFiles()?.any { it.extension == "jar" } == true) return
        val m2 = File(m2Path.get())
        if (m2.isDirectory) {
            m2.walkTopDown().filter { it.extension == "jar" }.forEach { jar ->
                jar.copyTo(File(dest, jar.name), overwrite = false)
            }
        }
    }
}

android {
    namespace = "io.github.leogallego.ansiblejane"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.leogallego.ansiblejane"
        minSdk = 31
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            val gitSha = providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
            versionNameSuffix = "-dev+${gitSha.get()}"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null)
                signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                val tmpDir = file("${rootProject.projectDir}/.tmp")
                if (tmpDir.exists()) {
                    it.systemProperty("java.io.tmpdir", tmpDir.absolutePath)
                }
            }
        }
    }
}

val robolectricDepsPath = File(rootProject.projectDir, "robolectric-deps").absolutePath

val prepareRobolectricDeps by tasks.registering(PrepareRobolectricDeps::class) {
    m2Path.set(File(System.getProperty("user.home"), ".m2/repository/org/robolectric/android-all-instrumented").absolutePath)
    destDir.set(file(robolectricDepsPath))
}

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricDeps)
    val roboDepsPath = robolectricDepsPath
    doFirst {
        val dir = File(roboDepsPath)
        if (dir.listFiles()?.any { it.extension == "jar" } == true) {
            systemProperty("robolectric.offline", "true")
            systemProperty("robolectric.dependency.dir", dir.absolutePath)
        }
    }
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    val debugTree = fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug")
    val mainSrc = "${project.projectDir}/src/main/kotlin"
    classDirectories.setFrom(debugTree)
    sourceDirectories.setFrom(files(mainSrc))
    executionData.setFrom(
        fileTree(layout.buildDirectory) { include("jacoco/testDebugUnitTest.exec") }
    )
}

baselineProfile {
    // Don't regenerate on every assemble — use checked-in / CI-generated profiles.
    automaticGenerationDuringBuild = false
    // Startup Profile → DEX layout optimizations (AGP 8.3+ default; keep explicit).
    dexLayoutOptimization = true
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":composeApp"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.datetime)

    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)

    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.serialization.kotlinx.json)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.rules)
    testImplementation(libs.androidx.work.testing)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    implementation("androidx.compose.runtime:runtime-tracing")
    debugImplementation("androidx.tracing:tracing-perfetto:1.0.1")
    debugImplementation("androidx.tracing:tracing-perfetto-binary:1.0.1")

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.androidx.ui.tooling)
}
