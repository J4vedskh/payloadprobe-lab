package com.javed.payloadprobe.store;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javed.payloadprobe.config.PayloadProbeProperties;
import com.javed.payloadprobe.config.PayloadResponseHistoryProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

@Repository
public class JsonPayloadResponseStore implements PayloadResponseStore {

    static final String CATALOG_SIZE_METRIC = "payloadprobe.response.catalog.size";
    static final String READS_METRIC = "payloadprobe.response.reads";
    static final String MISSES_METRIC = "payloadprobe.response.misses";
    static final String WRITES_METRIC = "payloadprobe.response.writes";

    private static final int STORE_VERSION = 2;
    private static final TypeReference<LinkedHashMap<String, String>> LEGACY_RESPONSE_MAP =
            new TypeReference<>() {
            };
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,120}");
    private static final String OPERATION_TAG = "operation";
    private static final String OUTCOME_TAG = "outcome";
    private static final String SUCCESS_OUTCOME = "success";
    private static final String REJECTED_OUTCOME = "rejected";

    private final ObjectMapper objectMapper;
    private final PayloadProbeProperties properties;
    private final PayloadResponseHistoryProperties historyProperties;
    private final Counter reads;
    private final Counter misses;
    private final Map<WriteOperation, Counter> successfulWrites;
    private final Map<WriteOperation, Counter> rejectedWrites;
    private final AtomicFileMover atomicFileMover;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Path storePath;
    private LinkedHashMap<String, ResponseHistory> cache = new LinkedHashMap<>();

    @Autowired
    public JsonPayloadResponseStore(
            ObjectMapper objectMapper,
            PayloadProbeProperties properties,
            PayloadResponseHistoryProperties historyProperties,
            MeterRegistry meterRegistry) {
        this(
                objectMapper,
                properties,
                historyProperties,
                meterRegistry,
                JsonPayloadResponseStore::moveAtomically);
    }

    JsonPayloadResponseStore(
            ObjectMapper objectMapper,
            PayloadProbeProperties properties,
            PayloadResponseHistoryProperties historyProperties,
            MeterRegistry meterRegistry,
            AtomicFileMover atomicFileMover) {
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        this.historyProperties = historyProperties;
        this.atomicFileMover = atomicFileMover;
        validateHistoryLimit();
        this.reads = Counter.builder(READS_METRIC)
                .description("Completed response lookup attempts.")
                .register(meterRegistry);
        this.misses = Counter.builder(MISSES_METRIC)
                .description("Response lookups that found no stored payload.")
                .register(meterRegistry);
        this.successfulWrites = registerWriteCounters(meterRegistry, SUCCESS_OUTCOME);
        this.rejectedWrites = registerWriteCounters(meterRegistry, REJECTED_OUTCOME);
        Gauge.builder(CATALOG_SIZE_METRIC, this, JsonPayloadResponseStore::catalogSize)
                .description("Current number of stored XML response keys.")
                .register(meterRegistry);
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
            seedStore();
        }
        LoadResult loaded = readStore();
        if (loaded.requiresRewrite()) {
            writeStoreAtomically(loaded.responses());
        }
        cache = loaded.responses();
    }

    @Override
    public List<String> keys() {
        lock.readLock().lock();
        try {
            ArrayList<String> keys = new ArrayList<>(cache.keySet());
            keys.sort(String::compareToIgnoreCase);
            return keys;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<String> findByKey(String key) {
        Optional<String> response;
        lock.readLock().lock();
        try {
            ResponseHistory history = cache.get(key);
            response = history == null ? Optional.empty() : Optional.of(history.currentXml());
        } finally {
            lock.readLock().unlock();
        }
        reads.increment();
        if (response.isEmpty()) {
            misses.increment();
        }
        return response;
    }

    @Override
    public Map<String, String> snapshot() {
        lock.readLock().lock();
        try {
            LinkedHashMap<String, String> snapshot = new LinkedHashMap<>();
            cache.forEach((key, history) -> snapshot.put(key, history.currentXml()));
            return snapshot;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<List<PayloadResponseRevision>> findRevisions(String key) {
        lock.readLock().lock();
        try {
            ResponseHistory history = cache.get(key);
            if (history == null) {
                return Optional.empty();
            }
            ArrayList<PayloadResponseRevision> newestFirst = new ArrayList<>(history.revisions());
            Collections.reverse(newestFirst);
            return Optional.of(List.copyOf(newestFirst));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean create(String key, String xmlInput) {
        boolean created;
        lock.writeLock().lock();
        try {
            if (cache.containsKey(key)) {
                created = false;
            } else {
                LinkedHashMap<String, ResponseHistory> candidate = copy(cache);
                candidate.put(key, ResponseHistory.first(xmlInput));
                writeStoreAtomically(candidate);
                cache = candidate;
                created = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        recordWrite(WriteOperation.CREATE, created);
        return created;
    }

    @Override
    public boolean update(String key, String xmlInput) {
        boolean updated;
        lock.writeLock().lock();
        try {
            ResponseHistory current = cache.get(key);
            if (current == null) {
                updated = false;
            } else {
                LinkedHashMap<String, ResponseHistory> candidate = copy(cache);
                candidate.put(
                        key,
                        current.append(xmlInput, historyProperties.getMaxRevisionsPerResponse()));
                writeStoreAtomically(candidate);
                cache = candidate;
                updated = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        recordWrite(WriteOperation.UPDATE, updated);
        return updated;
    }

    @Override
    public boolean delete(String key) {
        boolean deleted;
        lock.writeLock().lock();
        try {
            if (!cache.containsKey(key)) {
                deleted = false;
            } else {
                LinkedHashMap<String, ResponseHistory> candidate = copy(cache);
                candidate.remove(key);
                writeStoreAtomically(candidate);
                cache = candidate;
                deleted = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        recordWrite(WriteOperation.DELETE, deleted);
        return deleted;
    }

    @Override
    public boolean createAllIfAbsent(Map<String, String> responses) {
        lock.writeLock().lock();
        try {
            if (responses.keySet().stream().anyMatch(cache::containsKey)) {
                return false;
            }
            LinkedHashMap<String, ResponseHistory> candidate = copy(cache);
            responses.forEach((key, xml) -> candidate.put(key, ResponseHistory.first(xml)));
            writeStoreAtomically(candidate);
            cache = candidate;
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static Map<WriteOperation, Counter> registerWriteCounters(
            MeterRegistry meterRegistry,
            String outcome) {
        EnumMap<WriteOperation, Counter> counters = new EnumMap<>(WriteOperation.class);
        for (WriteOperation operation : WriteOperation.values()) {
            counters.put(operation, Counter.builder(WRITES_METRIC)
                    .description("Completed response mutation attempts.")
                    .tag(OPERATION_TAG, operation.tagValue)
                    .tag(OUTCOME_TAG, outcome)
                    .register(meterRegistry));
        }
        return counters;
    }

    private void recordWrite(WriteOperation operation, boolean successful) {
        Map<WriteOperation, Counter> counters = successful ? successfulWrites : rejectedWrites;
        counters.get(operation).increment();
    }

    private double catalogSize() {
        lock.readLock().lock();
        try {
            return cache.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void seedStore() throws IOException {
        ClassPathResource seed = new ClassPathResource("xmlResponses.json");
        if (seed.exists()) {
            try (InputStream inputStream = seed.getInputStream()) {
                Files.copy(inputStream, storePath);
                return;
            }
        }
        Files.writeString(storePath, "{}", StandardCharsets.UTF_8);
    }

    private LoadResult readStore() throws IOException {
        if (Files.size(storePath) == 0) {
            return new LoadResult(new LinkedHashMap<>(), true);
        }
        JsonNode root = objectMapper.readTree(storePath.toFile());
        if (looksVersioned(root)) {
            return readVersionedStore(root);
        }
        return readLegacyStore(root);
    }

    private boolean looksVersioned(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        JsonNode version = root.get("version");
        JsonNode responses = root.get("responses");
        return (version != null && !version.isTextual())
                || (responses != null && !responses.isTextual());
    }

    private LoadResult readLegacyStore(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw invalidStore();
        }
        LinkedHashMap<String, String> legacy;
        try {
            legacy = objectMapper.convertValue(root, LEGACY_RESPONSE_MAP);
        } catch (IllegalArgumentException exception) {
            throw invalidStore(exception);
        }
        LinkedHashMap<String, ResponseHistory> migrated = new LinkedHashMap<>();
        legacy.forEach((key, xml) -> {
            validateKeyAndXml(key, xml);
            migrated.put(key, ResponseHistory.first(xml));
        });
        return new LoadResult(canonicalCopy(migrated), true);
    }

    private LoadResult readVersionedStore(JsonNode root) throws IOException {
        ResponseStoreFile file = objectMapper.treeToValue(root, ResponseStoreFile.class);
        if (file == null || file.version() != STORE_VERSION || file.responses() == null) {
            throw invalidStore();
        }
        boolean pruned = false;
        LinkedHashMap<String, ResponseHistory> loaded = new LinkedHashMap<>();
        for (StoredResponse response : file.responses()) {
            if (response == null
                    || response.revisions() == null
                    || response.revisions().isEmpty()
                    || response.revisions().get(0) == null) {
                throw invalidStore();
            }
            validateKeyAndXml(response.key(), response.revisions().get(0).xml());
            ArrayList<PayloadResponseRevision> revisions = new ArrayList<>();
            long previousRevision = 0;
            for (StoredRevision revision : response.revisions()) {
                if (revision == null
                        || revision.revision() <= previousRevision
                        || revision.xml() == null) {
                    throw invalidStore();
                }
                revisions.add(new PayloadResponseRevision(revision.revision(), revision.xml()));
                previousRevision = revision.revision();
            }
            int limit = historyProperties.getMaxRevisionsPerResponse();
            if (revisions.size() > limit) {
                revisions = new ArrayList<>(revisions.subList(revisions.size() - limit, revisions.size()));
                pruned = true;
            }
            if (loaded.putIfAbsent(response.key(), new ResponseHistory(revisions)) != null) {
                throw invalidStore();
            }
        }
        return new LoadResult(canonicalCopy(loaded), pruned);
    }

    private void writeStoreAtomically(Map<String, ResponseHistory> responses) {
        Path temporaryPath = null;
        try {
            temporaryPath = Files.createTempFile(
                    storePath.getParent(),
                    storePath.getFileName().toString() + ".",
                    ".tmp");
            List<StoredResponse> storedResponses = responses.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new StoredResponse(
                            entry.getKey(),
                            entry.getValue().revisions().stream()
                                    .map(revision -> new StoredRevision(revision.revision(), revision.xml()))
                                    .toList()))
                    .toList();
            objectMapper.writeValue(
                    temporaryPath.toFile(),
                    new ResponseStoreFile(STORE_VERSION, storedResponses));
            atomicFileMover.move(temporaryPath, storePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist XML response store.", exception);
        } finally {
            deleteFile(temporaryPath);
        }
    }

    private void verifyAtomicMoveSupport() {
        Path probeSource = null;
        Path probeTarget = null;
        try {
            probeSource = Files.createTempFile(storePath.getParent(), ".payloadprobe-atomic-move-", ".tmp");
            probeTarget = Files.createTempFile(storePath.getParent(), ".payloadprobe-atomic-target-", ".tmp");
            atomicFileMover.move(probeSource, probeTarget);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "PayloadProbe response storage requires atomic file moves in the store directory.",
                    exception);
        } finally {
            deleteFile(probeSource);
            deleteFile(probeTarget);
        }
    }

    private void validateHistoryLimit() {
        int limit = historyProperties.getMaxRevisionsPerResponse();
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Response history limit must be between 1 and 1000");
        }
    }

    private static void validateKeyAndXml(String key, String xml) {
        if (key == null || !KEY_PATTERN.matcher(key).matches() || xml == null) {
            throw invalidStore();
        }
    }

    private static LinkedHashMap<String, ResponseHistory> copy(Map<String, ResponseHistory> source) {
        return new LinkedHashMap<>(source);
    }

    private static LinkedHashMap<String, ResponseHistory> canonicalCopy(
            Map<String, ResponseHistory> source) {
        LinkedHashMap<String, ResponseHistory> canonical = new LinkedHashMap<>();
        source.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> canonical.put(entry.getKey(), entry.getValue()));
        return canonical;
    }

    private static IllegalStateException invalidStore() {
        return new IllegalStateException("XML response store is invalid.");
    }

    private static IllegalStateException invalidStore(Exception cause) {
        return new IllegalStateException("XML response store is invalid.", cause);
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

    record ResponseStoreFile(int version, List<StoredResponse> responses) {
    }

    record StoredResponse(String key, List<StoredRevision> revisions) {
    }

    record StoredRevision(long revision, String xml) {
    }

    private record LoadResult(
            LinkedHashMap<String, ResponseHistory> responses,
            boolean requiresRewrite) {
    }

    private record ResponseHistory(List<PayloadResponseRevision> revisions) {

        private ResponseHistory {
            revisions = List.copyOf(revisions);
        }

        private static ResponseHistory first(String xml) {
            return new ResponseHistory(List.of(new PayloadResponseRevision(1, xml)));
        }

        private String currentXml() {
            return revisions.get(revisions.size() - 1).xml();
        }

        private ResponseHistory append(String xml, int limit) {
            PayloadResponseRevision current = revisions.get(revisions.size() - 1);
            if (current.revision() == Long.MAX_VALUE) {
                throw new IllegalStateException("Response revision number is exhausted.");
            }
            ArrayList<PayloadResponseRevision> updated = new ArrayList<>(revisions);
            updated.add(new PayloadResponseRevision(current.revision() + 1, xml));
            if (updated.size() > limit) {
                updated = new ArrayList<>(updated.subList(updated.size() - limit, updated.size()));
            }
            return new ResponseHistory(updated);
        }
    }

    @FunctionalInterface
    interface AtomicFileMover {
        void move(Path source, Path target) throws IOException;
    }

    private enum WriteOperation {
        CREATE("create"),
        UPDATE("update"),
        DELETE("delete");

        private final String tagValue;

        WriteOperation(String tagValue) {
            this.tagValue = tagValue;
        }
    }
}
