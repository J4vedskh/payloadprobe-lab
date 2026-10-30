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

class ResponseSetOpenApiContractTests {

    @Test
    void distinguishesNonEmptyImportsFromPotentiallyEmptyExports() throws IOException {
        Map<String, Object> api = parseContract();
        Map<String, Object> paths = map(api, "paths");

        Map<String, Object> responseSets = map(paths, "/api/response-sets");
        assertThat(responseSchemaReference(map(responseSets, "get"), "200"))
                .isEqualTo("#/components/schemas/ResponseSetDocument");
        assertThat(requestSchemaReference(map(responseSets, "post")))
                .isEqualTo("#/components/schemas/ResponseSetImportDocument");

        Map<String, Object> schemas = map(map(api, "components"), "schemas");
        assertResponseSetDocument(schemas, "ResponseSetDocument", false);
        assertResponseSetDocument(schemas, "ResponseSetImportDocument", true);
    }

    private static void assertResponseSetDocument(
            Map<String, Object> schemas, String schemaName, boolean requiresEntries) {
        Map<String, Object> schema = map(schemas, schemaName);
        assertThat(schema)
                .containsEntry("type", "object")
                .containsEntry("additionalProperties", false);
        assertThat(list(schema, "required")).containsExactly("version", "responses");

        Map<String, Object> properties = map(schema, "properties");
        assertThat(properties).containsOnlyKeys("version", "responses");
        assertThat(map(properties, "version"))
                .containsEntry("type", "integer");
        assertThat(list(map(properties, "version"), "enum")).containsExactly(1);

        Map<String, Object> responses = map(properties, "responses");
        assertThat(responses).containsEntry("type", "array");
        assertThat(map(responses, "items"))
                .containsEntry("$ref", "#/components/schemas/ResponseSetEntry");
        if (requiresEntries) {
            assertThat(responses).containsEntry("minItems", 1);
        } else {
            assertThat(responses).doesNotContainKey("minItems");
        }
    }

    private static String requestSchemaReference(Map<String, Object> operation) {
        Map<String, Object> requestBody = map(operation, "requestBody");
        assertThat(requestBody).containsEntry("required", true);
        Map<String, Object> content = map(requestBody, "content");
        assertThat(content).containsOnlyKeys("application/json");
        return String.valueOf(map(map(content, "application/json"), "schema").get("$ref"));
    }

    private static String responseSchemaReference(
            Map<String, Object> operation, String statusCode) {
        Map<String, Object> response = map(map(operation, "responses"), statusCode);
        Map<String, Object> content = map(response, "content");
        return String.valueOf(map(map(content, "application/json"), "schema").get("$ref"));
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
