plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.gastos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gastos.ingresos"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Leemos las credenciales SOLO desde variables de entorno del
            // proceso de build (o desde gradle.properties del home del
            // usuario). NUNCA hardcoded en el repositorio.
            val storeFilePath: String? = System.getenv("FINAI_KEYSTORE_FILE")
                ?: project.findProperty("finai.keystore.file") as String?
            storeFile = storeFilePath?.let { file(it) } ?: file("../finai-release.keystore")
            storePassword = System.getenv("FINAI_KEYSTORE_PASSWORD")
                ?: (project.findProperty("finai.keystore.password") as String? ?: "")
            keyAlias = System.getenv("FINAI_KEY_ALIAS")
                ?: (project.findProperty("finai.key.alias") as String? ?: "finai")
            keyPassword = System.getenv("FINAI_KEY_PASSWORD")
                ?: (project.findProperty("finai.key.password") as String? ?: "")
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

    splits {
        abi {
            isEnable = false
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
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
