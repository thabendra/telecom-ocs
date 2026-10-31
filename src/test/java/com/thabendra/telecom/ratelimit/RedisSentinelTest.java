package com.thabendra.telecom.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DnsResolvers;
import io.lettuce.core.resource.MappingSocketAddressResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RD-7: a Redis primary, a replica and three Sentinels set up like docker-compose.ha.yml (TLS + the ACL
 * users), and the backend's "redis-sentinel" settings: it finds the primary through the Sentinels,
 * and after the primary dies it finds the promoted replica, with the data written before.
 * <p>
 * The test runs outside the Docker network, where names like "redis-replica-1" don't resolve, so a
 * Lettuce address mapper sends each name to that container's published port; the TLS host name check
 * still uses the real name. Skipped when Docker isn't running.
 */
class RedisSentinelTest {

    private static final String IMAGE = "redis:7.4-alpine";
    private static final List<String> SENTINELS = List.of("redis-sentinel-1", "redis-sentinel-2", "redis-sentinel-3");

    private static Network network;
    private static GenericContainer<?> primary;
    private static GenericContainer<?> replica;
    private static final List<GenericContainer<?>> sentinels = new ArrayList<>();
    private static final Map<String, GenericContainer<?>> byName = new HashMap<>();
    private static LettuceConnectionFactory factory;
    private static ClientResources resources;

