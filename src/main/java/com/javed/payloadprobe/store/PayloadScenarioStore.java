package com.javed.payloadprobe.store;

import java.util.List;
import java.util.Optional;

public interface PayloadScenarioStore {

    List<PayloadScenario> list();

    Optional<PayloadScenario> findByName(String name);

    PutResult put(String name, List<String> responseKeys);

    boolean delete(String name);

    enum PutResult {
        CREATED,
        REPLACED,
        LIMIT_REACHED
    }
}
