package com.javed.payloadprobe.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.config.PayloadProbeProperties;
import com.javed.payloadprobe.config.PayloadResponseHistoryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JsonPayloadResponseStoreHistoryTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path testDirectory = Path.of("target", "test-data");
    private final List<SimpleMeterRegistry> registries = new java.util.ArrayList<>();

    @AfterEach
    void closeRegistries() {
        registries.forEach(SimpleMeterRegistry::close);
    }

    @Test
    void migratesLegacyResponsesAndReloadsExactVersions() throws IOException {
        Path path = newStorePath("migration");
        objectMapper.writeValue(path.toFile(), new LinkedHashMap<>(Map.of(
                "alpha", "<alpha>one</alpha>",
                "beta", "<beta/>")));

        JsonPayloadResponseStore store = initialize(path, 3);

        assertThat(store.findByKey("alpha")).contains("<alpha>one</alpha>");
        assertThat(store.findRevisions("alpha")).contains(List.of(
                new PayloadResponseRevision(1, "<alpha>one</alpha>")));
        JsonNode migrated = objectMapper.readTree(path.toFile());
        assertThat(migrated.path("version").asInt()).isEqualTo(2);
        assertThat(migrated.path("responses").isArray()).isTrue();

        assertThat(store.update("alpha", "<alpha>two</alpha>")).isTrue();
        JsonPayloadResponseStore reloaded = initialize(path, 3);

        assertThat(reloaded.findByKey("alpha")).contains("<alpha>two</alpha>");
        assertThat(reloaded.findRevisions("alpha")).contains(List.of(
                new PayloadResponseRevision(2, "<alpha>two</alpha>"),
                new PayloadResponseRevision(1, "<alpha>one</alpha>")));
        assertThat(reloaded.keys()).containsExactly("alpha", "beta");
    }

    @Test
    void retainsOnlyTheNewestConfiguredVersionsWithoutRenumbering() throws IOException {
        Path path = newStorePath("retention");
        objectMapper.writeValue(path.toFile(), Map.of());
        JsonPayloadResponseStore store = initialize(path, 2);

        assertThat(store.create("alpha", "<alpha>one</alpha>")).isTrue();
        assertThat(store.update("alpha", "<alpha>two</alpha>")).isTrue();
        assertThat(store.update("alpha", "<alpha>three</alpha>")).isTrue();

        assertThat(store.findRevisions("alpha")).contains(List.of(
                new PayloadResponseRevision(3, "<alpha>three</alpha>"),
                new PayloadResponseRevision(2, "<alpha>two</alpha>")));
        assertThat(initialize(path, 2).findRevisions("alpha")).contains(List.of(
                new PayloadResponseRevision(3, "<alpha>three</alpha>"),
                new PayloadResponseRevision(2, "<alpha>two</alpha>")));
    }

    @Test
    void importsStartAtRevisionOneAndDeletePurgesTheHistory() throws IOException {
        Path path = newStorePath("lifecycle");
        objectMapper.writeValue(path.toFile(), Map.of());
        JsonPayloadResponseStore store = initialize(path, 10);

        assertThat(store.createAllIfAbsent(new LinkedHashMap<>(Map.of(
                        "alpha", "<alpha/>",
                        "beta", "<beta/>"))))
                .isTrue();
        assertThat(store.findRevisions("alpha")).contains(List.of(
                new PayloadResponseRevision(1, "<alpha/>")));
        assertThat(store.delete("alpha")).isTrue();
        assertThat(store.findRevisions("alpha")).isEmpty();

        assertThat(store.create("alpha", "<alpha>new lifetime</alpha>")).isTrue();
        assertThat(store.findRevisions("alpha")).contains(List.of(
                new PayloadResponseRevision(1, "<alpha>new lifetime</alpha>")));
    }

    @ParameterizedTest
    @EnumSource(FailingMutation.class)
    void failedAtomicWritesLeaveDiskCurrentValuesAndHistoryUnchanged(FailingMutation mutation)
            throws IOException {
        Path path = newStorePath("atomic-" + mutation.name().toLowerCase());
        objectMapper.writeValue(path.toFile(), Map.of("alpha", "<alpha>one</alpha>"));
        byte[] legacyBytes = Files.readAllBytes(path);
        AtomicInteger moves = new AtomicInteger();
        PayloadProbeProperties storeProperties = storeProperties(path);
        PayloadResponseHistoryProperties historyProperties = historyProperties(10);
        SimpleMeterRegistry registry = registry();
        JsonPayloadResponseStore store = new JsonPayloadResponseStore(
                objectMapper,
                storeProperties,
                historyProperties,
                registry,
                (source, target) -> {
                    int move = moves.incrementAndGet();
                    if (move >= 3) {
                        throw new IOException("forced mutation failure");
                    }
                    Files.move(
                            source,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                });
        store.initialize();
        byte[] beforeMutation = Files.readAllBytes(path);
        Map<String, String> snapshotBefore = store.snapshot();
        List<PayloadResponseRevision> historyBefore = store.findRevisions("alpha").orElseThrow();

        assertThatThrownBy(() -> mutation.apply(store))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to persist XML response store.");

        assertThat(store.snapshot()).isEqualTo(snapshotBefore);
        assertThat(store.findRevisions("alpha")).contains(historyBefore);
        assertThat(Files.readAllBytes(path)).isEqualTo(beforeMutation).isNotEqualTo(legacyBytes);
        try (Stream<Path> files = Files.list(path.getParent())) {
            assertThat(files.noneMatch(candidate -> candidate.getFileName().toString()
                    .startsWith(path.getFileName().toString() + "."))).isTrue();
        }
        if (mutation.metricOperation != null) {
            assertThat(registry.get(JsonPayloadResponseStore.WRITES_METRIC)
                            .tag("operation", mutation.metricOperation)
                            .tag("outcome", "success")
                            .counter()
                            .count())
                    .isZero();
        }
    }

    @Test
    void failedMigrationAndUnsupportedVersionsLeaveTheOriginalFileUntouched() throws IOException {
        Path migrationPath = newStorePath("migration-failure");
        objectMapper.writeValue(migrationPath.toFile(), Map.of("alpha", "<alpha/>"));
        byte[] legacyBytes = Files.readAllBytes(migrationPath);
        AtomicInteger moves = new AtomicInteger();
        JsonPayloadResponseStore migrationStore = new JsonPayloadResponseStore(
                objectMapper,
                storeProperties(migrationPath),
                historyProperties(10),
                registry(),
                (source, target) -> {
                    if (moves.incrementAndGet() == 2) {
                        throw new IOException("forced migration failure");
                    }
                    Files.move(
                            source,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                });

        assertThatThrownBy(migrationStore::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to persist XML response store.");
        assertThat(Files.readAllBytes(migrationPath)).isEqualTo(legacyBytes);

        Path futurePath = newStorePath("future-version");
        String futureDocument = "{\"version\":3,\"responses\":[]}";
        Files.writeString(futurePath, futureDocument);
        assertThatThrownBy(() -> initialize(futurePath, 10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("XML response store is invalid.");
        assertThat(Files.readString(futurePath)).isEqualTo(futureDocument);
    }

    @Test
    void rejectsUnboundedHistoryConfiguration() throws IOException {
        Path path = newStorePath("invalid-limit");
        PayloadResponseHistoryProperties history = historyProperties(0);

        assertThatThrownBy(() -> new JsonPayloadResponseStore(
                        objectMapper,
                        storeProperties(path),
                        history,
                        registry()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Response history limit must be between 1 and 1000");
    }

    private JsonPayloadResponseStore initialize(Path path, int historyLimit) throws IOException {
        JsonPayloadResponseStore store = new JsonPayloadResponseStore(
                objectMapper,
                storeProperties(path),
                historyProperties(historyLimit),
                registry());
        store.initialize();
        return store;
    }

    private PayloadProbeProperties storeProperties(Path path) {
        PayloadProbeProperties properties = new PayloadProbeProperties();
        properties.setPath(path);
        return properties;
    }

    private PayloadResponseHistoryProperties historyProperties(int limit) {
        PayloadResponseHistoryProperties properties = new PayloadResponseHistoryProperties();
        properties.setMaxRevisionsPerResponse(limit);
        return properties;
    }

    private SimpleMeterRegistry registry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registries.add(registry);
        return registry;
    }

    private Path newStorePath(String prefix) throws IOException {
        Files.createDirectories(testDirectory);
        return testDirectory.resolve(prefix + "-" + UUID.randomUUID() + ".json");
    }

    private enum FailingMutation {
        CREATE("create") {
            @Override
            void apply(JsonPayloadResponseStore store) {
                store.create("gamma", "<gamma/>");
            }
        },
        UPDATE("update") {
            @Override
            void apply(JsonPayloadResponseStore store) {
                store.update("alpha", "<alpha>two</alpha>");
            }
        },
        DELETE("delete") {
            @Override
            void apply(JsonPayloadResponseStore store) {
                store.delete("alpha");
            }
        },
        IMPORT(null) {
            @Override
            void apply(JsonPayloadResponseStore store) {
                store.createAllIfAbsent(Map.of("gamma", "<gamma/>"));
            }
        };

        private final String metricOperation;

        FailingMutation(String metricOperation) {
            this.metricOperation = metricOperation;
        }

        abstract void apply(JsonPayloadResponseStore store);
    }
}
