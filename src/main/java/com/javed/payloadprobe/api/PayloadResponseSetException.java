package com.javed.payloadprobe.api;

public final class PayloadResponseSetException extends RuntimeException {

    private final Reason reason;

    public PayloadResponseSetException(Reason reason) {
        this(reason, null);
    }

    public PayloadResponseSetException(Reason reason, Throwable cause) {
        super(null, cause, false, false);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_DOCUMENT,
        TOO_LARGE,
        EXPORT_TOO_LARGE,
        CONFLICT,
        PERSISTENCE_FAILURE
    }
}
