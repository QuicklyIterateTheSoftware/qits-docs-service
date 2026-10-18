package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import eu.wohlben.qits.http.QitsHttpClients;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * <b>Every outbound call to a qits service has a row</b> (ticket qits-1149): the guard that keeps
 * {@code pacts/qits-docs-service_qits-artifacts-service.json} true as the code moves.
 *
 * <p>The calls are DERIVED from the code, not listed. First, every main class holding a {@link
 * HttpClient} or built from {@link QitsHttpClients} is an outbound client, and {@link DocsUpstream}
 * must be the only one. Then every method {@link DocsUpstream} offers the routes is run against a
 * recording double, with the arguments named by the provider state's params, and every request it
 * sent — method, path and query — must be one the committed pact holds. A new method, a new query
 * parameter or a changed path with no row fails here.
 *
 * <p><b>Excluded by name</b>, because neither is a qits REST contract: {@link #LIFECYCLE}, which
 * builds the client and sends nothing, and the OpenTelemetry exporter's OTLP posts to
 * qits-observability ({@code quarkus.otel.exporter.otlp.endpoint}), a telemetry stream every
 * service sends and no pact describes.
 */
class OutboundCallsGuardTest {

  /** {@link DocsUpstream}'s methods that send nothing. */
  static final Set<String> LIFECYCLE = Set.of("open");

  /** The argument each parameter name stands for: qits-artifacts' {@code a published docs site}. */
  private static final Map<String, String> ARGUMENTS =
      Map.of(
          "site", "contract-docs-site",
          "version", "2100.1.0",
          "path", "index.html",
          "branch", "main");

  @Test
  void docsUpstreamIsTheOnlyOutboundClient() {
    Set<String> clients = new TreeSet<>();
    for (String name : mainClassNames()) {
      if (isOutboundClient(load(name))) {
        clients.add(name);
      }
    }
    assertEquals(Set.of(DocsUpstream.class.getName()), clients);
  }

  @Test
  void everyRequestDocsUpstreamSendsIsInThePact() throws Exception {
    List<String> sent = Collections.synchronizedList(new ArrayList<>());
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String query = exchange.getRequestURI().getRawQuery();
          sent.add(
              exchange.getRequestMethod()
                  + " "
                  + exchange.getRequestURI().getRawPath()
                  + (query == null ? "" : "?" + URLDecoder.decode(query, StandardCharsets.UTF_8)));
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    Set<String> asked = new TreeSet<>();
    try {
      DocsUpstream upstream =
          ArtifactsContract.upstreamAgainst("http://127.0.0.1:" + server.getAddress().getPort());
      for (Method method : DocsUpstream.class.getDeclaredMethods()) {
        if (Modifier.isStatic(method.getModifiers())
            || Modifier.isPrivate(method.getModifiers())
            || method.isSynthetic()
            || LIFECYCLE.contains(method.getName())) {
          continue;
        }
        method.setAccessible(true);
        for (Object[] args : arguments(method)) {
          int before = sent.size();
          try {
            Object answer = method.invoke(upstream, args);
            if (answer instanceof AutoCloseable closeable) {
              closeable.close();
            }
          } catch (InvocationTargetException refused) {
            // A 404 is an answer the caller maps; only whether a request went out matters here.
          }
          assertTrue(sent.size() > before, method.getName() + " sent no request");
          asked.add(method.getName());
        }
      }
    } finally {
      server.stop(0);
    }
    assertFalse(asked.isEmpty(), "no method of DocsUpstream was driven");
    Set<String> pacted = pactedRequests();
    for (String request : new TreeSet<>(sent)) {
      assertTrue(
          pacted.contains(request),
          request
              + " is sent by DocsUpstream but no row of the pact holds it; the pact holds "
              + pacted);
    }
  }

  /** One argument list per call; a {@code branch} parameter is run both null and set. */
  private static List<Object[]> arguments(Method method) {
    Parameter[] parameters = method.getParameters();
    Object[] args = new Object[parameters.length];
    int branch = -1;
    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      assertTrue(
          parameter.isNamePresent(), "compile with -parameters: " + method + " has unnamed ones");
      String value = ARGUMENTS.get(parameter.getName());
      assertTrue(
          value != null && parameter.getType() == String.class,
          method + ": no argument for parameter " + parameter + " — name it in ARGUMENTS");
      args[i] = value;
      if ("branch".equals(parameter.getName())) {
        branch = i;
      }
    }
    if (branch < 0) {
      return List.<Object[]>of(args);
    }
    Object[] unfiltered = args.clone();
    unfiltered[branch] = null;
    return List.of(args, unfiltered);
  }

  /** The committed pact's requests, as {@code METHOD path[?k=v]}. */
  private static Set<String> pactedRequests() throws IOException {
    Path file = Path.of("pacts", ArtifactsContract.CONSUMER + "_qits-artifacts-service.json");
    JsonObject pact = new JsonObject(Files.readString(file));
    Set<String> requests = new TreeSet<>();
    for (Object entry : pact.getJsonArray("interactions")) {
      JsonObject request = ((JsonObject) entry).getJsonObject("request");
      StringBuilder line =
          new StringBuilder(request.getString("method") + " " + request.getString("path"));
      JsonObject query = request.getJsonObject("query");
      if (query != null) {
        List<String> pairs = new ArrayList<>();
        for (String key : new TreeSet<>(query.fieldNames())) {
          for (Object value : (JsonArray) query.getValue(key)) {
            pairs.add(key + "=" + value);
          }
        }
        line.append('?').append(String.join("&", pairs));
      }
      requests.add(line.toString());
    }
    return requests;
  }

  private static boolean isOutboundClient(Class<?> type) {
    for (Field field : type.getDeclaredFields()) {
      if (HttpClient.class.isAssignableFrom(field.getType())
          || QitsHttpClients.class.equals(field.getType())) {
        return true;
      }
    }
    for (Constructor<?> constructor : type.getDeclaredConstructors()) {
      if (Arrays.asList(constructor.getParameterTypes()).contains(QitsHttpClients.class)) {
        return true;
      }
    }
    return false;
  }

  /** Every main class: the code source of {@link DocsUpstream}, a directory in this build. */
  private static List<String> mainClassNames() {
    try {
      Path root =
          Path.of(DocsUpstream.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      try (Stream<Path> files = Files.walk(root)) {
        return files
            .map(f -> root.relativize(f).toString().replace('\\', '/'))
            .filter(f -> f.endsWith(".class"))
            .map(f -> f.substring(0, f.length() - ".class".length()).replace('/', '.'))
            .toList();
      }
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name, false, OutboundCallsGuardTest.class.getClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      throw new IllegalStateException("cannot load " + name, e);
    }
  }
}
