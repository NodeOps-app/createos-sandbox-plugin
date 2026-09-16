package sh.createos.jenkins.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.sun.net.httpserver.HttpServer;
import hudson.model.TaskListener;
import hudson.util.Secret;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CreateOSSlaveTest {

  @Test
  void backingSandboxTerminationIsIdempotent(JenkinsRule r) throws Exception {
    AtomicInteger deletes = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/sandboxes/sb-test",
        exchange -> {
          if ("DELETE".equals(exchange.getRequestMethod())) {
            deletes.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
          } else {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
          }
          exchange.close();
        });
    server.start();
    try {
      SystemCredentialsProvider.getInstance()
          .getCredentials()
          .add(
              new StringCredentialsImpl(
                  CredentialsScope.GLOBAL,
                  "createos-api-key",
                  "CreateOS API key",
                  Secret.fromString("sk-test")));

      CreateOSCloud cloud = new CreateOSCloud("createos");
      cloud.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
      cloud.setCredentialsId("createos-api-key");
      SandboxTemplate template = new SandboxTemplate("createos", "s-1vcpu-1gb", "devbox:1");
      cloud.setTemplates(List.of(template));
      r.jenkins.clouds.add(cloud);

      CreateOSSlave slave = new CreateOSSlave("createos-test", template, cloud);
      slave.setSandboxId("sb-test");

      slave.terminateBackingSandbox(TaskListener.NULL);
      slave.terminateBackingSandbox(TaskListener.NULL);

      assertEquals(1, deletes.get());
    } finally {
      server.stop(0);
    }
  }
}
