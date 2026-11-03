package eu.wohlben.qits.docs.telemetry;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The two platform services one export touches, on one loopback port — the shape of
 * qits-telemetry-core's own {@code StubPlatform}: qits-idp's token endpoint ({@code POST
 * /idp/token}, client credentials, a fresh {@code token-<n>} on every call) and
 * qits-observability's OTLP/HTTP ingest ({@code POST /observability/api/otel/v1/<signal>}), which
 * records every POST with the Authorization header it carried and answers 200.
 *
 * <p><b>Recorded to a file, port parked in a system property</b> — {@code StoryStore}'s discipline
 * and for its reason: a test profile is instantiated in more than one classloader, so static tables
 * would be read from a copy the server never wrote. One tab-separated line per request.
 */
public final class TelemetryStub {

  static final String INGEST = "/observability/api/otel";

  private static final String PORT_PROPERTY = "qits.docs.test.telemetry-stub.port";

  private static final Path RECORDING = Path.of("target", "telemetry-stub", "requests.tsv");

  private static final AtomicInteger MINTED = new AtomicInteger();

  /** One recorded request. {@code authorization} is {@code "-"} when it carried none. */
  record Request(String kind, String path, String authorization, String contentType, int bytes) {

    static Request parse(String line) {
      String[] f = line.split("\t", -1);
      return new Request(f[0], f[1], f[2], f[3], Integer.parseInt(f[4]));
    }

    boolean hasBearer() {
      return authorization.startsWith("Bearer token-");
    }
  }

  private TelemetryStub() {}

  /** Start once per JVM, wiping the last run's recording; answers the base URL. */
  static synchronized String ensureStarted() {
    String port = System.getProperty(PORT_PROPERTY);
    if (port != null) {
      return baseUrl(Integer.parseInt(port));
    }
    HttpServer server;
    try {
      Files.createDirectories(RECORDING.getParent());
      Files.deleteIfExists(RECORDING);
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException("could not start the telemetry stub", e);
    }
    server.createContext("/idp/token", TelemetryStub::token);
    server.createContext(INGEST + "/v1/", TelemetryStub::ingest);
    server.createContext("/", TelemetryStub::elsewhere);
    server.start();
    System.setProperty(PORT_PROPERTY, String.valueOf(server.getAddress().getPort()));
    return baseUrl(server.getAddress().getPort());
  }

  private static String baseUrl(int port) {
    return "http://127.0.0.1:" + port;
  }

  private static void token(HttpExchange exchange) throws IOException {
    int bytes = drain(exchange);
    record("TOKEN", exchange, bytes);
    String json =
        "{\"access_token\":\"token-"
            + MINTED.incrementAndGet()
            + "\",\"token_type\":\"Bearer\",\"expires_in\":300}";
    byte[] body = json.getBytes(UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private static void ingest(HttpExchange exchange) throws IOException {
    int bytes = drain(exchange);
    record("EXPORT", exchange, bytes);
    exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
    exchange.sendResponseHeaders(200, -1);
    exchange.close();
  }

  /** Anything else is recorded too, so a wrong path is as visible as a right one. */
  private static void elsewhere(HttpExchange exchange) throws IOException {
    int bytes = drain(exchange);
    record("OTHER", exchange, bytes);
    exchange.sendResponseHeaders(404, -1);
    exchange.close();
  }

  private static int drain(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return body.readAllBytes().length;
    }
  }

  private static synchronized void record(String kind, HttpExchange exchange, int bytes)
      throws IOException {
    String line =
        String.join(
                "\t",
                kind,
                exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
                header(exchange, "Authorization"),
                header(exchange, "Content-Type"),
                String.valueOf(bytes))
            + "\n";
    Files.writeString(RECORDING, line, UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  private static String header(HttpExchange exchange, String name) {
    String value = exchange.getRequestHeaders().getFirst(name);
    return value == null ? "-" : value.replace('\t', ' ');
  }

  /** Every request recorded so far, in arrival order. */
  static List<Request> requests() {
    try {
      if (!Files.isRegularFile(RECORDING)) {
        return List.of();
      }
      return Files.readAllLines(RECORDING, UTF_8).stream()
          .filter(line -> !line.isBlank())
          .map(Request::parse)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The first recorded request matching {@code filter} within {@code within}, polling. */
  static Optional<Request> await(Predicate<Request> filter, Duration within) {
    long deadline = System.nanoTime() + within.toNanos();
    do {
      Optional<Request> hit = requests().stream().filter(filter).findFirst();
      if (hit.isPresent()) {
        return hit;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
    } while (System.nanoTime() < deadline);
    return Optional.empty();
  }

  /** An OTLP POST to one signal's path. */
  static Predicate<Request> export(String signal) {
    return request ->
        request.kind().equals("EXPORT")
            && request.path().equals("POST " + INGEST + "/v1/" + signal);
  }
}
