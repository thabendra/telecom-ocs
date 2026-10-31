package com.thabendra.telecom.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * telecom.gateways.* in application.yml.
 *
 * @param paths   the gateway API (Ant patterns): only on the mTLS port, and only for allowed gateways
 * @param allowed certificate names (CN) of the gateways that may call it
 * @param token   what a gateway's access token must look like
 */
@ConfigurationProperties("telecom.gateways")
public record GatewayProperties(List<String> paths, List<String> allowed, Token token) {

    /**
     * @param issuer    the token server (iss claim)
     * @param jwkSetUri where its public signing keys are published
     * @param audience  this API's name, required in the aud claim
     * @param scope     the scope the gateway API needs (SE-5)
     */
    public record Token(String issuer, String jwkSetUri, String audience, String scope) {
    }
}
