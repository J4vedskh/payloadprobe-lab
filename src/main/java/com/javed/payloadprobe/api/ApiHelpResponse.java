package com.javed.payloadprobe.api;

import java.util.List;

public record ApiHelpResponse(List<ApiEndpoint> endpoints) {
}
