package com.smartmgmt.management.document;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/** Local disk location for uploaded documents, bound from {@code app.storage.*}. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.storage")
public class DocumentStorageProperties {

    private String documentsDir = "./uploads/documents";
    private long maxFileSizeBytes = 25L * 1024 * 1024;
}
