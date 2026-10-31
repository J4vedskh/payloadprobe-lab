package com.javed.payloadprobe.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ApiHelpOpenApiContractTests {

    @Test
    void documentsThePublicHelpCatalogResponseShape() throws IOException {
        Map<String, Object> api = parseContract();
        Map<String, Object> helpOperation = map(map(map(api, "paths"), "/help"), "get");

        Map<String, Object> successResponse = map(map(helpOperation, "responses"), "200");
        Map<String, Object> content = map(successResponse, "content");
        assertThat(content).containsOnlyKeys("application/json");
        assertThat(map(map(content, "application/json"), "schema"))
                .containsEntry("$ref", "#/components/schemas/ApiHelpResponse");

        Map<String, Object> schemas = map(map(api, "components"), "schemas");
        assertHelpResponseSchema(schemas);
        assertEndpointSchema(schemas);
    }

    private static void assertHelpResponseSchema(Map<String, Object> schemas) {
        Map<String, Object> schema = map(schemas, "ApiHelpResponse");
        assertThat(schema)
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat(list(schema, "required")).containsExactly("endpoints");

        Map<String, Object> properties = map(schema, "properties");
        assertThat(properties).containsOnlyKeys("endpoints");
        Map<String, Object> endpoints = map(properties, "endpoints");
        assertThat(endpoints)
                .containsEntry("type", "array")
                .containsEntry("minItems", 1);
        assertThat(map(endpoints, "items"))
                .containsEntry("$ref", "#/components/schemas/ApiEndpoint");
    }

    private static void assertEndpointSchema(Map<String, Object> schemas) {
        Map<String, Object> schema = map(schemas, "ApiEndpoint");
        assertThat(schema)
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat(list(schema, "required"))
                .containsExactly("method", "path", "description");

        Map<String, Object> properties = map(schema, "properties");
        assertThat(properties).containsOnlyKeys("method", "path", "description");
        assertThat(map(properties, "method")).containsEntry("type", "string");
        assertThat(list(map(properties, "method"), "enum"))
                .containsExactly("GET", "POST", "PUT", "DELETE");
        assertThat(map(properties, "path"))
                .containsEntry("type", "string")
                .containsEntry("minLength", 1);
        assertThat(map(properties, "description"))
                .containsEntry("type", "string")
                .containsEntry("minLength", 1);
    }

    private static Map<String, Object> parseContract() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (Reader reader = Files.newBufferedReader(contractPath(), StandardCharsets.UTF_8)) {
            Object document = new Yaml(new SafeConstructor(options)).load(reader);
            return map(document);
        }
    }

    private static Path contractPath() {
        Path directory = Path.of("").toAbsolutePath().normalize();
        while (directory != null) {
            Path contract = directory.resolve("docs/api/openapi.yaml");
            if (Files.isRegularFile(directory.resolve("pom.xml"))
                    && Files.isRegularFile(contract)) {
                return contract;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Could not locate docs/api/openapi.yaml");
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return map(parent.get(key));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertThat(value).isInstanceOf(List.class);
        return (List<Object>) value;
    }
}
