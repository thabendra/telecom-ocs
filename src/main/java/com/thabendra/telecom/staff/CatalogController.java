package com.thabendra.telecom.staff;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
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
 * PR-2, ADMIN only (see {@link com.thabendra.telecom.access.PublicApiSecurity}):
 * <pre>
 *   GET  /api/admin/packages                      every package, on sale or not
 *   POST /api/admin/packages                      create a package (201)
 *   PUT  /api/admin/packages/{code}/price         {"price": 790.00}
 *   PUT  /api/admin/packages/{code}/speed         {"maxBandwidthKbps": 4096, "throttledBandwidthKbps": 512}
 *   PUT  /api/admin/packages/{code}/status        {"active": false}   stop / start selling
 *   GET  /api/admin/packages/{code}/prices        price history
 *   PUT  /api/admin/plans/{code}/speed            {"maxBandwidthKbps": 20480}
 * </pre>
 * The admin's login from the SSO token is recorded as the one who made the change.
 */
@RestController
@RequestMapping("/api/admin")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record CreatePackageRequest(
            @NotNull @Pattern(regexp = "[A-Z0-9_]{2,20}") String code,
            @NotBlank @Size(max = 60) String name,
            @Pattern(regexp = "YOUTUBE|WHATSAPP|FACEBOOK") String app,
            @NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price,
            @Positive Long dataMb,
            @Min(1) @Max(365) int validityDays,
            @Min(64) @Max(10_000_000) int maxBandwidthKbps,
            @Positive Long fairUsageMb,
            @Min(64) @Max(10_000_000) Integer throttledBandwidthKbps) {
    }

    public record PriceRequest(@NotNull @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal price) {
    }

    public record SpeedRequest(@Min(64) @Max(10_000_000) int maxBandwidthKbps,
                               @Min(64) @Max(10_000_000) Integer throttledBandwidthKbps) {
    }

    public record StatusRequest(@NotNull Boolean active) {
    }

    public record PlanSpeedRequest(@Min(64) @Max(10_000_000) int maxBandwidthKbps) {
    }

    @GetMapping("/packages")
    public List<CatalogService.CatalogPackage> packages() {
        return catalog.packages();
    }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogService.CatalogPackage create(@Valid @RequestBody CreatePackageRequest r, Authentication admin) {
        return catalog.create(new CatalogService.NewPackage(r.code(), r.name(), r.app(), r.price(), r.dataMb(),
                r.validityDays(), r.maxBandwidthKbps(), r.fairUsageMb(), r.throttledBandwidthKbps()), admin.getName());
    }

    @PutMapping("/packages/{code}/price")
    public CatalogService.CatalogPackage price(@PathVariable String code, @Valid @RequestBody PriceRequest r,
                                               Authentication admin) {
        return catalog.changePrice(code, r.price(), admin.getName());
    }

    @PutMapping("/packages/{code}/speed")
    public CatalogService.CatalogPackage speed(@PathVariable String code, @Valid @RequestBody SpeedRequest r,
                                               Authentication admin) {
        return catalog.changeSpeed(code, r.maxBandwidthKbps(), r.throttledBandwidthKbps(), admin.getName());
    }

    @PutMapping("/packages/{code}/status")
    public CatalogService.CatalogPackage status(@PathVariable String code, @Valid @RequestBody StatusRequest r,
                                                Authentication admin) {
        return catalog.setActive(code, r.active(), admin.getName());
    }

    @GetMapping("/packages/{code}/prices")
    public List<CatalogService.PriceChange> prices(@PathVariable String code) {
        return catalog.priceHistory(code);
    }

    @PutMapping("/plans/{code}/speed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void planSpeed(@PathVariable String code, @Valid @RequestBody PlanSpeedRequest r, Authentication admin) {
        catalog.changePlanSpeed(code, r.maxBandwidthKbps(), admin.getName());
    }
}
