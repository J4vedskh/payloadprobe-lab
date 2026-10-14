package com.javed.payloadprobe.store;

import java.util.List;

public record PayloadScenario(String name, List<String> responseKeys) {

    public PayloadScenario {
        responseKeys = List.copyOf(responseKeys);
    }
}
