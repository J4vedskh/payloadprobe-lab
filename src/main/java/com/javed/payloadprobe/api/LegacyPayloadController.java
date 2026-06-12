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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class LegacyPayloadController {

    private final PayloadResponseStore store;

    public LegacyPayloadController(PayloadResponseStore store) {
        this.store = store;
    }

    @GetMapping(value = "/fetch/{key}", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> fetchXmlGet(@PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key) {
        return fetchLegacyXml(key);
    }

    @PostMapping(value = "/fetch/{key}", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> fetchXmlPost(@PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key) {
        return fetchLegacyXml(key);
    }

    @PostMapping(
            value = "/add/{key}",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> addXml(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key,
            @RequestBody String xmlInput) {
        if (!store.create(key, xmlInput)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new MessageResponse("Response for the given key is already present, try update or delete commands"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MessageResponse("Response added for the SPI: " + key));
    }

    @PostMapping(
            value = "/update/{key}",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> updateXml(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key,
            @RequestBody String xmlInput) {
        if (!store.update(key, xmlInput)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("Response for the given key does not exist, try add command"));
        }
        return ResponseEntity.ok(new MessageResponse("Response updated for the SPI: " + key));
    }

    @DeleteMapping(value = "/delete/{key}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MessageResponse> deleteXml(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9._-]{1,120}") String key) {
        if (!store.delete(key)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new MessageResponse("Response for the given key does not exist, or has been deleted already"));
        }
        return ResponseEntity.ok(new MessageResponse("Response removed for the SPI: " + key));
    }

    @GetMapping(value = "/fetchAll", produces = MediaType.APPLICATION_JSON_VALUE)
    public PayloadCatalogResponse getAllSpi() {
        List<String> keys = store.keys();
        return new PayloadCatalogResponse(keys.size(), keys);
    }

    @GetMapping(value = "/default", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> defaultXmlGet() {
        return xmlResponse(DefaultPayloads.DEFAULT_XML);
    }

    @PostMapping(value = "/default", produces = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE})
    public ResponseEntity<String> defaultXmlPost() {
        return xmlResponse(DefaultPayloads.DEFAULT_XML);
    }

    @GetMapping(value = "/help", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiHelpResponse helpManual() {
        return new ApiHelpResponse(List.of(
                new ApiEndpoint("GET", "/api/responses", "List all stored XML response keys."),
                new ApiEndpoint("GET", "/api/responses/{key}", "Fetch the XML response for a key."),
                new ApiEndpoint("POST", "/api/responses/{key}", "Create a new XML response for a key."),
                new ApiEndpoint("PUT", "/api/responses/{key}", "Update an existing XML response."),
                new ApiEndpoint("DELETE", "/api/responses/{key}", "Delete an XML response."),
                new ApiEndpoint("POST", "/api/responses/default", "Return a default XML response sample."),
                new ApiEndpoint("GET", "/fetch/{key}", "Legacy alias for fetching XML by key."),
                new ApiEndpoint("POST", "/add/{key}", "Legacy alias for adding XML by key."),
                new ApiEndpoint("POST", "/update/{key}", "Legacy alias for updating XML by key."),
                new ApiEndpoint("DELETE", "/delete/{key}", "Legacy alias for deleting XML by key."),
                new ApiEndpoint("GET", "/fetchAll", "Legacy alias for listing response keys."),
                new ApiEndpoint("GET", "/help", "List supported endpoints.")));
    }

    private ResponseEntity<String> fetchLegacyXml(String key) {
        return store.findByKey(key)
                .map(LegacyPayloadController::xmlResponse)
                .orElseGet(() -> xmlResponse(DefaultPayloads.notFoundXml()));
    }

    private static ResponseEntity<String> xmlResponse(String xml) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .body(xml);
    }
}
