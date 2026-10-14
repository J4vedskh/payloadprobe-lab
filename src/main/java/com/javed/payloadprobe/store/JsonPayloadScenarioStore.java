package com.javed.payloadprobe.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javed.payloadprobe.config.PayloadScenarioProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class JsonPayloadScenarioStore implements PayloadScenarioStore {

    private static final int STORE_VERSION = 1;
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,120}");

    private final ObjectMapper objectMapper;
    private final PayloadScenarioProperties properties;
    private final AtomicFileMover atomicFileMover;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Path storePath;
    private LinkedHashMap<String, List<String>> cache = new LinkedHashMap<>();

    @Autowired
    public JsonPayloadScenarioStore(
            ObjectMapper objectMapper,
            PayloadScenarioProperties properties) {
        this(objectMapper, properties, JsonPayloadScenarioStore::moveAtomically);
    }

    JsonPayloadScenarioStore(
            ObjectMapper objectMapper,
            PayloadScenarioProperties properties,
            AtomicFileMover atomicFileMover) {
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        this.atomicFileMover = atomicFileMover;
        validateLimits();
    }

    @PostConstruct
    void initialize() throws IOException {
        storePath = properties.getPath().toAbsolutePath().normalize();
        Path parent = storePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        verifyAtomicMoveSupport();
        if (Files.notExists(storePath)) {
            writeStoreAtomically(new LinkedHashMap<>());
        }
        cache = readStore();
    }

    @Override
    public List<PayloadScenario> list() {
        lock.readLock().lock();
        try {
            return cache.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new PayloadScenario(entry.getKey(), entry.getValue()))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<PayloadScenario> findByName(String name) {
        lock.readLock().lock();
        try {
            List<String> responseKeys = cache.get(name);
            return responseKeys == null
                    ? Optional.empty()
                    : Optional.of(new PayloadScenario(name, responseKeys));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public PutResult put(String name, List<String> responseKeys) {
        lock.writeLock().lock();
        try {
            boolean exists = cache.containsKey(name);
            if (!exists && cache.size() >= properties.getMaxScenarios()) {
                return PutResult.LIMIT_REACHED;
            }
            LinkedHashMap<String, List<String>> candidate = copy(cache);
            candidate.put(name, List.copyOf(responseKeys));
            writeStoreAtomically(candidate);
            cache = candidate;
            return exists ? PutResult.REPLACED : PutResult.CREATED;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(String name) {
        lock.writeLock().lock();
        try {
            if (!cache.containsKey(name)) {
                return false;
            }
            LinkedHashMap<String, List<String>> candidate = copy(cache);
            candidate.remove(name);
            writeStoreAtomically(candidate);
            cache = candidate;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private LinkedHashMap<String, List<String>> readStore() throws IOException {
        ScenarioFile file = objectMapper.readValue(storePath.toFile(), ScenarioFile.class);
        if (file == null || file.version() != STORE_VERSION || file.scenarios() == null) {
            throw new IllegalStateException("Scenario store is invalid.");
        }
        LinkedHashMap<String, List<String>> loaded = new LinkedHashMap<>();
        for (StoredScenario scenario : file.scenarios()) {
            if (scenario == null
                    || scenario.name() == null
                    || !NAME_PATTERN.matcher(scenario.name()).matches()
                    || scenario.responseKeys() == null
                    || scenario.responseKeys().isEmpty()
                    || scenario.responseKeys().size() > properties.getMaxResponseKeys()
                    || scenario.responseKeys().stream().anyMatch(key -> key == null || !NAME_PATTERN.matcher(key).matches())
                    || scenario.responseKeys().stream().distinct().count() != scenario.responseKeys().size()
                    || loaded.putIfAbsent(scenario.name(), List.copyOf(scenario.responseKeys())) != null) {
                throw new IllegalStateException("Scenario store is invalid.");
            }
        }
        if (loaded.size() > properties.getMaxScenarios()) {
            throw new IllegalStateException("Scenario store is invalid.");
        }
        return loaded;
    }

    private void writeStoreAtomically(Map<String, List<String>> scenarios) {
        Path temporaryPath = null;
        try {
            temporaryPath = Files.createTempFile(
                    storePath.getParent(),
                    storePath.getFileName().toString() + ".",
                    ".tmp");
            List<StoredScenario> storedScenarios = scenarios.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new StoredScenario(entry.getKey(), List.copyOf(entry.getValue())))
                    .toList();
            objectMapper.writeValue(temporaryPath.toFile(), new ScenarioFile(STORE_VERSION, storedScenarios));
            atomicFileMover.move(temporaryPath, storePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist scenario store.", exception);
        } finally {
            deleteFile(temporaryPath);
        }
    }

    private void verifyAtomicMoveSupport() {
        Path probeSource = null;
        Path probeTarget = null;
        try {
            probeSource = Files.createTempFile(storePath.getParent(), ".payloadprobe-scenario-move-", ".tmp");
            probeTarget = Files.createTempFile(storePath.getParent(), ".payloadprobe-scenario-target-", ".tmp");
            atomicFileMover.move(probeSource, probeTarget);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "PayloadProbe scenarios require atomic file moves in the store directory.",
                    exception);
        } finally {
            deleteFile(probeSource);
            deleteFile(probeTarget);
        }
    }

    private void validateLimits() {
        if (properties.getMaxScenarios() < 1
                || properties.getMaxResponseKeys() < 1
                || properties.getMaxDocumentBytes() < 1) {
            throw new IllegalArgumentException("Scenario limits must be greater than zero");
        }
    }

    private static LinkedHashMap<String, List<String>> copy(Map<String, List<String>> source) {
        LinkedHashMap<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((name, keys) -> copy.put(name, List.copyOf(keys)));
        return copy;
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Failed persistence or startup already reports the durable boundary.
        }
    }

    record ScenarioFile(int version, List<StoredScenario> scenarios) {
    }

    record StoredScenario(String name, List<String> responseKeys) {
    }

    @FunctionalInterface
    interface AtomicFileMover {
        void move(Path source, Path target) throws IOException;
    }
}
