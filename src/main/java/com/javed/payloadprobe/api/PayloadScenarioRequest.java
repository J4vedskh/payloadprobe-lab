package com.javed.payloadprobe.api;

import java.util.List;

public record PayloadScenarioRequest(List<String> responseKeys) {
}
