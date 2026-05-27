package com.javed.payloadprobe.api;

import java.util.List;

public record PayloadCatalogResponse(int count, List<String> keys) {
}
