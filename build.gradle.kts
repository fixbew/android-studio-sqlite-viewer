plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        local("C:/Program Files/Android/Android Studio")
    }
    
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")
}

kotlin {
    jvmToolchain(17)
}