package com.crisinis.fileuploadservice.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description springdoc serves at {@code /v3/api-docs}, with Swagger UI at
 * {@code /swagger-ui.html} ({@code springdoc.*} in application.yml). Operations come from the
 * controllers and their annotations; this adds what describes the API as a whole.
 */
@Configuration(proxyBeanMethods = false)
class ApiDocumentation {

    @Bean
    OpenAPI fileUploadServiceApi(ObjectProvider<BuildProperties> build) {
        String version = build.getIfAvailable() == null ? "development" : build.getIfAvailable().getVersion();
        return new OpenAPI().info(new Info()
                .title("file-upload-service")
                .version(version)
                .description("""
                        Uploads of images and PDFs, scanned by AWS GuardDuty before they're served.

                        Two upload styles end the same way (docs/upload-styles.md compares them):
                        **presigned URL** (`POST /api/v1/uploads`, then PUT the file to S3) and
                        **stream** (`POST /api/v1/files` with the file as the body). Follow a file with
                        `GET /api/v1/files/{id}` until its status is AVAILABLE, INFECTED, REJECTED or
                        NOT_SCANNED.

                        Errors are RFC 9457 problem details with a `requestId`; refused uploads add a
                        `reason`. **No authentication yet.**"""));
    }
}
