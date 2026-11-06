import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.tools.attach.VirtualMachine;

/**
 * US-1.4 load-balancer simulation: a graceful stop of one local node, as SIGTERM would do on Linux. Windows has no
 * SIGTERM for a console-less JVM, and the HTTP shutdown endpoint stays denied, so this attaches to the node's JVM and
 * invokes Spring Boot's shutdown endpoint over local JMX (the node starts with it exposed on JMX only).
 *
 * <p>Run with {@code java tools/lb-sim/GracefulStop.java <pid>}.
 */
public class GracefulStop {

    public static void main(String[] args) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(args[0]);
        String address = vm.startLocalManagementAgent();
        vm.detach();
        try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(address))) {
            MBeanServerConnection server = connector.getMBeanServerConnection();
            Object result = server.invoke(new ObjectName("org.springframework.boot:type=Endpoint,name=Shutdown"),
                    "shutdown", null, null);
            System.out.println("shutdown requested: " + result);
        }
    }
}
