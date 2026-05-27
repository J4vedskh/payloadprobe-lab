package com.javed.payloadprobe.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payloadprobe.store")
public class PayloadProbeProperties {

    private Path path = Path.of("data", "xmlResponses.json");

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }
}
