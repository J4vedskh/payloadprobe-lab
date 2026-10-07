package com.javed.payloadprobe.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.config.PayloadProbeProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonPayloadResponseStoreMetricsTests {

    private SimpleMeterRegistry meterRegistry;
    private JsonPayloadResponseStore store;
    private Path storePath;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        storePath = Path.of("target", "test-data", "metrics-" + UUID.randomUUID() + ".json");
        Files.createDirectories(storePath.getParent());
        objectMapper.writeValue(storePath.toFile(), new LinkedHashMap<>(Map.of(
                "alpha", "<alpha/>",
                "beta", "<beta/>")));

        PayloadProbeProperties properties = new PayloadProbeProperties();
        properties.setPath(storePath);
        meterRegistry = new SimpleMeterRegistry();
        store = new JsonPayloadResponseStore(objectMapper, properties, meterRegistry);
        store.initialize();
    }

    @AfterEach
    void closeMeterRegistry() throws IOException {
        meterRegistry.close();
        Files.deleteIfExists(storePath);
    }

    @Test
    void reportsTheCurrentCatalogSize() {
        assertThat(gaugeValue()).isEqualTo(2.0);

        assertThat(store.create("gamma", "<gamma/>")).isTrue();
        assertThat(gaugeValue()).isEqualTo(3.0);

        assertThat(store.update("alpha", "<alpha>updated</alpha>")).isTrue();
        assertThat(gaugeValue()).isEqualTo(3.0);

        assertThat(store.delete("beta")).isTrue();
        assertThat(gaugeValue()).isEqualTo(2.0);
    }

    @Test
    void countsResponseLookupsAndMissesWithoutCountingCatalogLists() {
        assertThat(store.findByKey("alpha")).contains("<alpha/>");
        assertThat(store.findByKey("missing")).isEmpty();
        assertThat(store.keys()).containsExactly("alpha", "beta");

        assertThat(counterValue(JsonPayloadResponseStore.READS_METRIC)).isEqualTo(2.0);
        assertThat(counterValue(JsonPayloadResponseStore.MISSES_METRIC)).isEqualTo(1.0);
    }

    @Test
    void countsSuccessfulAndRejectedWriteAttemptsWithBoundedTags() {
        assertThat(store.create("gamma", "<gamma/>")).isTrue();
        assertThat(store.create("gamma", "<duplicate/>")).isFalse();
        assertThat(store.update("alpha", "<alpha>updated</alpha>")).isTrue();
        assertThat(store.update("missing", "<missing/>")).isFalse();
        assertThat(store.delete("beta")).isTrue();
        assertThat(store.delete("missing")).isFalse();

        assertWriteCount("create", "success", 1.0);
        assertWriteCount("create", "rejected", 1.0);
        assertWriteCount("update", "success", 1.0);
        assertWriteCount("update", "rejected", 1.0);
        assertWriteCount("delete", "success", 1.0);
        assertWriteCount("delete", "rejected", 1.0);
    }

    private double gaugeValue() {
        return meterRegistry.get(JsonPayloadResponseStore.CATALOG_SIZE_METRIC).gauge().value();
    }

    private double counterValue(String metricName) {
        return meterRegistry.get(metricName).counter().count();
    }

    private void assertWriteCount(String operation, String outcome, double expectedCount) {
        assertThat(meterRegistry.get(JsonPayloadResponseStore.WRITES_METRIC)
                        .tag("operation", operation)
                        .tag("outcome", outcome)
                        .counter()
                        .count())
                .isEqualTo(expectedCount);
    }
}
