package com.javed.payloadprobe.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class PayloadWriteAuthenticationPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void disabledAuthenticationStartsWithoutAToken() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PayloadWriteAuthenticationProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void enabledAuthenticationStartsWithAValidToken() {
        contextRunner
                .withPropertyValues(
                        "payloadprobe.write-auth.enabled=true",
                        "payloadprobe.write-auth.bearer-token=valid-write-token-123456")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PayloadWriteAuthenticationProperties.class).isConfigurationValid())
                            .isTrue();
                });
    }

    @Test
    void enabledAuthenticationFailsClosedWithoutAUsableTokenAndDoesNotEchoIt() {
        String invalidSecret = "invalid secret value";

        contextRunner
                .withPropertyValues(
                        "payloadprobe.write-auth.enabled=true",
                        "payloadprobe.write-auth.bearer-token=" + invalidSecret)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureChain(context.getStartupFailure()))
                            .contains("write authentication requires a 16-256 character Bearer token")
                            .doesNotContain(invalidSecret);
                });
    }

    private static String failureChain(Throwable failure) {
        List<String> messages = new ArrayList<>();
        Throwable current = failure;
        while (current != null) {
            messages.add(current.toString());
            current = current.getCause();
        }
        return String.join("\n", messages);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PayloadWriteAuthenticationProperties.class)
    static class TestConfiguration {
    }
}
