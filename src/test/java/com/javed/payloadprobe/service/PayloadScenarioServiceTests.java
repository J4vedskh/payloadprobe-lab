package com.javed.payloadprobe.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javed.payloadprobe.api.PayloadScenarioException;
import com.javed.payloadprobe.config.PayloadScenarioProperties;
import com.javed.payloadprobe.store.PayloadResponseStore;
import com.javed.payloadprobe.store.PayloadScenario;
import com.javed.payloadprobe.store.PayloadScenarioStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class PayloadScenarioServiceTests {

    @Test
    void putUsesOneValidationSnapshotAndResolutionRechecksForLaterDeletion() {
        PayloadScenarioStore scenarioStore = mock(PayloadScenarioStore.class);
        PayloadResponseStore responseStore = mock(PayloadResponseStore.class);
        PayloadResponseSetService responseSetService = mock(PayloadResponseSetService.class);
        PayloadScenarioProperties properties = new PayloadScenarioProperties();
        PayloadScenarioService service = new PayloadScenarioService(
                scenarioStore,
                responseStore,
                responseSetService,
                properties,
                new ObjectMapper());

        when(responseStore.snapshot())
                .thenReturn(Map.of("openTest", "<Open>15</Open>"))
                .thenReturn(Map.of());
        when(scenarioStore.put("race", List.of("openTest")))
                .thenReturn(PayloadScenarioStore.PutResult.CREATED);
        when(scenarioStore.findByName("race"))
                .thenReturn(Optional.of(new PayloadScenario("race", List.of("openTest"))));

        PayloadScenarioService.ScenarioWriteResult result = service.put(
                "race",
                new ByteArrayInputStream(
                        "{\"responseKeys\":[\"openTest\"]}".getBytes(StandardCharsets.UTF_8)));

        assertThat(result.created()).isTrue();
        InOrder writeOrder = inOrder(responseStore, scenarioStore);
        writeOrder.verify(responseStore).snapshot();
        writeOrder.verify(scenarioStore).put("race", List.of("openTest"));

        assertThatThrownBy(() -> service.resolve("race"))
                .isInstanceOf(PayloadScenarioException.class);
    }
}
