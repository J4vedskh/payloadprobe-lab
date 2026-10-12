package com.javed.payloadprobe.api;

import java.util.List;

public record PayloadResponseSetDocument(int version, List<PayloadResponseSetEntry> responses) {
}
