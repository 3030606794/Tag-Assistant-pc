import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins { kotlin("jvm"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.compose") }
kotlin {
    jvmToolchain(17)
    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
    }
}
dependencies {
    implementation(project(":engine"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.12.0-alpha03")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    testImplementation("junit:junit:4.13.2")
}
compose.desktop {
    application {
        mainClass = "cn.guangyao.eplan.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8", "-Duser.language=zh", "-Duser.country=CN")
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "EPLAN标签助手"
            packageVersion = "0.1.2"
            description = "EPLAN 标签助手 · Windows 离线版"
            vendor = "光光"
            copyright = "Copyright 2026 Eplan Pocket contributors"
            licenseFile.set(rootProject.file("LICENSE"))
            appResourcesRootDir.set(project.layout.buildDirectory.dir("app-resources"))
            includeAllModules = true
            windows {
                iconFile.set(project.file("src/main/resources/branding/app.ico"))
                menuGroup = "EPLAN 标签助手"
                shortcut = true
                dirChooser = true
                upgradeUuid = "e80107f9-71c7-44f0-b405-650d3d275768"
            }
        }
    }
}
tasks.test {
    maxHeapSize = "2g"
    systemProperty("file.encoding", "UTF-8")
    systemProperty("eplan.resources.dir", layout.buildDirectory.dir("app-resources/common").get().asFile.absolutePath)
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
tasks.register<JavaExec>("cloudPreview") {
    group = "verification"
    description = "Render the actual Compose desktop UI to cloud preview PNGs."
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir
    mainClass.set("cn.guangyao.eplan.MainKt")
    args("--cloud-preview", layout.buildDirectory.dir("previews").get().asFile.absolutePath)
    systemProperty("eplan.resources.dir", layout.buildDirectory.dir("app-resources/common").get().asFile.absolutePath)
    systemProperty("file.encoding", "UTF-8")
    jvmArgs("-Xmx2g")
}
