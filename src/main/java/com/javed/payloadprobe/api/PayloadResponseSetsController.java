package com.javed.payloadprobe.api;

import com.javed.payloadprobe.service.PayloadResponseSetService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/response-sets")
public class PayloadResponseSetsController {

    private final PayloadResponseSetService responseSetService;

    public PayloadResponseSetsController(PayloadResponseSetService responseSetService) {
        this.responseSetService = responseSetService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public PayloadResponseSetDocument exportResponses() {
        return responseSetService.exportResponses();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PayloadResponseSetImportResponse> importResponses(
            HttpServletRequest request) {
        int imported;
        try {
            imported = responseSetService.importResponses(request.getInputStream());
        } catch (IOException exception) {
            throw new PayloadResponseSetException(
                    PayloadResponseSetException.Reason.INVALID_DOCUMENT,
                    exception);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new PayloadResponseSetImportResponse(imported));
    }
}
