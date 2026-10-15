plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":mapengine"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
}

application {
    mainClass.set("app.minemaps.tools.render.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
