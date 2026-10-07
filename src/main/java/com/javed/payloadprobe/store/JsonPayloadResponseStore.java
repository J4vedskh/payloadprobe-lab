package com.javed.payloadprobe.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javed.payloadprobe.config.PayloadProbeProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

@Repository
public class JsonPayloadResponseStore implements PayloadResponseStore {

    static final String CATALOG_SIZE_METRIC = "payloadprobe.response.catalog.size";
    static final String READS_METRIC = "payloadprobe.response.reads";
    static final String MISSES_METRIC = "payloadprobe.response.misses";
    static final String WRITES_METRIC = "payloadprobe.response.writes";

    private static final TypeReference<LinkedHashMap<String, String>> RESPONSE_MAP =
            new TypeReference<>() {
            };
    private static final String OPERATION_TAG = "operation";
    private static final String OUTCOME_TAG = "outcome";
    private static final String SUCCESS_OUTCOME = "success";
    private static final String REJECTED_OUTCOME = "rejected";

    private final ObjectMapper objectMapper;
    private final PayloadProbeProperties properties;
    private final Counter reads;
    private final Counter misses;
    private final Map<WriteOperation, Counter> successfulWrites;
    private final Map<WriteOperation, Counter> rejectedWrites;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Path storePath;
    private LinkedHashMap<String, String> cache = new LinkedHashMap<>();

    public JsonPayloadResponseStore(
            ObjectMapper objectMapper,
            PayloadProbeProperties properties,
            MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.properties = properties;
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
        if (Files.notExists(storePath)) {
            seedStore();
        }
        cache = readStore();
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
            response = Optional.ofNullable(cache.get(key));
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
    public boolean create(String key, String xmlInput) {
        boolean created;
        lock.writeLock().lock();
        try {
            if (cache.containsKey(key)) {
                created = false;
            } else {
                cache.put(key, xmlInput);
                writeStore(cache);
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
            if (!cache.containsKey(key)) {
                updated = false;
            } else {
                cache.put(key, xmlInput);
                writeStore(cache);
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
                cache.remove(key);
                writeStore(cache);
                deleted = true;
            }
        } finally {
            lock.writeLock().unlock();
        }
        recordWrite(WriteOperation.DELETE, deleted);
        return deleted;
    }

    private static Map<WriteOperation, Counter> registerWriteCounters(
            MeterRegistry meterRegistry,
            String outcome) {
        EnumMap<WriteOperation, Counter> counters = new EnumMap<>(WriteOperation.class);
        for (WriteOperation operation : WriteOperation.values()) {
            counters.put(operation, Counter.builder(WRITES_METRIC)
                    .description("Completed create, update, or delete attempts.")
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

    private LinkedHashMap<String, String> readStore() throws IOException {
        if (Files.size(storePath) == 0) {
            return new LinkedHashMap<>();
        }
        return objectMapper.readValue(storePath.toFile(), RESPONSE_MAP);
    }

    private void writeStore(Map<String, String> responses) {
        try {
            objectMapper.writeValue(storePath.toFile(), responses);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist XML response store at " + storePath, e);
        }
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
