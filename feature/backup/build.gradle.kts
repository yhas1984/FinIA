plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.gastos.feature.backup"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose.lib)

    // Core
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)

    // Google Sign In (Drive backup)
    implementation(libs.play.services.auth)

    // Google Sheets API (exportación a Sheets)
    implementation(libs.google.api.client.android)
    implementation(libs.google.oauth.client.jetty)
    implementation(libs.google.api.services.sheets)

    // Project modules
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:common"))
}
