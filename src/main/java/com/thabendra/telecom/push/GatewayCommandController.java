package com.thabendra.telecom.push;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The push channel as seen by a gateway (PU-3). The gateway polls over its own outbound mTLS
 * connection, so nothing has to reach into the operator's network, and acks every command it applied.
 * Needs scope notify:read; the gateway is the one in its certificate + token (group 3).
 * <pre>
 *   GET  /charging/v1/commands            due commands for me (BLOCK / UNBLOCK), oldest first
 *   POST /charging/v1/commands/{id}/ack   applied it; without an ack it comes again (PU-4)
 * </pre>
 */
@RestController
@RequestMapping("/charging/v1/commands")
public class GatewayCommandController {

    private final GatewayCommands commands;

    public GatewayCommandController(GatewayCommands commands) {
        this.commands = commands;
    }

    @GetMapping
    public List<GatewayCommand> poll(Authentication gateway) {
        return commands.poll(gateway.getName());
    }

    @PostMapping("/{id}/ack")
    public ResponseEntity<Void> ack(Authentication gateway, @PathVariable String id) {
        commands.ack(gateway.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
