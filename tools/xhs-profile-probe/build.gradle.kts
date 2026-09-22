plugins {
    application
}

application {
    mainClass.set("probe.ProfileProbe")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}
