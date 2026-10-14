package com.javed.payloadprobe.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.config.PayloadScenarioProperties;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonPayloadScenarioStoreTests {

    private Path storePath;
    private PayloadScenarioProperties properties;
    private JsonPayloadScenarioStore store;

    @BeforeEach
    void setUp() throws IOException {
        storePath = Path.of("target", "test-data", "scenario-store-" + UUID.randomUUID() + ".json");
        Files.createDirectories(storePath.getParent());
        properties = properties(storePath);
        store = new JsonPayloadScenarioStore(new ObjectMapper(), properties);
        store.initialize();
    }

    @AfterEach
    void cleanUp() throws IOException {
        Files.deleteIfExists(storePath);
    }

    @Test
    void createsReplacesDeletesAndReloadsScenarios() throws IOException {
        assertThat(store.put("beta", List.of("openTest")))
                .isEqualTo(PayloadScenarioStore.PutResult.CREATED);
        assertThat(store.put("alpha", List.of("FdLiquidatTestMock", "openTest")))
                .isEqualTo(PayloadScenarioStore.PutResult.CREATED);
        assertThat(store.list()).extracting(PayloadScenario::name).containsExactly("alpha", "beta");

        assertThat(store.put("alpha", List.of("NtbtdMockTest")))
                .isEqualTo(PayloadScenarioStore.PutResult.REPLACED);
        assertThat(store.findByName("alpha")).get()
                .extracting(PayloadScenario::responseKeys)
                .isEqualTo(List.of("NtbtdMockTest"));

        JsonPayloadScenarioStore reloaded = new JsonPayloadScenarioStore(new ObjectMapper(), properties);
        reloaded.initialize();
        assertThat(reloaded.findByName("alpha")).contains(new PayloadScenario("alpha", List.of("NtbtdMockTest")));

        assertThat(store.delete("beta")).isTrue();
        assertThat(store.delete("beta")).isFalse();
    }

    @Test
    void enforcesTheScenarioCatalogLimitWithoutChangingTheFile() throws IOException {
        assertThat(store.put("alpha", List.of("openTest"))).isEqualTo(PayloadScenarioStore.PutResult.CREATED);
        assertThat(store.put("beta", List.of("openTest"))).isEqualTo(PayloadScenarioStore.PutResult.CREATED);
        String before = Files.readString(storePath);

        assertThat(store.put("gamma", List.of("openTest")))
                .isEqualTo(PayloadScenarioStore.PutResult.LIMIT_REACHED);
        assertThat(Files.readString(storePath)).isEqualTo(before);
        assertThat(store.list()).extracting(PayloadScenario::name).containsExactly("alpha", "beta");
    }

    @Test
    void persistenceFailureLeavesCacheAndFileUnchanged() throws IOException {
        Path failingPath = Path.of("target", "test-data", "scenario-failure-" + UUID.randomUUID() + ".json");
        Files.writeString(failingPath, "{\"version\":1,\"scenarios\":[]}");
        PayloadScenarioProperties failingProperties = properties(failingPath);
        AtomicInteger moves = new AtomicInteger();
        JsonPayloadScenarioStore failingStore = new JsonPayloadScenarioStore(
                new ObjectMapper(),
                failingProperties,
                (source, target) -> {
                    if (moves.incrementAndGet() == 1) {
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        return;
                    }
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "test failure");
                });
        failingStore.initialize();
        String before = Files.readString(failingPath);

        assertThatThrownBy(() -> failingStore.put("alpha", List.of("openTest")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to persist scenario store.");
        assertThat(failingStore.list()).isEmpty();
        assertThat(Files.readString(failingPath)).isEqualTo(before);
        Files.deleteIfExists(failingPath);
    }

    private PayloadScenarioProperties properties(Path path) {
        PayloadScenarioProperties scenarioProperties = new PayloadScenarioProperties();
        scenarioProperties.setPath(path);
        scenarioProperties.setMaxScenarios(2);
        scenarioProperties.setMaxResponseKeys(2);
        scenarioProperties.setMaxDocumentBytes(512);
        return scenarioProperties;
    }
}
