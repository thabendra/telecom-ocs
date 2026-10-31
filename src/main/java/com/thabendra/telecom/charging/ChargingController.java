package com.thabendra.telecom.charging;

import com.thabendra.telecom.usage.UsageService.App;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST adapter of the online charging API called by the network gateway (CH-1..CH-3).
 * A DENY is a normal answer (200/201 with result DENY), not an error: the gateway blocks the traffic.
 */
@RestController
@RequestMapping("/charging/v1/sessions")
public class ChargingController implements ChargingApi<ChargingController.StartRequest, ChargingService.Grant,
        ChargingController.UpdateRequest, ChargingService.Grant,
        ChargingController.ReleaseRequest, ChargingService.Release> {

    private static final long MAX_CHUNK_MB = 10_240;

    private final ChargingService service;

    public ChargingController(ChargingService service) {
        this.service = service;
    }

    public record StartRequest(@NotNull @Pattern(regexp = "947\\d{8}") String msisdn,
                               @NotNull App app,
                               @Min(1) @Max(MAX_CHUNK_MB) long requestedMb) {
    }

    /** requestNumber: the gateway's counter for this session (start is 0, then 1, 2, ...). */
    public record UpdateRequest(@Min(1) long requestNumber, @Min(0) long usedMb,
                                @Min(0) @Max(MAX_CHUNK_MB) long requestedMb) {
    }

    public record ReleaseRequest(@Min(1) long requestNumber, @Min(0) long usedMb) {
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChargingService.Grant startSession(@Valid @RequestBody StartRequest request) {
        return service.startSession(request.msisdn(), request.app(), request.requestedMb());
    }

    @Override
    @PostMapping("/{sessionId}/update")
    public ChargingService.Grant updateSession(@PathVariable String sessionId,
                                               @Valid @RequestBody UpdateRequest request) {
        return service.updateSession(sessionId, request.requestNumber(), request.usedMb(), request.requestedMb());
    }

    @Override
    @PostMapping("/{sessionId}/release")
    public ChargingService.Release releaseSession(@PathVariable String sessionId,
                                                  @Valid @RequestBody ReleaseRequest request) {
        return service.releaseSession(sessionId, request.requestNumber(), request.usedMb());
    }
}
