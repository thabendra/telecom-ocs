package com.thabendra.telecom.gateway;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * The two doors of one application (SE-1 + SE-8):
 * <ul>
 *   <li>gateway port = server.port, mTLS (server.ssl.client-auth: need)</li>
 *   <li>public port = telecom.public-port, an extra plain HTTP connector for customer / admin APIs</li>
 * </ul>
 * Also remembers the ports really in use (tests start both on port 0 = any free port).
 */
@Component
public class ServerPorts implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>,
        ApplicationListener<ServletWebServerInitializedEvent> {

    private final Connector publicConnector = new Connector();
    private volatile int gatewayPort = -1;

    public ServerPorts(@Value("${telecom.public-port}") int publicPort) {
        publicConnector.setPort(publicPort);
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addAdditionalTomcatConnectors(publicConnector);
    }

    // getPort() is the main connector, i.e. server.port with its TLS settings. The management server
    // (OB-2) is a second web server with its own event: its port is not the gateway port
    @Override
    public void onApplicationEvent(ServletWebServerInitializedEvent event) {
        if (!"management".equals(event.getApplicationContext().getServerNamespace())) {
            gatewayPort = event.getWebServer().getPort();
        }
    }

    public int gatewayPort() {
        return gatewayPort;
    }

    public int publicPort() {
        return publicConnector.getLocalPort();
    }
}
