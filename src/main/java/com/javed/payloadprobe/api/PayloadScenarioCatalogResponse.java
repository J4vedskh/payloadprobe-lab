package com.javed.payloadprobe.api;

import java.util.List;

public record PayloadScenarioCatalogResponse(int count, List<PayloadScenarioSummary> scenarios) {
}
