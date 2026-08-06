plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.screenshot) apply false
    alias(libs.plugins.kotlin.atomicfu) apply false
    alias(libs.plugins.baselineprofile) apply false
}

subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
