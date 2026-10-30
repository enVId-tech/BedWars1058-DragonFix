import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "9.0.0"
}

group = "dev.etran"
version = "1.3.1"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    // BedWars1058 is intentionally NOT a compile-time dependency: its API artifact is not published
    // anywhere reachable (the old repo.andrei1058.dev domain now redirects to an unrelated site and
    // the new repo.andrei1058.com/releases is empty). See BedWarsBridge for the reflection hook.
}

dependencies {
    // Lowest common denominator API: the plugin is written against 1.8.8 and uses
    // version-independent calls (or PacketEvents) for everything newer.
    compileOnly("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { exclude(group = "net.md-5") }
    // PacketEvents is installed on the server as a plugin (required dependency).
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.1")

    implementation(kotlin("stdlib"))
    implementation("com.zaxxer:HikariCP:4.0.3")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.1")

    testImplementation(kotlin("test"))
    testImplementation("org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT") { exclude(group = "net.md-5") }
}

kotlin {
    // Java 8 bytecode so the same jar loads on 1.8 servers (Java 8) up to the newest (Java 21+).
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjdk-release=8")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.release.set(8)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    relocate("kotlin", "dev.etran.bedreplay.libs.kotlin")
    relocate("kotlinx", "dev.etran.bedreplay.libs.kotlinx")
    relocate("org.jetbrains", "dev.etran.bedreplay.libs.jetbrains")
    relocate("org.intellij", "dev.etran.bedreplay.libs.intellij")
    relocate("com.zaxxer.hikari", "dev.etran.bedreplay.libs.hikari")
    mergeServiceFiles()
    minimize {
        exclude(dependency("org.mariadb.jdbc:.*"))
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
