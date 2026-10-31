package com.thabendra.telecom.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * telecom.gateways.* in application.yml.
 *
 * @param paths   the gateway API (Ant patterns): only on the mTLS port, and only for allowed gateways
 * @param allowed certificate names (CN) of the gateways that may call it
 */
@ConfigurationProperties("telecom.gateways")
public record GatewayProperties(List<String> paths, List<String> allowed) {
}
