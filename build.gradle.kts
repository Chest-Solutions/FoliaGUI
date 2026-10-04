plugins {
    id("java")
    id("java-library")
    id("maven-publish")
}

group = "dev.csl"
version = "1.0.0"
description = "Packet-driven inventory GUI library built directly on PacketEvents"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
}

dependencies {
    // Compile-only: the consuming plugin/server supplies these at runtime.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    javadoc {
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
