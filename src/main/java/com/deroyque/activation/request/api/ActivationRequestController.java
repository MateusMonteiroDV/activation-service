package com.deroyque.activation.request.api;

import com.deroyque.activation.request.application.ActivationRequestService;
import com.deroyque.activation.request.application.CreateActivationCommand;
import com.deroyque.activation.request.application.CreateOutcome;
import com.deroyque.activation.request.domain.RequestStatus;
import com.deroyque.activation.shared.security.ClientIdentity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /** 201 quando cria; 200 com o registro existente quando é retry com o mesmo conteúdo. */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
                                                            @AuthenticationPrincipal Jwt jwt) {
        var command = new CreateActivationCommand(body.externalId(), body.customerId(), body.productCode());
        var outcome = service.create(command, ClientIdentity.from(jwt));
        var response = ActivationRequestResponse.from(outcome.request());

        return switch (outcome) {
            case CreateOutcome.Created created -> ResponseEntity
                    .created(ServletUriComponentsBuilder.fromCurrentRequest()
                            .path("/{id}").buildAndExpand(response.id()).toUri())
                    .body(response);
            case CreateOutcome.AlreadyExists existing -> ResponseEntity.ok(response);
        };
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse findById(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return ActivationRequestResponse.from(service.findById(id, ClientIdentity.from(jwt)));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> list(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt jwt) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var result = service.list(status, customerId, ClientIdentity.from(jwt), pageable);
        return PageResponse.from(result, ActivationRequestResponse::from);
    }
}