    @BeforeAll
    static void startRedisWithSentinels() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not running");
        network = Network.newNetwork();
        primary = server("redis", "");
        primary.start();
        replica = server("redis-replica-1", " --replicaof redis 6379")
                .waitingFor(Wait.forLogMessage(".*MASTER <-> REPLICA sync: Finished with success.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        replica.start();
        for (String name : SENTINELS) {
            GenericContainer<?> sentinel = withTls(new GenericContainer<>(IMAGE))
                    .withNetwork(network).withNetworkAliases(name)
                    .withCopyFileToContainer(MountableFile.forHostPath("docker/redis/sentinel.conf", 0644),
                            "/conf/sentinel.conf")
                    .withCommand("sh", "-c", "cp /conf/sentinel.conf /data/sentinel.conf"
                            + " && echo 'sentinel announce-ip " + name + "' >> /data/sentinel.conf"
                            + " && exec redis-sentinel /data/sentinel.conf")
                    .withExposedPorts(26379)
                    // it has found the primary, and through the primary the replica it could promote
                    .waitingFor(Wait.forLogMessage(".*\\+slave slave redis-replica-1.*", 1)
                            .withStartupTimeout(Duration.ofSeconds(60)));
            sentinel.start();
            sentinels.add(sentinel);
            byName.put(name, sentinel);
        }
        byName.put("redis", primary);
        byName.put("redis-replica-1", replica);
        factory = backendConnection();
    }

    @AfterAll
    static void stopAll() {
        if (factory != null) {
            factory.destroy();
            resources.shutdown();
        }
        sentinels.forEach(GenericContainer::stop);
        if (replica != null) {
            replica.stop();
            if (primary.isRunning()) {
                try {
                    primary.getDockerClient().unpauseContainerCmd(primary.getContainerId()).exec();
                } catch (RuntimeException notPaused) {
                    // the test failed before pausing it
                }
            }
            primary.stop();
            network.close();
        }
    }

    @Test
    void afterThePrimaryDiesTheBackendFindsThePromotedReplica() throws Exception {
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.opsForValue().set("ratelimit:before-failover", "kept");
        Thread.sleep(1000);   // replication is asynchronous: give the replica a moment to copy it

        // a crashed server: it stops answering, but its name still resolves. (Removing the container would
        // also remove its DNS name, and Sentinel goes into TILT mode, no failovers, when a name doesn't resolve)
        primary.getDockerClient().pauseContainerCmd(primary.getContainerId()).exec();

        // the Sentinels need ~3 s to agree it is down (down-after-milliseconds), then promote the replica
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        boolean written = false;
        while (!written && System.nanoTime() < deadline) {
            try {
                redis.opsForValue().set("ratelimit:after-failover", "ok");
                written = true;
            } catch (DataAccessException stillFailingOver) {
                Thread.sleep(500);
            }
        }

        assertThat(written).as("a write works again after the failover; Sentinel said:%n%s",
                sentinels.get(0).getLogs()).isTrue();
        assertThat(redis.opsForValue().get("ratelimit:before-failover")).isEqualTo("kept");
        assertThat(redis.opsForValue().get("ratelimit:after-failover")).isEqualTo("ok");
    }

    // a Redis server as in docker-compose.ha.yml: TLS only, the ACL file, replication over TLS as "replica"
    private static GenericContainer<?> server(String name, String extra) {
        return withTls(new GenericContainer<>(IMAGE))
                .withNetwork(network).withNetworkAliases(name)
                .withCopyFileToContainer(MountableFile.forHostPath("docker/redis/users.acl", 0644),
                        "/etc/redis/users.acl")
                .withCommand("sh", "-c", "exec redis-server --port 0 --tls-port 6379"
                        + " --tls-cert-file /tls/redis.pem --tls-key-file /tls/redis-key.pem"
                        + " --tls-ca-cert-file /tls/ca.pem --tls-auth-clients no --tls-replication yes"
                        + " --aclfile /etc/redis/users.acl --masteruser replica --masterauth dev-only-replica-password"
                        + " --replica-announce-ip " + name + extra)
                .withExposedPorts(6379)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    }

    private static GenericContainer<?> withTls(GenericContainer<?> container) {
        return container
                .withCopyFileToContainer(MountableFile.forHostPath("certs/redis.pem", 0644), "/tls/redis.pem")
                .withCopyFileToContainer(MountableFile.forHostPath("certs/redis-key.pem", 0644), "/tls/redis-key.pem")
                .withCopyFileToContainer(MountableFile.forHostPath("certs/ca.pem", 0644), "/tls/ca.pem");
    }

    // the same values as application-redis-sentinel.yml + application.yml (RD-9) + RedisSentinelConfig
    private static LettuceConnectionFactory backendConnection() {
        RedisSentinelConfiguration sentinel = new RedisSentinelConfiguration("ocs",
                Set.of("redis-sentinel-1:26379", "redis-sentinel-2:26379", "redis-sentinel-3:26379"));
        sentinel.setUsername("telecom-ocs");
        sentinel.setPassword("dev-only-redis-password");
        sentinel.setSentinelUsername("telecom-ocs");
        sentinel.setSentinelPassword("dev-only-sentinel-client-password");

        resources = ClientResources.builder()
                .socketAddressResolver(MappingSocketAddressResolver.create(DnsResolvers.UNRESOLVED,
                        RedisSentinelTest::published))
                .build();
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder();
        RedisSentinelConfig.followFailover(builder);   // what the backend's redis-sentinel profile adds
        LettuceClientConfiguration client = builder
                .clientResources(resources)
                .commandTimeout(Duration.ofSeconds(2))
                .useSsl().and()
                .clientOptions(ClientOptions.builder()
                        .sslOptions(SslOptions.builder().jdkSslProvider()
                                .truststore(new File("certs/gateway-ca-truststore.p12"), "changeit").build())
                        .build())
                .build();
        LettuceConnectionFactory connection = new LettuceConnectionFactory(sentinel, client);
        connection.afterPropertiesSet();
        connection.start();
        return connection;
    }

    // "redis-replica-1:6379" -> localhost:<the port Docker published for that container>
    private static HostAndPort published(HostAndPort address) {
        GenericContainer<?> container = byName.get(address.getHostText());
        if (container == null || !container.isRunning()) {
            return address;
        }
        return HostAndPort.of(container.getHost(), container.getMappedPort(address.getPort()));
    }
}
