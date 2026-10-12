package com.javed.payloadprobe.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PayloadResponseSetsController.class)
class PayloadResponseSetExceptionHandler {

    static final String INVALID_DOCUMENT_MESSAGE = "Invalid response-set document.";
    static final String TOO_LARGE_MESSAGE = "Response-set document exceeds the configured size limit.";
    static final String EXPORT_TOO_LARGE_MESSAGE =
            "Current response catalog exceeds the configured response-set export limit.";
    static final String CONFLICT_MESSAGE = "Response-set import conflicts with existing response keys.";
    static final String PERSISTENCE_FAILURE_MESSAGE = "Response-set import could not be persisted.";
    static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Unsupported Content-Type. Use application/json.";

    @ExceptionHandler(PayloadResponseSetException.class)
    ResponseEntity<MessageResponse> handleResponseSetFailure(PayloadResponseSetException exception) {
        return switch (exception.reason()) {
            case INVALID_DOCUMENT -> messageResponse(HttpStatus.BAD_REQUEST, INVALID_DOCUMENT_MESSAGE);
            case TOO_LARGE -> messageResponse(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE_MESSAGE);
            case EXPORT_TOO_LARGE -> messageResponse(HttpStatus.CONFLICT, EXPORT_TOO_LARGE_MESSAGE);
            case CONFLICT -> messageResponse(HttpStatus.CONFLICT, CONFLICT_MESSAGE);
            case PERSISTENCE_FAILURE -> messageResponse(HttpStatus.SERVICE_UNAVAILABLE, PERSISTENCE_FAILURE_MESSAGE);
        };
    }

    private static ResponseEntity<MessageResponse> messageResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MessageResponse(message));
    }
}
