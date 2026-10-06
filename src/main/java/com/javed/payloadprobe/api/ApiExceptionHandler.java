package com.javed.payloadprobe.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
class ApiExceptionHandler {

    static final String INVALID_KEY_MESSAGE =
            "Invalid response key. Use 1-120 letters, numbers, dots, underscores, or hyphens.";
    static final String BLANK_PAYLOAD_MESSAGE = "XML response content must not be blank.";
    static final String MALFORMED_PAYLOAD_MESSAGE =
            "XML response content must be a well-formed XML document or fragment; "
                    + "DTDs and external entities are not allowed.";
    static final String OVERSIZED_PAYLOAD_MESSAGE =
            "XML response content exceeds the configured size limit.";
    static final String UNSUPPORTED_MEDIA_TYPE_MESSAGE =
            "Unsupported Content-Type. Use application/xml, text/xml, or text/plain.";

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    ResponseEntity<MessageResponse> handleKeyValidationFailure(Exception ignored) {
        return messageResponse(HttpStatus.BAD_REQUEST, INVALID_KEY_MESSAGE);
    }

    @ExceptionHandler(InvalidXmlPayloadException.class)
    ResponseEntity<MessageResponse> handleInvalidXmlPayload(InvalidXmlPayloadException exception) {
        String message = switch (exception.reason()) {
            case BLANK -> BLANK_PAYLOAD_MESSAGE;
            case TOO_LARGE -> OVERSIZED_PAYLOAD_MESSAGE;
            case MALFORMED -> MALFORMED_PAYLOAD_MESSAGE;
        };
        return messageResponse(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<MessageResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ignored) {
        return messageResponse(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE_MESSAGE);
    }

    private static ResponseEntity<MessageResponse> messageResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new MessageResponse(message));
    }
}
