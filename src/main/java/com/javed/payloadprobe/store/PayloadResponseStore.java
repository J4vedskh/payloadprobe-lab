package com.javed.payloadprobe.store;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PayloadResponseStore {

    List<String> keys();

    Optional<String> findByKey(String key);

    Map<String, String> snapshot();

    boolean create(String key, String xmlInput);

    boolean update(String key, String xmlInput);

    boolean delete(String key);

    boolean createAllIfAbsent(Map<String, String> responses);
}
