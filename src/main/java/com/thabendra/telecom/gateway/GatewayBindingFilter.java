package com.thabendra.telecom.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs after the access token was verified, and checks that the token and the TLS connection belong
 * to the same gateway:
 * <ul>
 *   <li>the certificate isn't on the revocation list, otherwise 403 (SE-6)</li>
 *   <li>the connection's certificate name (CN) is an allowed gateway, otherwise 403 (SE-1)</li>
 *   <li>the token was issued to that gateway (sub = client_id = CN), otherwise 403: a Colombo token
 *       can't be used over a Kandy connection (SE-3)</li>
 *   <li>the token's cnf.x5t#S256 is this certificate's thumbprint, otherwise 401: a stolen token is
 *       useless without the matching certificate and private key (SE-4). Spring Security already
 *       refuses a mismatching cnf while checking the token; this also refuses a token with no cnf.</li>
 * </ul>
 * Not a Spring bean on purpose: it only runs inside the gateway security chain.
 */
class GatewayBindingFilter extends OncePerRequestFilter {

    private static final String CERTIFICATE_ATTRIBUTE = "jakarta.servlet.request.X509Certificate";
    private static final Pattern COMMON_NAME = Pattern.compile("(?:^|,)CN=([^,]+)");

    private final GatewayProperties gateways;
    private final RevocationList revocations;

    GatewayBindingFilter(GatewayProperties gateways, RevocationList revocations) {
        this.gateways = gateways;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        if (!(caller instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);   // no valid token: the authorization step answers 401
            return;
        }

        X509Certificate certificate = clientCertificate(request);
        if (certificate == null) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "A client certificate is required");
            return;
        }
        String gateway = commonName(certificate);
        if (revocations.isRevoked(certificate)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "The certificate of " + gateway + " is revoked");
            return;
        }
        if (gateway == null || !gateways.allowed().contains(gateway)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Gateway " + gateway + " is not allowed");
            return;
        }
        if (!gateway.equals(token.getName())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN,
                    "The access token was issued to " + token.getName() + ", but the certificate is " + gateway);
            return;
        }
        if (!GatewayTokens.thumbprint(certificate).equals(GatewayTokens.boundThumbprint(token.getToken()))) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "The access token is not bound to this certificate");
            return;
        }
        chain.doFilter(request, response);
    }

    private static X509Certificate clientCertificate(HttpServletRequest request) {
        Object chain = request.getAttribute(CERTIFICATE_ATTRIBUTE);
        return chain instanceof X509Certificate[] certificates && certificates.length > 0 ? certificates[0] : null;
    }

    private static String commonName(X509Certificate certificate) {
        Matcher name = COMMON_NAME.matcher(certificate.getSubjectX500Principal().getName());
        return name.find() ? name.group(1) : null;
    }

    private static void reject(HttpServletResponse response, int status, String detail) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String body = "{\"status\":%d,\"detail\":\"%s\"}".formatted(status, detail.replace("\"", "'"));
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
