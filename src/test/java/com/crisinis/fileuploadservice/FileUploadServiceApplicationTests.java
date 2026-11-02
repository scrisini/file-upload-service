package com.crisinis.fileuploadservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.crisinis.fileuploadservice.config.FileUploadServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FileUploadServiceApplicationTests {

    @Autowired
    private FileUploadServiceProperties properties;

    @Test
    void startsWithDefaultConfiguration() {
        assertThat(properties.outboundProxy().enabled()).isFalse();
    }
}
