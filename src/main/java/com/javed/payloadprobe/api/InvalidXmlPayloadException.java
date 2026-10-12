package com.javed.payloadprobe.api;

public final class InvalidXmlPayloadException extends RuntimeException {

    private final Reason reason;

    InvalidXmlPayloadException(Reason reason) {
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    enum Reason {
        BLANK,
        TOO_LARGE,
        MALFORMED
    }
}
