package com.javed.payloadprobe.api;

import com.javed.payloadprobe.service.PayloadScenarioService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scenarios")
public class PayloadScenariosController {

    private final PayloadScenarioService scenarioService;

    public PayloadScenariosController(PayloadScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PayloadScenarioCatalogResponse list() {
        return scenarioService.list();
    }

    @GetMapping(value = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PayloadResponseSetDocument resolve(@PathVariable String name) {
        return scenarioService.resolve(name);
    }

    @PutMapping(value = "/{name}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PayloadScenarioSummary> put(
            @PathVariable String name,
            HttpServletRequest request) {
        PayloadScenarioService.ScenarioWriteResult result;
        try {
            result = scenarioService.put(name, request.getInputStream());
        } catch (IOException exception) {
            throw new PayloadScenarioException(PayloadScenarioException.Reason.INVALID_DOCUMENT, exception);
        }
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.scenario());
    }

    @DeleteMapping(value = "/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        scenarioService.delete(name);
        return ResponseEntity.noContent().build();
    }
}
