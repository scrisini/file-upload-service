plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.crisinis"
version = "0.0.1-SNAPSHOT"
description = "Accepts file uploads over HTTP and stores them for later retrieval."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Parameter Store at startup (configdata/); the JDK's HTTP client instead of Apache or Netty
    implementation(platform("software.amazon.awssdk:bom:2.57.8"))
    implementation("software.amazon.awssdk:ssm")
    // Uploads, scan events and file status (storage/, events/, persistence/)
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sqs")
    implementation("software.amazon.awssdk:dynamodb")
    // Policy extraction with a model on Amazon Bedrock (Converse API)
    implementation("software.amazon.awssdk:bedrockruntime")
    implementation("software.amazon.awssdk:url-connection-client")
    // OpenAPI spec and Swagger UI (not in Spring Boot's BOM; 3.2.0 is built against Boot 4.1.2)
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// One synchronous HTTP client for every AWS SDK module: the JDK's, not Apache or Netty
configurations.configureEach {
    exclude(group = "software.amazon.awssdk", module = "apache-client")
    exclude(group = "software.amazon.awssdk", module = "apache5-client")
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Integration tests against REAL AWS (S3, DynamoDB, Bedrock, GuardDuty): src/integrationTest/java.
// Never part of `test`, `check` or `build`; run them deliberately with `./gradlew integrationTest`
// once an AWS account is configured (README "Testing against real AWS"). Unit tests stay offline.
testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            targets.all {
                testTask.configure {
                    description = "Runs the integration tests against real AWS (needs FILE_UPLOAD_SERVICE_IT_* settings)"
                    shouldRunAfter(tasks.named("test"))
                    // a policy image is drawn in memory for the Bedrock test
                    systemProperty("java.awt.headless", "true")
                    // each run talks to AWS: never reuse an earlier result
                    outputs.upToDateWhen { false }
                    doFirst {
                        val region = System.getenv("FILE_UPLOAD_SERVICE_IT_REGION")
                        if (region.isNullOrBlank()) {
                            throw GradleException(
                                "integrationTest runs against real AWS and isn't configured. Set at least " +
                                    "FILE_UPLOAD_SERVICE_IT_REGION and AWS credentials (AWS_PROFILE or the default chain), " +
                                    "plus the resources each test needs: FILE_UPLOAD_SERVICE_IT_BEDROCK_MODEL_ID, " +
                                    "FILE_UPLOAD_SERVICE_IT_QUARANTINE_BUCKET, FILE_UPLOAD_SERVICE_IT_CLEAN_BUCKET, " +
                                    "FILE_UPLOAD_SERVICE_IT_TABLE (README \"Testing against real AWS\").")
                        }
                    }
                }
            }
        }
    }
}

// The integration tests use the application's classes and dependencies, and the test libraries
configurations["integrationTestImplementation"].extendsFrom(configurations.implementation.get(), configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get())
dependencies {
    "integrationTestImplementation"(project())
    "integrationTestCompileOnly"("org.projectlombok:lombok")
    "integrationTestAnnotationProcessor"("org.projectlombok:lombok")
}

