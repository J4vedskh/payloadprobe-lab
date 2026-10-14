package com.javed.payloadprobe.api;

public final class PayloadScenarioException extends RuntimeException {

    private final Reason reason;

    public PayloadScenarioException(Reason reason) {
        this(reason, null);
    }

    public PayloadScenarioException(Reason reason, Throwable cause) {
        super(null, cause, false, false);
        this.reason = reason;
    }

    Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_DOCUMENT,
        TOO_LARGE,
        NOT_FOUND,
        UNAVAILABLE_RESPONSE,
        UNRESOLVABLE,
        LIMIT_REACHED,
        PERSISTENCE_FAILURE
    }
}
