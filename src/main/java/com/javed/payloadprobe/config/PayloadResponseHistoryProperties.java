package com.javed.payloadprobe.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "payloadprobe.response-history")
public class PayloadResponseHistoryProperties {

    @Min(1)
    @Max(1000)
    private int maxRevisionsPerResponse = 10;

    public int getMaxRevisionsPerResponse() {
        return maxRevisionsPerResponse;
    }

    public void setMaxRevisionsPerResponse(int maxRevisionsPerResponse) {
        this.maxRevisionsPerResponse = maxRevisionsPerResponse;
    }
}
