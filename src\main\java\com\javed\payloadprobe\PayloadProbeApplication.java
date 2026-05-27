package com.javed.payloadprobe;

import com.javed.payloadprobe.config.PayloadProbeProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PayloadProbeProperties.class)
public class PayloadProbeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PayloadProbeApplication.class, args);
    }
}
