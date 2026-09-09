import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

val browserTestSourceSet = sourceSets.create("browserTest") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[browserTestSourceSet.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get(),
)
configurations[browserTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get(),
)

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    applicationName = "transaction-import-web"
    mainClass.set("io.github.stolex1y.transactionimport.web.MainKt")
}

distributions {
    main {
        contents {
            from(rootProject.file("config")) {
                into("config")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":transport"))
    implementation(project(":persistence"))
    implementation(project(":d05"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

dependencies {
    add(browserTestSourceSet.implementationConfigurationName, libs.playwright)
}

tasks.test {
    useJUnitPlatform()
}

tasks.named("compileBrowserTestKotlin") {
    dependsOn(tasks.testClasses)
}

tasks.register<Test>("browserTest") {
    group = "verification"
    description = "Runs the Playwright browser end-to-end tests."
    testClassesDirs = browserTestSourceSet.output.classesDirs
    classpath = browserTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}

tasks.register<JavaExec>("playwrightInstall") {
    group = "verification"
    description = "Installs the Chromium build managed by Playwright."
    dependsOn(browserTestSourceSet.classesTaskName)
    classpath = browserTestSourceSet.runtimeClasspath
    mainClass.set("com.microsoft.playwright.CLI")
    args("install", "chromium")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
