package com.javed.payloadprobe.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payloadprobe.scenarios")
public class PayloadScenarioProperties {

    private Path path = Path.of("data", "scenarios.json");
    private int maxScenarios = 100;
    private int maxResponseKeys = 100;
    private int maxDocumentBytes = 64 * 1024;

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    public int getMaxScenarios() {
        return maxScenarios;
    }

    public void setMaxScenarios(int maxScenarios) {
        this.maxScenarios = maxScenarios;
    }

    public int getMaxResponseKeys() {
        return maxResponseKeys;
    }

    public void setMaxResponseKeys(int maxResponseKeys) {
        this.maxResponseKeys = maxResponseKeys;
    }

    public int getMaxDocumentBytes() {
        return maxDocumentBytes;
    }

    public void setMaxDocumentBytes(int maxDocumentBytes) {
        this.maxDocumentBytes = maxDocumentBytes;
    }
}
