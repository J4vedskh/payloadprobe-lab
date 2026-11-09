package com.javed.payloadprobe.config;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.SerializationFeature;

public final class PayloadJsonMappers {

    private PayloadJsonMappers() {
    }

    public static <T> StrictDocumentReader<T> strictDocumentReader(
            ObjectMapper baseMapper,
            Class<T> documentType,
            int maxNestingDepth,
            int maxStringLength) {
        if (!(baseMapper.tokenStreamFactory() instanceof JsonFactory baseFactory)) {
            throw new IllegalArgumentException("PayloadProbe requires a JSON token stream factory.");
        }

        JsonFactory strictFactory = baseFactory.rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(maxNestingDepth)
                        .maxStringLength(maxStringLength)
                        .build())
                .build();
        ObjectReader strictReader = baseMapper.readerFor(documentType)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        return new StrictDocumentReader<>(strictFactory, strictReader);
    }

    public static ObjectMapper durableStoreMapper(ObjectMapper baseMapper) {
        return baseMapper.rebuild()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
    }

    public static final class StrictDocumentReader<T> {

        private final JsonFactory jsonFactory;
        private final ObjectReader objectReader;

        private StrictDocumentReader(JsonFactory jsonFactory, ObjectReader objectReader) {
            this.jsonFactory = jsonFactory;
            this.objectReader = objectReader;
        }

        public T readValue(byte[] documentBytes) throws JacksonException {
            try (JsonParser parser = jsonFactory.createParser(documentBytes)) {
                return objectReader.readValue(parser);
            }
        }
    }
}
