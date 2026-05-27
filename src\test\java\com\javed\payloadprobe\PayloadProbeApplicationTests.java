package com.javed.payloadprobe;

import static org.assertj.core.api.Assertions.assertThat;

import com.javed.payloadprobe.api.MessageResponse;
import com.javed.payloadprobe.api.PayloadCatalogResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "payloadprobe.store.path=target/test-data/xmlResponses-${random.uuid}.json")
class PayloadProbeApplicationTests {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void fetchesSeededXmlResponse() {
        ResponseEntity<String> response = restTemplate.getForEntity(url("/api/responses/openTest"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_XML);
        assertThat(response.getBody()).contains("<Open>15</Open>");
    }

    @Test
    void createsListsUpdatesAndDeletesXmlResponse() {
        String key = "demo-" + UUID.randomUUID();
        String initialXml = "<response><status>created</status></response>";
        String updatedXml = "<response><status>updated</status></response>";

        ResponseEntity<MessageResponse> created = restTemplate.exchange(
                url("/api/responses/" + key),
                HttpMethod.POST,
                xmlEntity(initialXml),
                MessageResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<MessageResponse> duplicate = restTemplate.exchange(
                url("/api/responses/" + key),
                HttpMethod.POST,
                xmlEntity(initialXml),
                MessageResponse.class);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<PayloadCatalogResponse> catalog = restTemplate.exchange(
                url("/api/responses"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                });
        assertThat(catalog.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalog.getBody().keys()).contains(key);

        ResponseEntity<MessageResponse> updated = restTemplate.exchange(
                url("/api/responses/" + key),
                HttpMethod.PUT,
                xmlEntity(updatedXml),
                MessageResponse.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> fetched = restTemplate.getForEntity(url("/api/responses/" + key), String.class);
        assertThat(fetched.getBody()).contains("updated");

        ResponseEntity<MessageResponse> deleted = restTemplate.exchange(
                url("/api/responses/" + key),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                MessageResponse.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> missing = restTemplate.getForEntity(url("/api/responses/" + key), String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void exposesDefaultXmlAndHealth() {
        ResponseEntity<String> defaultXml = restTemplate.exchange(
                url("/api/responses/default"),
                HttpMethod.POST,
                HttpEntity.EMPTY,
                String.class);
        assertThat(defaultXml.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(defaultXml.getBody()).contains("Default XML response");

        ResponseEntity<String> health = restTemplate.getForEntity(url("/actuator/health"), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("UP");
    }

    @Test
    void supportsLegacyAliases() {
        String key = "legacy-" + UUID.randomUUID();
        String xml = "<legacy><status>ok</status></legacy>";

        ResponseEntity<MessageResponse> added = restTemplate.exchange(
                url("/add/" + key),
                HttpMethod.POST,
                xmlEntity(xml),
                MessageResponse.class);
        assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> fetched = restTemplate.getForEntity(url("/fetch/" + key), String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).contains("<legacy>");

        ResponseEntity<PayloadCatalogResponse> all = restTemplate.exchange(
                url("/fetchAll"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                new ParameterizedTypeReference<>() {
                });
        assertThat(all.getBody().keys()).contains(key);

        ResponseEntity<String> help = restTemplate.getForEntity(url("/help"), String.class);
        assertThat(help.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(help.getBody()).contains("/api/responses");
    }

    private HttpEntity<String> xmlEntity(String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        return new HttpEntity<>(xml, headers);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
