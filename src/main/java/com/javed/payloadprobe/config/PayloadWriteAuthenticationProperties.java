package com.javed.payloadprobe.config;

import jakarta.validation.constraints.AssertTrue;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "payloadprobe.write-auth")
public class PayloadWriteAuthenticationProperties {

    private static final Pattern BEARER_TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]+");

    private boolean enabled;
    private String bearerToken = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public void setBearerToken(String bearerToken) {
        this.bearerToken = bearerToken;
    }

    @AssertTrue(message = "write authentication requires a 16-256 character Bearer token")
    public boolean isConfigurationValid() {
        if (!enabled) {
            return true;
        }
        return bearerToken != null
                && bearerToken.length() >= 16
                && bearerToken.length() <= 256
                && BEARER_TOKEN.matcher(bearerToken).matches();
    }
}
