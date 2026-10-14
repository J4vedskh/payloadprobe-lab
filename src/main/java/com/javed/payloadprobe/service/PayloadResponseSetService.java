package com.javed.payloadprobe.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.javed.payloadprobe.api.InvalidXmlPayloadException;
import com.javed.payloadprobe.api.PayloadResponseSetDocument;
import com.javed.payloadprobe.api.PayloadResponseSetEntry;
import com.javed.payloadprobe.api.PayloadResponseSetException;
import com.javed.payloadprobe.api.XmlPayloadValidator;
import com.javed.payloadprobe.store.PayloadResponseStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PayloadResponseSetService {

    static final int DOCUMENT_VERSION = 1;
    public static final String WRITES_METRIC = "payloadprobe.response.writes";

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,120}");

    private final PayloadResponseStore store;
    private final XmlPayloadValidator xmlPayloadValidator;
    private final ObjectReader documentReader;
    private final ObjectWriter documentWriter;
    private final int maxEntries;
    private final int maxDocumentBytes;
    private final Counter successfulImports;
    private final Counter rejectedImports;

    public PayloadResponseSetService(
            PayloadResponseStore store,
            XmlPayloadValidator xmlPayloadValidator,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${payloadprobe.response-sets.max-entries:100}") int maxEntries,
            @Value("${payloadprobe.response-sets.max-document-bytes:1048576}") int maxDocumentBytes) {
        if (maxEntries < 1 || maxDocumentBytes < 1) {
            throw new IllegalArgumentException("Response-set limits must be greater than zero");
        }
        this.store = store;
        this.xmlPayloadValidator = xmlPayloadValidator;
        this.maxEntries = maxEntries;
        this.maxDocumentBytes = maxDocumentBytes;

        ObjectMapper strictObjectMapper = objectMapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strictObjectMapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(20)
                .maxStringLength(maxDocumentBytes)
                .build());
        this.documentReader = strictObjectMapper.readerFor(PayloadResponseSetDocument.class);
        this.documentWriter = strictObjectMapper.writerFor(PayloadResponseSetDocument.class);
        this.successfulImports = importCounter(meterRegistry, "success");
        this.rejectedImports = importCounter(meterRegistry, "rejected");
    }

    public PayloadResponseSetDocument exportResponses() {
        List<PayloadResponseSetEntry> responses = store.snapshot().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> new PayloadResponseSetEntry(entry.getKey(), entry.getValue()))
                .toList();
        return documentForEntries(responses);
    }

    PayloadResponseSetDocument documentForEntries(List<PayloadResponseSetEntry> responses) {
        PayloadResponseSetDocument document = new PayloadResponseSetDocument(DOCUMENT_VERSION, responses);
        if (responses.size() > maxEntries || serializedSize(document) > maxDocumentBytes) {
            throw new PayloadResponseSetException(PayloadResponseSetException.Reason.EXPORT_TOO_LARGE);
        }
        return document;
    }

    public int importResponses(InputStream documentStream) {
        try {
            PayloadResponseSetDocument document = readDocument(documentStream);
            LinkedHashMap<String, String> responses = validateDocument(document);
            if (!store.createAllIfAbsent(responses)) {
                throw new PayloadResponseSetException(PayloadResponseSetException.Reason.CONFLICT);
            }
            successfulImports.increment();
            return responses.size();
        } catch (PayloadResponseSetException exception) {
            rejectedImports.increment();
            throw exception;
        } catch (IllegalStateException exception) {
            rejectedImports.increment();
            throw new PayloadResponseSetException(
                    PayloadResponseSetException.Reason.PERSISTENCE_FAILURE,
                    exception);
        }
    }

    private PayloadResponseSetDocument readDocument(InputStream documentStream) {
        if (documentStream == null) {
            throw new PayloadResponseSetException(PayloadResponseSetException.Reason.INVALID_DOCUMENT);
        }
        try {
            byte[] documentBytes = readBounded(documentStream);
            if (documentBytes.length == 0) {
                throw new PayloadResponseSetException(PayloadResponseSetException.Reason.INVALID_DOCUMENT);
            }
            return documentReader.readValue(documentBytes);
        } catch (JsonProcessingException exception) {
            throw new PayloadResponseSetException(
                    PayloadResponseSetException.Reason.INVALID_DOCUMENT,
                    exception);
        } catch (IOException exception) {
            throw new PayloadResponseSetException(
                    PayloadResponseSetException.Reason.INVALID_DOCUMENT,
                    exception);
        }
    }

    private byte[] readBounded(InputStream documentStream) throws IOException {
        ByteArrayOutputStream documentBytes = new ByteArrayOutputStream(Math.min(maxDocumentBytes, 8192));
        byte[] buffer = new byte[8192];
        int totalBytes = 0;
        int bytesRead;
        while ((bytesRead = documentStream.read(buffer)) != -1) {
            if (bytesRead > maxDocumentBytes - totalBytes) {
                throw new PayloadResponseSetException(PayloadResponseSetException.Reason.TOO_LARGE);
            }
            documentBytes.write(buffer, 0, bytesRead);
            totalBytes += bytesRead;
        }
        return documentBytes.toByteArray();
    }

    private LinkedHashMap<String, String> validateDocument(PayloadResponseSetDocument document) {
        if (document == null
                || document.version() != DOCUMENT_VERSION
                || document.responses() == null
                || document.responses().isEmpty()
                || document.responses().size() > maxEntries) {
            throw new PayloadResponseSetException(PayloadResponseSetException.Reason.INVALID_DOCUMENT);
        }

        LinkedHashMap<String, String> validatedResponses = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (PayloadResponseSetEntry entry : document.responses()) {
            if (entry == null
                    || entry.key() == null
                    || !KEY_PATTERN.matcher(entry.key()).matches()
                    || !keys.add(entry.key())) {
                throw new PayloadResponseSetException(PayloadResponseSetException.Reason.INVALID_DOCUMENT);
            }
            try {
                validatedResponses.put(entry.key(), xmlPayloadValidator.validate(entry.xml()));
            } catch (InvalidXmlPayloadException exception) {
                throw new PayloadResponseSetException(PayloadResponseSetException.Reason.INVALID_DOCUMENT);
            }
        }
        return validatedResponses;
    }

    private int serializedSize(PayloadResponseSetDocument document) {
        try {
            return documentWriter.writeValueAsBytes(document).length;
        } catch (JsonProcessingException exception) {
            throw new PayloadResponseSetException(
                    PayloadResponseSetException.Reason.EXPORT_TOO_LARGE,
                    exception);
        }
    }

    private static Counter importCounter(MeterRegistry meterRegistry, String outcome) {
        return Counter.builder(WRITES_METRIC)
                .description("Completed response mutation attempts.")
                .tag("operation", "import")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }
}
