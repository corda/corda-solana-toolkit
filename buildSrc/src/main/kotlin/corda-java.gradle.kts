plugins {
    id("default-java")
}

// Only the main classes need to be Java 17 compatible. The tests can use the default JDK.
tasks.compileJava {
    options.release = 17
}
