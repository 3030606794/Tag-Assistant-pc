plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
dependencies {
    api("org.apache.pdfbox:pdfbox:2.0.37")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
tasks.test {
    maxHeapSize = "512m"
    systemProperty("java.awt.headless", "true")
    systemProperty("file.encoding", "UTF-8")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
