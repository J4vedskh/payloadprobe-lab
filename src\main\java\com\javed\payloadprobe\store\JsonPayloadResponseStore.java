package com.javed.payloadprobe.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.javed.payloadprobe.config.PayloadProbeProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;

@Repository
public class JsonPayloadResponseStore implements PayloadResponseStore {

    private static final TypeReference<LinkedHashMap<String, String>> RESPONSE_MAP =
            new TypeReference<>() {
            };

    private final ObjectMapper objectMapper;
    private final PayloadProbeProperties properties;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Path storePath;
    private LinkedHashMap<String, String> cache = new LinkedHashMap<>();

    public JsonPayloadResponseStore(ObjectMapper objectMapper, PayloadProbeProperties properties) {
        this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        this.properties = properties;
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
        lock.readLock().lock();
        try {
            return Optional.ofNullable(cache.get(key));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean create(String key, String xmlInput) {
        lock.writeLock().lock();
        try {
            if (cache.containsKey(key)) {
                return false;
            }
            cache.put(key, xmlInput);
            writeStore(cache);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean update(String key, String xmlInput) {
        lock.writeLock().lock();
        try {
            if (!cache.containsKey(key)) {
                return false;
            }
            cache.put(key, xmlInput);
            writeStore(cache);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(String key) {
        lock.writeLock().lock();
        try {
            if (!cache.containsKey(key)) {
                return false;
            }
            cache.remove(key);
            writeStore(cache);
            return true;
        } finally {
            lock.writeLock().unlock();
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
}
