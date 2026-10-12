plugins {
    kotlin("jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    compileOnly("com.pinterest.ktlint:ktlint-rule-engine-core:1.8.0")
    compileOnly("com.pinterest.ktlint:ktlint-cli-ruleset-core:1.8.0")
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("com.pinterest.ktlint:ktlint-rule-engine:1.8.0")
    testImplementation("com.pinterest.ktlint:ktlint-ruleset-standard:1.8.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-nop")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable") {
        version { strictly("2.2.21") }
    }
    add("ktlint", "org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.21")
    add("ktlint", files(tasks.named("jar")))
}

ktlint {
    version.set("1.8.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
