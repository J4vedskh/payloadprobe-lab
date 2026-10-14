package com.javed.payloadprobe.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.javed.payloadprobe.api.PayloadResponseSetDocument;
import com.javed.payloadprobe.api.PayloadResponseSetEntry;
import com.javed.payloadprobe.api.PayloadResponseSetException;
import com.javed.payloadprobe.api.PayloadScenarioCatalogResponse;
import com.javed.payloadprobe.api.PayloadScenarioException;
import com.javed.payloadprobe.api.PayloadScenarioRequest;
import com.javed.payloadprobe.api.PayloadScenarioSummary;
import com.javed.payloadprobe.config.PayloadScenarioProperties;
import com.javed.payloadprobe.store.PayloadResponseStore;
import com.javed.payloadprobe.store.PayloadScenario;
import com.javed.payloadprobe.store.PayloadScenarioStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class PayloadScenarioService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,120}");

    private final PayloadScenarioStore scenarioStore;
    private final PayloadResponseStore responseStore;
    private final PayloadResponseSetService responseSetService;
    private final PayloadScenarioProperties properties;
    private final ObjectReader requestReader;

    public PayloadScenarioService(
            PayloadScenarioStore scenarioStore,
            PayloadResponseStore responseStore,
            PayloadResponseSetService responseSetService,
            PayloadScenarioProperties properties,
            ObjectMapper objectMapper) {
        this.scenarioStore = scenarioStore;
        this.responseStore = responseStore;
        this.responseSetService = responseSetService;
        this.properties = properties;
        ObjectMapper strictObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strictObjectMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(10)
                .maxStringLength(properties.getMaxDocumentBytes())
                .build());
        this.requestReader = strictObjectMapper.readerFor(PayloadScenarioRequest.class);
    }

    public PayloadScenarioCatalogResponse list() {
        List<PayloadScenarioSummary> scenarios = scenarioStore.list().stream()
                .map(scenario -> new PayloadScenarioSummary(
                        scenario.name(), scenario.responseKeys().size()))
                .toList();
        return new PayloadScenarioCatalogResponse(scenarios.size(), scenarios);
    }

    public PayloadResponseSetDocument resolve(String name) {
        validateName(name);
        PayloadScenario scenario = scenarioStore.findByName(name)
                .orElseThrow(() -> new PayloadScenarioException(PayloadScenarioException.Reason.NOT_FOUND));
        Map<String, String> responses = responseStore.snapshot();
        List<PayloadResponseSetEntry> entries = scenario.responseKeys().stream()
                .map(key -> {
                    String xml = responses.get(key);
                    if (xml == null) {
                        throw new PayloadScenarioException(PayloadScenarioException.Reason.UNAVAILABLE_RESPONSE);
                    }
                    return new PayloadResponseSetEntry(key, xml);
                })
                .toList();
        try {
            return responseSetService.documentForEntries(entries);
        } catch (PayloadResponseSetException exception) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.UNRESOLVABLE);
        }
    }

    public ScenarioWriteResult put(String name, InputStream documentStream) {
        validateName(name);
        PayloadScenarioRequest request = readRequest(documentStream);
        List<String> responseKeys = validateResponseKeys(request);
        Map<String, String> responses = responseStore.snapshot();
        if (responseKeys.stream().anyMatch(key -> !responses.containsKey(key))) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.UNAVAILABLE_RESPONSE);
        }
        try {
            PayloadScenarioStore.PutResult result = scenarioStore.put(name, responseKeys);
            if (result == PayloadScenarioStore.PutResult.LIMIT_REACHED) {
                throw new PayloadScenarioException(PayloadScenarioException.Reason.LIMIT_REACHED);
            }
            PayloadScenarioSummary summary = new PayloadScenarioSummary(name, responseKeys.size());
            return new ScenarioWriteResult(summary, result == PayloadScenarioStore.PutResult.CREATED);
        } catch (PayloadScenarioException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            throw new PayloadScenarioException(
                    PayloadScenarioException.Reason.PERSISTENCE_FAILURE,
                    exception);
        }
    }

    public void delete(String name) {
        validateName(name);
        try {
            if (!scenarioStore.delete(name)) {
                throw new PayloadScenarioException(PayloadScenarioException.Reason.NOT_FOUND);
            }
        } catch (PayloadScenarioException exception) {
            throw exception;
        } catch (IllegalStateException exception) {
            throw new PayloadScenarioException(
                    PayloadScenarioException.Reason.PERSISTENCE_FAILURE,
                    exception);
        }
    }

    private PayloadScenarioRequest readRequest(InputStream documentStream) {
        if (documentStream == null) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT);
        }
        try {
            byte[] documentBytes = readBounded(documentStream);
            if (documentBytes.length == 0) {
                throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT);
            }
            return requestReader.readValue(documentBytes);
        } catch (PayloadScenarioException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT, exception);
        } catch (IOException exception) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT, exception);
        }
    }

    private byte[] readBounded(InputStream documentStream) throws IOException {
        ByteArrayOutputStream documentBytes =
                new ByteArrayOutputStream(Math.min(properties.getMaxDocumentBytes(), 8192));
        byte[] buffer = new byte[8192];
        int totalBytes = 0;
        int bytesRead;
        while ((bytesRead = documentStream.read(buffer)) != -1) {
            if (bytesRead > properties.getMaxDocumentBytes() - totalBytes) {
                throw new PayloadScenarioException(PayloadScenarioException.Reason.TOO_LARGE);
            }
            documentBytes.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }
        return documentBytes.toByteArray();
    }

    private List<String> validateResponseKeys(PayloadScenarioRequest request) {
        if (request == null
                || request.responseKeys() == null
                || request.responseKeys().isEmpty()
                || request.responseKeys().size() > properties.getMaxResponseKeys()) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT);
        }
        Set<String> uniqueKeys = new LinkedHashSet<>();
        for (String key : request.responseKeys()) {
            if (key == null || !NAME_PATTERN.matcher(key).matches() || !uniqueKeys.add(key)) {
                throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT);
            }
        }
        return List.copyOf(uniqueKeys);
    }

    private void validateName(String name) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT);
        }
    }

    public record ScenarioWriteResult(PayloadScenarioSummary scenario, boolean created) {
    }
}
