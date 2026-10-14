package com.javed.payloadprobe.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PayloadScenariosController.class)
class PayloadScenarioExceptionHandler {

    static final String INVALID_DOCUMENT_MESSAGE = "Invalid scenario document.";
    static final String TOO_LARGE_MESSAGE = "Scenario document exceeds the configured size limit.";
    static final String NOT_FOUND_MESSAGE = "No scenario found.";
    static final String UNAVAILABLE_RESPONSE_MESSAGE = "Scenario references an unavailable response.";
    static final String UNRESOLVABLE_MESSAGE =
            "Scenario exceeds the configured response-set export limit.";
    static final String LIMIT_REACHED_MESSAGE = "Scenario catalog limit reached.";
    static final String PERSISTENCE_FAILURE_MESSAGE = "Scenario could not be persisted.";
    static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE = "Unsupported Content-Type. Use application/json.";

    @ExceptionHandler(PayloadScenarioException.class)
    ResponseEntity<MessageResponse> handleScenarioFailure(PayloadScenarioException exception) {
        return switch (exception.reason()) {
            case INVALID_DOCUMENT -> messageResponse(HttpStatus.BAD_REQUEST, INVALID_DOCUMENT_MESSAGE);
            case TOO_LARGE -> messageResponse(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE_MESSAGE);
            case NOT_FOUND -> messageResponse(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
            case UNAVAILABLE_RESPONSE -> messageResponse(HttpStatus.CONFLICT, UNAVAILABLE_RESPONSE_MESSAGE);
            case UNRESOLVABLE -> messageResponse(HttpStatus.CONFLICT, UNRESOLVABLE_MESSAGE);
            case LIMIT_REACHED -> messageResponse(HttpStatus.CONFLICT, LIMIT_REACHED_MESSAGE);
            case PERSISTENCE_FAILURE -> messageResponse(HttpStatus.SERVICE_UNAVAILABLE, PERSISTENCE_FAILURE_MESSAGE);
        };
    }

    private static ResponseEntity<MessageResponse> messageResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MessageResponse(message));
    }
}
