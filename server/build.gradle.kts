import java.io.File
import java.io.StringWriter
import java.util.Properties

plugins {
    kotlin("jvm")
    application
    `maven-publish`
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("org.jmailen.kotlinter")
    id("io.gitlab.arturbosch.detekt")
    id("com.google.devtools.ksp")
    id("com.ryandens.javaagent-test") version "0.5.0"
}
dependencies {
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.14.0")

    testJavaagent("com.beyondgrader.resource-agent:agent:2022.9.3")

    implementation(project(":core"))

    implementation("io.ktor:ktor-server-netty:2.2.4")
    implementation("io.ktor:ktor-server-cors:2.2.4")
    implementation("io.ktor:ktor-server-content-negotiation:2.2.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("com.squareup.moshi:moshi-kotlin:1.14.0")
    implementation("com.github.cs125-illinois:ktor-moshi:2022.9.0")
    implementation("ch.qos.logback:logback-classic:1.4.6")
    implementation("com.uchuhimo:konf-core:1.1.2")
    implementation("com.uchuhimo:konf-yaml:1.1.2")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    implementation("com.github.cs124-illinois:libcs1:2023.2.0")
    implementation("com.beyondgrader.resource-agent:agent:2022.9.3")
    implementation("com.beyondgrader.resource-agent:jeedplugin:2022.9.3")

    testImplementation("io.kotest:kotest-runner-junit5:5.6.0")
    testImplementation("io.kotest:kotest-assertions-ktor:4.4.3")
    testImplementation("io.ktor:ktor-server-test-host:2.2.4")
}

application {
    mainClass.set("edu.illinois.cs.cs125.jeed.server.MainKt")
}

tasks.processResources {
    dependsOn("createProperties")
}

val dockerName = "cs124/jeed"
tasks.register<Copy>("dockerCopyJar") {
    from(tasks["shadowJar"].outputs)
    into("${buildDir}/docker")
}
tasks.register<Copy>("dockerCopyDockerfile") {
    from("${projectDir}/Dockerfile")
    into("${buildDir}/docker")
}
tasks.register<Exec>("dockerBuild") {
    dependsOn("dockerCopyJar", "dockerCopyDockerfile")
    workingDir("${buildDir}/docker")
    environment("DOCKER_BUILDKIT", "1")
    commandLine(
        ("docker build . " +
            "-t ${dockerName}:latest " +
            "-t ${dockerName}:${project.version}").split(" ")
    )
}
tasks.register<Exec>("dockerPush") {
    dependsOn("dockerCopyJar", "dockerCopyDockerfile")
    workingDir("${buildDir}/docker")
    commandLine(
        ("docker buildx build . --platform=linux/amd64,linux/arm64/v8 " +
            "--builder multiplatform " +
            "--tag ${dockerName}:latest " +
            "--tag ${dockerName}:${project.version} --push").split(" ")
    )
}
tasks.test {
    useJUnitPlatform()
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
    environment["JEED_USE_CACHE"] = "true"
}

task("createProperties") {
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.jeed.server.version")
            .printWriter().use { printWriter ->
                printWriter.print(
                    StringWriter().also { properties.store(it, null) }.buffer.toString()
                        .lines().drop(1).joinToString(separator = "\n").trim()
                )
            }
    }
}

tasks.processResources {
    dependsOn("createProperties")
}

publishing {
    publications {
        create<MavenPublication>("server") {
            from(components["java"])
        }
    }
}
kotlin {
    kotlinDaemonJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

tasks.shadowJar {
    manifest {
        attributes["Launcher-Agent-Class"] = "com.beyondgrader.resourceagent.AgentKt"
        attributes["Can-Redefine-Classes"] = "true"
        attributes["Can-Retransform-Classes"] = "true"
    }
}
/*
kotlinter {
    disabledRules = arrayOf("filename", "enum-entry-name-case")
}
 */
afterEvaluate {
    tasks.named("formatKotlinGeneratedByKspKotlin") {
        enabled = false
    }
    tasks.named("lintKotlinGeneratedByKspKotlin") {
        enabled = false
    }
}