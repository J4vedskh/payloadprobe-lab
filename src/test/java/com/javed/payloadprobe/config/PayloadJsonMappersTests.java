package com.javed.payloadprobe.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

class PayloadJsonMappersTests {

    @Test
    void strictDocumentReaderPreservesInjectedMapperConfiguration() throws Exception {
        ObjectMapper baseMapper = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build();
        PayloadJsonMappers.StrictDocumentReader<CustomDocument> reader =
                PayloadJsonMappers.strictDocumentReader(baseMapper, CustomDocument.class, 10, 100);

        CustomDocument document = reader.readValue(
                "{\"response_key\":\"openTest\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(document.responseKey()).isEqualTo("openTest");
    }

    private record CustomDocument(String responseKey) {
    }
}
