plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.serialization"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "ru.focusguard.android"; compileSdk = 36; buildToolsVersion = "36.0.0"
    defaultConfig { applicationId = "ru.focusguard.android"; minSdk = 31; targetSdk = 36; versionCode = 2; versionName = "0.2.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    flavorDimensions += "environment"
    productFlavors {
        create("lab") { dimension = "environment"; applicationIdSuffix = ".lab"; buildConfigField("boolean", "LAB_DEVICE", "true") }
        create("production") { dimension = "environment"; buildConfigField("boolean", "LAB_DEVICE", "false") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21; isCoreLibraryDesugaringEnabled = true }
    kotlinOptions { jvmTarget = "21" }
    buildTypes { release { isMinifyEnabled = false } }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
