import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    id("com.gradleup.shadow") version "9.2.2"
}

group = "dev.etran"
version = "1.0.0"

val spigotApiVersion: String by project

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // The only compile dependency: the OLDEST supported Spigot API. BedWars is reached by reflection
    // (see BedwarsHook.kt), so no BedWars jar or repository is needed to build.
    // bungeecord-chat 1.8-SNAPSHOT (a transitive dependency of the 1.8.8 API) no longer exists in the
    // Spigot snapshot repository. The plugin does not use chat components, so it is excluded.
    compileOnly("org.spigotmc:spigot-api:$spigotApiVersion") {
        exclude(group = "net.md-5", module = "bungeecord-chat")
    }
    implementation(kotlin("stdlib"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("BedWars1058-DragonFix-${project.version}.jar")
        // Servers on 1.8 cannot load libraries from plugin.yml, so Kotlin is shaded and relocated.
        relocate("kotlin", "dev.etran.libs.kotlin")
        relocate("org.jetbrains", "dev.etran.libs.jetbrains")
        relocate("org.intellij", "dev.etran.libs.intellij")
    }

    build { dependsOn(shadowJar) }
    jar { enabled = false }
}
