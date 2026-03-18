plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    application
}

group = "com.algotrader"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // HTTP & WebSocket (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Technical indicators (ta4j)
    implementation("org.ta4j:ta4j-core:0.15")

    // PostgreSQL JDBC
    implementation("org.postgresql:postgresql:42.7.3")

    // Testing
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

application {
    mainClass.set("com.algotrader.AlpacaApplicationKt")
    applicationDefaultJvmArgs = listOf(
        "-DALPACA_KEY_ID=${envOrProp("ALPACA_KEY_ID")}",
        "-DALPACA_SECRET_KEY=${envOrProp("ALPACA_SECRET_KEY")}",
        "-DFINNHUB_API_KEY=${envOrProp("FINNHUB_API_KEY")}",
        "-DTELEGRAM_BOT_TOKEN=${envOrProp("TELEGRAM_BOT_TOKEN")}",
        "-DTELEGRAM_CHAT_ID=${envOrProp("TELEGRAM_CHAT_ID")}",
        "-DDATABASE_URL=${envOrProp("DATABASE_URL")}"
    )
}

kotlin {
    jvmToolchain(17)
}

fun envOrProp(key: String) =
    System.getenv(key) ?: (project.findProperty(key) as? String) ?: ""

tasks.test {
    useJUnitPlatform()
    environment("ALPACA_KEY_ID",        envOrProp("ALPACA_KEY_ID"))
    environment("ALPACA_SECRET_KEY",    envOrProp("ALPACA_SECRET_KEY"))
    environment("FINNHUB_API_KEY",      envOrProp("FINNHUB_API_KEY"))
    environment("TELEGRAM_BOT_TOKEN",   envOrProp("TELEGRAM_BOT_TOKEN"))
    environment("TELEGRAM_CHAT_ID",     envOrProp("TELEGRAM_CHAT_ID"))
    environment("DATABASE_URL",         envOrProp("DATABASE_URL"))
}

tasks.named<JavaExec>("run") {
    systemProperty("ALPACA_KEY_ID",        envOrProp("ALPACA_KEY_ID"))
    systemProperty("ALPACA_SECRET_KEY",    envOrProp("ALPACA_SECRET_KEY"))
    systemProperty("FINNHUB_API_KEY",      envOrProp("FINNHUB_API_KEY"))
    systemProperty("TELEGRAM_BOT_TOKEN",   envOrProp("TELEGRAM_BOT_TOKEN"))
    systemProperty("TELEGRAM_CHAT_ID",     envOrProp("TELEGRAM_CHAT_ID"))
    systemProperty("DATABASE_URL",         envOrProp("DATABASE_URL"))
}
