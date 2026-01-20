plugins {
    id("java")
    id("application")
    id("org.jfxcore.fxmlplugin")
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(17)
}

application {
    mainClass.set("org.example.App")
}

javafx {
    modules("javafx.controls")
}
