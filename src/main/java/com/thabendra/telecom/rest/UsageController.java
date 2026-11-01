package com.thabendra.telecom.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.thabendra.telecom.cache.CacheKeys;
import com.thabendra.telecom.cache.ReadCache;

import com.thabendra.telecom.usage.DataPackage;
import com.thabendra.telecom.usage.UsageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** REST adapter for packages, quota and usage charging. */
@RestController
@RequestMapping("/api")
public class UsageController {

    private static final TypeReference<List<PackageDto>> PACKAGE_LIST = new TypeReference<>() {
    };

    private final UsageService service;
    private final ReadCache cache;

    public UsageController(UsageService service, ReadCache cache) {
        this.service = service;
        this.cache = cache;
    }

    public record PackageDto(String code, String name, String app, BigDecimal price, Long dataMb, int validityDays,
                             int maxBandwidthKbps, Long fairUsageMb, Integer throttledBandwidthKbps) {
        static PackageDto from(DataPackage p) {
            return new PackageDto(p.getCode(), p.getName(), p.getApp(), p.getPrice(), p.getDataMb(),
                    p.getValidityDays(), p.getMaxBandwidthKbps(), p.getFairUsageMb(), p.getThrottledBandwidthKbps());
        }
    }

    public record ActivatePackageRequest(@NotBlank String packageCode) {
    }

    public record UsageRequest(@NotNull UsageService.App app, @Min(1) @Max(1_048_576) long dataMb) {
    }

    @GetMapping("/packages")
    public List<PackageDto> listPackages() {
        return cache.get("packages", CacheKeys.PACKAGES, PACKAGE_LIST,
                () -> service.listPackages().stream().map(PackageDto::from).toList());
    }

    @PostMapping("/subscribers/{msisdn}/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public UsageService.ActivatedPackage activatePackage(@PathVariable String msisdn,
                                                         @Valid @RequestBody ActivatePackageRequest request) {
        return service.activatePackage(msisdn, request.packageCode());
    }

    @PostMapping("/subscribers/{msisdn}/usage")
    public UsageService.UsageResult recordUsage(@PathVariable String msisdn, @Valid @RequestBody UsageRequest request) {
        return service.recordUsage(msisdn, request.app(), request.dataMb());
    }

    @GetMapping("/subscribers/{msisdn}/quota")
    public UsageService.Quota getQuota(@PathVariable String msisdn) {
        return service.getQuota(msisdn);
    }

    /** CU-2: usage history, by default the last 30 days, 20 rows a page, newest first. */
    @GetMapping("/subscribers/{msisdn}/usage")
    public UsageService.UsageHistory getUsage(
            @PathVariable String msisdn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        // no "to" = up to the newest row (not "now" here: recorded_at is in the database's clock)
        LocalDateTime start = from != null ? from : (to != null ? to : LocalDateTime.now()).minusDays(30);
        if (to != null && !start.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        return service.history(msisdn, start, to, page, size);
    }
}
