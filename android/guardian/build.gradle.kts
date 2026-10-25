plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "ru.focusguard.guardian"; compileSdk = 36; buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "ru.focusguard.guardian"; minSdk = 30; targetSdk = 36; versionCode = 5; versionName = "0.3.0" }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "21" }
}
dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.17.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
