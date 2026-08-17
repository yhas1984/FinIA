plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.gastos.feature.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        fun buildConfigString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        buildConfigField(
            "String",
            "BILLING_BACKEND_URL",
            buildConfigString(providers.environmentVariable("FINAI_BILLING_BACKEND_URL").orElse("").get())
        )
        buildConfigField("String", "BILLING_PACKAGE_NAME", buildConfigString("com.gastos.ingresos"))
        buildConfigField(
            "String",
            "BILLING_ENTITLEMENT_PUBLIC_KEY_PEM",
            buildConfigString(providers.environmentVariable("FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM").orElse("").get())
        )
        buildConfigField(
            "String",
            "BILLING_ENTITLEMENT_ISSUER",
            buildConfigString(providers.environmentVariable("FINAI_BILLING_ENTITLEMENT_ISSUER").orElse("").get())
        )
        buildConfigField(
            "String",
            "BILLING_ENTITLEMENT_KEY_ID",
            buildConfigString(providers.environmentVariable("FINAI_BILLING_ENTITLEMENT_KEY_ID").orElse("").get())
        )
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "BILLING_BACKEND_REQUIRED", "false")
            buildConfigField("Boolean", "BILLING_PLAY_INTEGRITY_ENABLED", "false")
        }
        release {
            buildConfigField(
                "Boolean",
                "BILLING_BACKEND_REQUIRED",
                providers.environmentVariable("FINAI_BILLING_BACKEND_REQUIRED").orElse("true").get().toBoolean().toString()
            )
            buildConfigField(
                "Boolean",
                "BILLING_PLAY_INTEGRITY_ENABLED",
                providers.environmentVariable("FINAI_BILLING_PLAY_INTEGRITY_ENABLED").orElse("false").get().toBoolean().toString()
            )
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
}

// Guard: evita publicar un release "fail-closed" por olvidar las env vars del
// backend de verificación. Se evalúa solo si se pide un release de la app.
val releaseRequested = gradle.startParameter.taskNames.any { name ->
    name == "bundleRelease" || name == "assembleRelease" ||
        name.endsWith(":bundleRelease") || name.endsWith(":assembleRelease")
}
if (releaseRequested) {
    val required = providers.environmentVariable("FINAI_BILLING_BACKEND_REQUIRED").orElse("true").get().toBooleanStrictOrNull() ?: true
    if (required) {
        val url = providers.environmentVariable("FINAI_BILLING_BACKEND_URL").orElse("").get()
        val issuer = providers.environmentVariable("FINAI_BILLING_ENTITLEMENT_ISSUER").orElse("").get()
        val keyId = providers.environmentVariable("FINAI_BILLING_ENTITLEMENT_KEY_ID").orElse("").get()
        val publicKey = providers.environmentVariable("FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM").orElse("").get()
        val missing = buildList {
            if (url.isBlank()) add("FINAI_BILLING_BACKEND_URL")
            if (issuer.isBlank()) add("FINAI_BILLING_ENTITLEMENT_ISSUER")
            if (keyId.isBlank()) add("FINAI_BILLING_ENTITLEMENT_KEY_ID")
            if (publicKey.isBlank()) add("FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release con BILLING_BACKEND_REQUIRED=true exige configurar: ${missing.joinToString(", ")}. " +
                    "Sino, usa FINAI_BILLING_BACKEND_REQUIRED=false para el fallback local."
            )
        }
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security (EncryptedSharedPreferences)
    implementation(libs.androidx.security.crypto)

    // Billing (Google Play)
    implementation(libs.billing)
    implementation("com.google.android.play:integrity:1.6.0")
    implementation(libs.okhttp)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Project modules
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:common"))
    implementation(project(":feature:ai"))

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
