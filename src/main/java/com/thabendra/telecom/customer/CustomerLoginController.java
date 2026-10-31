package com.thabendra.telecom.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CU-1 on the public port, open to anyone (rate limited per IP, PR-3):
 * <pre>
 *   POST /api/auth/otp         {"msisdn":"94771234567"}                  202, an SMS with a code
 *   POST /api/auth/otp/verify  {"msisdn":"94771234567","code":"123456"}  200 {accessToken, ...} or 401
 * </pre>
 */
@RestController
@RequestMapping("/api/auth/otp")
public class CustomerLoginController {

    private final OtpService otp;

    public CustomerLoginController(OtpService otp) {
        this.otp = otp;
    }

    public record CodeRequest(@NotNull @Pattern(regexp = "947\\d{8}") String msisdn) {
    }

    public record VerifyRequest(@NotNull @Pattern(regexp = "947\\d{8}") String msisdn,
                                @NotNull @Pattern(regexp = "\\d{6}") String code) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestCode(@Valid @RequestBody CodeRequest request) {
        otp.requestCode(request.msisdn());
    }

    // a token must never be kept by a cache or proxy
    @PostMapping("/verify")
    public ResponseEntity<CustomerTokens.IssuedToken> verify(@Valid @RequestBody VerifyRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(otp.verify(request.msisdn(), request.code()));
    }
}
