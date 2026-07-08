import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.gastos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gastos.ingresos"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreProps = Properties()
            val f = rootProject.file("local.properties")
            if (f.exists()) {
                f.inputStream().use { stream -> keystoreProps.load(stream) }
            }
            val ksFile = keystoreProps.getProperty("FINAI_KEYSTORE_FILE")?.let(::file)
            if (ksFile != null) {
                storeFile = ksFile
                storePassword = keystoreProps.getProperty("FINAI_KEYSTORE_PASSWORD") ?: ""
                keyAlias = keystoreProps.getProperty("FINAI_KEY_ALIAS") ?: ""
                keyPassword = keystoreProps.getProperty("FINAI_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES"
            )
        }
    }

    // splits.abi eliminado: estaba con isEnable = false y solo producía un
    // único APK universal. El bloque `splits` se reintroducirá cuando se
    // quiera dividir el APK por arquitectura (e.g. publicar APKs separados
    // en Play Store para reducir peso).
}

dependencies {
    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose.lib)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Coil (images)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Google AI (Gemini API - cloud)
    implementation(libs.google.generative.ai)

    // DataStore
    implementation(libs.datastore.preferences)

    // Serialization
    implementation(libs.serialization.json)

    // Billing
    implementation(libs.billing)

    // Google Sign In (Sheets export)
    implementation(libs.play.services.auth)

    // Core modules
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:common"))

    // Feature modules
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:invoices"))
    implementation(project(":feature:incomes"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:ai"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:backup"))
    implementation(project(":feature:chatbot"))

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
