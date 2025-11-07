plugins {
    kotlin("jvm") version "2.2.0"
}

group = "com.dysaster"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.ow2.asm/asm-all
    implementation("org.ow2.asm:asm-all:5.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}