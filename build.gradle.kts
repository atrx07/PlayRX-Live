plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Paparazzi 1.3.5: last 1.x line, matches this project's Gradle 8.7,
    // compileSdk 34, JDK 17, and Compose BOM 2024.10. The 2.0 alphas need
    // JDK 21 / AGP 9 / newer BOM and are NOT used here.
    id("app.cash.paparazzi") version "1.3.5" apply false
}
