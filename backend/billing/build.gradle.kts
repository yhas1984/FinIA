plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
}

group = "com.gastos.billing"
version = "1.0.0"

application {
    mainClass.set("com.gastos.billing.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val ktorVersion = "3.5.2"

    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.cloud:google-cloud-firestore:3.43.1")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.49.0")
    implementation("com.google.api-client:google-api-client:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
