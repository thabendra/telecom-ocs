package com.thabendra.telecom.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.thabendra.telecom.cache.CacheKeys;
import com.thabendra.telecom.cache.ReadCache;

import com.thabendra.telecom.push.GatewayCommand.Reason;
import com.thabendra.telecom.subscriber.Plan;
import com.thabendra.telecom.subscriber.Subscriber;
import com.thabendra.telecom.subscriber.SubscriberService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * REST adapter over the same service the SOAP endpoint uses. Who may call each endpoint is set in
 * {@link com.thabendra.telecom.access.PublicApiSecurity}: a customer only for their own number (CU-2),
 * staff for any.
 */
@RestController
@RequestMapping("/api")
public class SubscriberController {

    private static final TypeReference<List<PlanDto>> PLAN_LIST = new TypeReference<>() {
    };

    private final SubscriberService service;
    private final ReadCache cache;

    public SubscriberController(SubscriberService service, ReadCache cache) {
        this.service = service;
        this.cache = cache;
    }

    public record PlanDto(String code, String name, BigDecimal monthlyFee, int dataGb, int voiceMinutes,
                          int maxBandwidthKbps) {
        static PlanDto from(Plan p) {
            return new PlanDto(p.getCode(), p.getName(), p.getMonthlyFee(), p.getDataGb(), p.getVoiceMinutes(),
                    p.getMaxBandwidthKbps());
        }
    }

    public record SubscriberDto(String msisdn, String fullName, String nic, BigDecimal balance, String status,
                                PlanDto plan) {
        static SubscriberDto from(Subscriber s) {
            return new SubscriberDto(s.getMsisdn(), s.getFullName(), s.getNic(), s.getBalance(),
                    s.getStatus().name(), PlanDto.from(s.getPlan()));
        }
    }

    /**
     * CA-1: the part of a profile that rarely changes. No balance: every charge changes it, so it is always
     * read from Oracle. Only the plan's code: the plan comes from the cached plan list, so changing a plan's
     * speed reaches every profile by deleting one key.
     */
    public record CachedProfile(String msisdn, String fullName, String nic, String status, String planCode) {
        static CachedProfile from(Subscriber s) {
            return new CachedProfile(s.getMsisdn(), s.getFullName(), s.getNic(), s.getStatus().name(),
                    s.getPlan().getCode());
        }
    }

    public record CreateSubscriberRequest(
            @NotNull @Pattern(regexp = "947\\d{8}") String msisdn,
            @NotBlank @Size(min = 2, max = 100) String fullName,
            @NotNull @Pattern(regexp = "\\d{9}[VvXx]|\\d{12}") String nic,
            @NotBlank String planCode,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal initialBalance) {
    }

    public record TopUpRequest(@NotNull @DecimalMin(value = "0", inclusive = false)
                               @Digits(integer = 8, fraction = 2) BigDecimal amount) {
    }

    public record ChangePlanRequest(@NotBlank String planCode) {
    }

    @GetMapping("/plans")
    public List<PlanDto> listPlans() {
        return cache.get("plans", CacheKeys.PLANS, PLAN_LIST,
                () -> service.listPlans().stream().map(PlanDto::from).toList());
    }

    @PostMapping("/subscribers")
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriberDto create(@Valid @RequestBody CreateSubscriberRequest request) {
        return SubscriberDto.from(service.createSubscriber(request.msisdn(), request.fullName(), request.nic(),
                request.planCode(), request.initialBalance()));
    }

    @GetMapping("/subscribers/{msisdn}")
    public SubscriberDto get(@PathVariable String msisdn) {
        CachedProfile profile = cache.get("profile", CacheKeys.profile(msisdn), CachedProfile.class,
                () -> CachedProfile.from(service.getSubscriber(msisdn)));
        return listPlans().stream()
                .filter(plan -> plan.code().equals(profile.planCode()))
                .findFirst()
                .map(plan -> new SubscriberDto(profile.msisdn(), profile.fullName(), profile.nic(),
                        service.balance(msisdn), profile.status(), plan))
                .orElseGet(() -> SubscriberDto.from(service.getSubscriber(msisdn)));   // a plan newer than the list
    }

    @PostMapping("/subscribers/{msisdn}/top-ups")
    public SubscriberDto topUp(@PathVariable String msisdn, @Valid @RequestBody TopUpRequest request) {
        return SubscriberDto.from(service.topUp(msisdn, request.amount()));
    }

    @PutMapping("/subscribers/{msisdn}/plan")
    public SubscriberDto changePlan(@PathVariable String msisdn, @Valid @RequestBody ChangePlanRequest request) {
        return SubscriberDto.from(service.changePlan(msisdn, request.planCode()));
    }

    /** reason: SUSPENDED (operator decision) or FRAUD. */
    public record SuspendRequest(@NotNull Reason reason) {
    }

    // staff only (PR-2): suspend / unsuspend also tell every gateway to block / unblock (PU-1, PU-2),
    // and the staff member's login is kept as the one who did it
    @PutMapping("/subscribers/{msisdn}/suspension")
    public SubscriberDto suspend(@PathVariable String msisdn, @Valid @RequestBody SuspendRequest request,
                                 Authentication staff) {
        return SubscriberDto.from(service.suspend(msisdn, request.reason(), staff.getName()));
    }

    @DeleteMapping("/subscribers/{msisdn}/suspension")
    public SubscriberDto unsuspend(@PathVariable String msisdn, Authentication staff) {
        return SubscriberDto.from(service.unsuspend(msisdn, staff.getName()));
    }
}
