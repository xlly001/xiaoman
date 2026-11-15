plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("dev.xiaoman.eval.MainKt")
    applicationName = "xiaoman-eval"
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":core-agent"))
    implementation(project(":core-sentinel"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
