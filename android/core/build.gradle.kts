plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(21) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform(); systemProperty("repoRoot", rootDir.parentFile.absolutePath) }
