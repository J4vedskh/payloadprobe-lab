package com.javed.payloadprobe.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.config.PayloadProbeProperties;
import com.javed.payloadprobe.config.PayloadResponseHistoryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
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
    private PayloadResponseHistoryProperties historyProperties;

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
        historyProperties = new PayloadResponseHistoryProperties();
        meterRegistry = new SimpleMeterRegistry();
        store = new JsonPayloadResponseStore(objectMapper, properties, historyProperties, meterRegistry);
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

    @Test
    void mergesAResponseSetAtomicallyAndReturnsDetachedSnapshots() throws IOException {
        Map<String, String> detached = store.snapshot();
        detached.put("outside", "<outside/>");
        assertThat(store.findByKey("outside")).isEmpty();

        String fileBeforeConflict = Files.readString(storePath);
        assertThat(store.createAllIfAbsent(new LinkedHashMap<>(Map.of(
                        "alpha", "<replacement/>",
                        "gamma", "<gamma/>"))))
                .isFalse();
        assertThat(store.snapshot()).containsExactlyEntriesOf(Map.of(
                "alpha", "<alpha/>",
                "beta", "<beta/>"));
        assertThat(Files.readString(storePath)).isEqualTo(fileBeforeConflict);

        assertThat(store.createAllIfAbsent(new LinkedHashMap<>(Map.of(
                        "gamma", "<gamma/>",
                        "delta", "<delta/>"))))
                .isTrue();
        assertThat(store.snapshot())
                .containsEntry("alpha", "<alpha/>")
                .containsEntry("beta", "<beta/>")
                .containsEntry("gamma", "<gamma/>")
                .containsEntry("delta", "<delta/>");
        assertThat(new ObjectMapper().readTree(storePath.toFile()).path("responses").toString())
                .contains("\"key\":\"gamma\"")
                .contains("\"key\":\"delta\"");
        assertThat(gaugeValue()).isEqualTo(4.0);
    }

    @Test
    void failsStartupWhenTheStoreCannotGuaranteeAtomicMoves() {
        Path unsupportedPath = Path.of(
                "target",
                "test-data",
                "atomic-move-unsupported-" + UUID.randomUUID() + ".json");
        PayloadProbeProperties unsupportedProperties = new PayloadProbeProperties();
        unsupportedProperties.setPath(unsupportedPath);

        SimpleMeterRegistry unsupportedRegistry = new SimpleMeterRegistry();
        try {
            JsonPayloadResponseStore unsupportedStore = new JsonPayloadResponseStore(
                    new ObjectMapper(),
                    unsupportedProperties,
                    historyProperties,
                    unsupportedRegistry,
                    (source, target) -> {
                        throw new AtomicMoveNotSupportedException(
                                source.toString(),
                                target.toString(),
                                "unsupported by test filesystem");
                    });

            assertThatThrownBy(unsupportedStore::initialize)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PayloadProbe response storage requires atomic file moves in the store directory.");
            assertThat(Files.exists(unsupportedPath)).isFalse();
        } finally {
            unsupportedRegistry.close();
        }
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
