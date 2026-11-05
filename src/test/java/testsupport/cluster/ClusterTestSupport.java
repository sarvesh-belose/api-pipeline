package testsupport.cluster;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.MSSQLServerContainer;

import com.crisil.apiorch.ApiorchApplication;

/**
 * Several apiorch nodes on one database, for lease, recovery and hot-reload tests (R1 plan §7.3). Each node is a
 * full Spring context with its own {@code apiorch.instance-id} and ports. On SQL Server (the {@code slow} tier,
 * because H2 ignores lock hints, ADR-003 F3) pass the shared container; on H2 pass a named in-memory URL.
 *
 * <p>US-5.5 adds the engine hooks (freeze lease renewal, latch the coordinator) on top of {@link Node#bean}.
 */
public final class ClusterTestSupport implements AutoCloseable {

    /** One running node. */
    public record Node(String instanceId, ConfigurableApplicationContext context) {

        public int port() {
            return ((WebServerApplicationContext) context).getWebServer().getPort();
        }

        public <T> T bean(Class<T> type) {
            return context.getBean(type);
        }
    }

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final List<Node> nodes = new ArrayList<>();

    private ClusterTestSupport(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static ClusterTestSupport onSqlServer(MSSQLServerContainer<?> container) {
        return new ClusterTestSupport(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    public static ClusterTestSupport onH2(String databaseName) {
        return new ClusterTestSupport("jdbc:h2:mem:" + databaseName + ";MODE=MSSQLServer;DATABASE_TO_UPPER=FALSE;"
                + "CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
    }

    /** Starts a node; {@code extraArgs} are {@code --key=value} overrides. */
    public Node start(String instanceId, String... extraArgs) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--management.server.port=0",
                "--apiorch.instance-id=" + instanceId, "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + username, "--spring.datasource.password=" + password));
        args.addAll(List.of(extraArgs));
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ApiorchApplication.class)
                .profiles("test").run(args.toArray(String[]::new));
        Node node = new Node(instanceId, ctx);
        nodes.add(node);
        return node;
    }

    /** Graceful stop: the node's shutdown path runs (US-5.5 releases its leases). */
    public void stop(Node node) {
        node.context().close();
        nodes.remove(node);
    }

    public List<Node> nodes() {
        return List.copyOf(nodes);
    }

    @Override
    public void close() {
        for (Node node : List.copyOf(nodes)) {
            stop(node);
        }
    }
}
