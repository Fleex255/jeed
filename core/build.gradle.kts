import java.io.File
import java.io.StringWriter
import java.util.Properties
import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm")
    antlr
    java
    `maven-publish`
    id("org.jmailen.kotlinter")
    id("io.gitlab.arturbosch.detekt")
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.0")

    antlr("org.antlr:antlr4:4.13.0")

    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.8.22")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
    implementation("com.puppycrawl.tools:checkstyle:10.12.0")
    implementation("com.pinterest.ktlint:ktlint-core:0.47.1")
    implementation("com.pinterest.ktlint:ktlint-ruleset-standard:0.47.1")
    implementation("com.github.jknack:handlebars:4.3.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("org.ow2.asm:asm:9.5")
    implementation("org.ow2.asm:asm-tree:9.5")
    implementation("org.ow2.asm:asm-util:9.5")
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("ch.qos.logback:logback-classic:1.4.7")
    implementation("io.github.microutils:kotlin-logging:3.0.5")
    implementation("io.github.classgraph:classgraph:4.8.160")
    implementation("net.java.dev.jna:jna:5.13.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("com.google.googlejavaformat:google-java-format:1.17.0")
    implementation("com.google.guava:guava:32.0.0-jre")
    implementation("net.sf.extjwnl:extjwnl:2.0.5")
    implementation("net.sf.extjwnl:extjwnl-data-wn31:1.2")
    implementation("com.beyondgrader.resource-agent:agent:2023.4.0")

    api("org.jacoco:org.jacoco.core:0.8.10")
    api("com.github.ben-manes.caffeine:caffeine:3.1.6")

    testImplementation("io.kotest:kotest-runner-junit5:5.6.2")
}
tasks.test {
    useJUnitPlatform()
    if (JavaVersion.current() >= JavaVersion.VERSION_15) {
        jvmArgs("-ea", "-Xmx4G", "-Xss256k", "--enable-preview", "-XX:+UseZGC")
    } else if (JavaVersion.current() >= JavaVersion.VERSION_11) {
        jvmArgs("-ea", "-Xmx4G", "-Xss256k", "--enable-preview")
    } else {
        jvmArgs("-ea", "-Xmx4G", "-Xss256k")
    }
    systemProperties["logback.configurationFile"] = File(projectDir, "src/test/resources/logback-test.xml").absolutePath
    @Suppress("MagicNumber")
    environment["JEED_MAX_THREAD_POOL_SIZE"] = 4
    if (!OperatingSystem.current().isWindows) {
        environment["PATH"] = "${environment["PATH"]}:/usr/local/bin/"
        environment["JEED_CONTAINER_TMP_DIR"] = "/tmp/"
    }

    if (!project.hasProperty("slowTests")) {
        exclude("**/TestResourceExhaustion.class")
        exclude("**/TestParallelism.class")
        exclude("**/TestContainer.class")
    }
}
tasks.generateGrammarSource {
    outputDirectory = File(projectDir, "src/main/java/edu/illinois/cs/cs125/jeed/core/antlr")
    arguments.addAll(
        listOf(
            "-visitor",
            "-package", "edu.illinois.cs.cs125.jeed.core.antlr",
            "-Xexact-output-dir",
            "-lib", "src/main/antlr/edu/illinois/cs/cs125/jeed/antlr/lib/"
        )
    )
}
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource, "createProperties")
}
tasks.compileTestKotlin {
    dependsOn(tasks.generateTestGrammarSource)
}
afterEvaluate {
    tasks.named("kspKotlin") {
        dependsOn(tasks.generateGrammarSource)
    }
    tasks.named("kspTestKotlin") {
        dependsOn(tasks.generateTestGrammarSource)
    }
    tasks.named("formatKotlinGeneratedByKspKotlin") {
        enabled = false
    }
    tasks.named("lintKotlinGeneratedByKspKotlin") {
        enabled = false
    }
    tasks.named("formatKotlinTest") {
        dependsOn(tasks.generateTestGrammarSource)
    }
    tasks.named("lintKotlinTest") {
        dependsOn(tasks.generateTestGrammarSource)
    }
}
task("createProperties") {
    dependsOn(tasks.processResources)
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.jeed.core.version")
            .printWriter().use { printWriter ->
                printWriter.print(
                    StringWriter().also { properties.store(it, null) }.buffer.toString()
                        .lines().drop(1).joinToString(separator = "\n").trim()
                )
            }
    }
}
tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }
    artifacts {
        add("archives", sourcesJar)
    }
}
tasks.detekt {
    dependsOn(tasks.generateGrammarSource)
}

tasks.lintKotlinMain {
    dependsOn(tasks.generateGrammarSource)
}
tasks.formatKotlinMain {
    dependsOn(tasks.generateGrammarSource)
}
publishing {
    publications {
        create<MavenPublication>("core") {
            from(components["java"])
        }
    }
}
kotlin {
    kotlinDaemonJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
/*
kotlinter {
    disabledRules = arrayOf("filename", "enum-entry-name-case")
}
 */