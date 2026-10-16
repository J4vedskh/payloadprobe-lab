package com.javed.payloadprobe.api;

import java.util.List;

public record PayloadResponseVersionHistory(
        String key,
        long currentRevision,
        int retainedCount,
        List<PayloadResponseVersionSummary> versions) {
}
