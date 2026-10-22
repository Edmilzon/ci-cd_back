import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.space"
version = providers.gradleProperty("releaseVersion").orElse("0.0.1-SNAPSHOT").get()

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.security:spring-security-crypto")
	implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.postgresql:postgresql")
	implementation("org.bouncycastle:bcprov-jdk18on:1.86")
	implementation("com.github.mwiede:jsch:0.2.25")
	implementation("org.yaml:snakeyaml:2.6")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
}
