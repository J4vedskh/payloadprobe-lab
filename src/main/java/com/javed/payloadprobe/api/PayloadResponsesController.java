package com.javed.payloadprobe.api;

import com.javed.payloadprobe.service.DefaultPayloads;
import com.javed.payloadprobe.store.PayloadResponseStore;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/responses")
public class PayloadResponsesController {

    private final PayloadResponseStore store;

    public PayloadResponsesController(PayloadResponseStore store) {
        this.store = store;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PayloadCatalogResponse listResponses() {
        List<String> keys = store.keys();
        return new PayloadCatalogResponse(keys.size(), keys);
    }

    @GetMapping(value = "/{key}", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> fetchResponse(@PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key) {
        return store.findByKey(key)
                .map(PayloadResponsesController::xmlResponse)
                .orElseGet(() -> messageResponse(HttpStatus.NOT_FOUND, "No XML response found for key: " + key));
    }

    @PostMapping(
            value = "/{key}",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> createResponse(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key,
            @RequestBody String xmlInput) {
        boolean created = store.create(key, xmlInput);
        if (!created) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageResponse("Response already exists for key: " + key));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Response created for key: " + key));
    }

    @PutMapping(
            value = "/{key}",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> updateResponse(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key,
            @RequestBody String xmlInput) {
        boolean updated = store.update(key, xmlInput);
        if (!updated) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("No XML response found for key: " + key));
        }
        return ResponseEntity.ok(new MessageResponse("Response updated for key: " + key));
    }

    @DeleteMapping(value = "/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> deleteResponse(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key) {
        boolean deleted = store.delete(key);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("No XML response found for key: " + key));
        }
        return ResponseEntity.ok(new MessageResponse("Response deleted for key: " + key));
    }

    @PostMapping(value = "/default", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> defaultResponse() {
        return xmlResponse(DefaultPayloads.DEFAULT_XML);
    }

    private static ResponseEntity<String> xmlResponse(String xml) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }

    private static ResponseEntity<String> messageResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"message\":\"" + message.replace("\"", "\\\"") + "\"}");
    }
}
