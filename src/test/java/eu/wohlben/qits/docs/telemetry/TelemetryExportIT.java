package eu.wohlben.qits.docs.telemetry;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * <b>All three signals leave the packaged process, each carrying the {@code qits} client's
 * bearer</b> (epic qits-428, the first adopter of qits-telemetry-core).
 *
 * <p>The packaged artifact — the fast-jar in a plain {@code verify}, the native binary under {@code
 * -Dnative} — is launched with the OpenTelemetry SDK <b>on</b>, the one place in this suite it is.
 * {@code qits.telemetry.endpoint} and the {@code qits} oidc client both point at {@link
 * TelemetryStub} on loopback, so nothing here dials the real edge or idp. What it proves is the
 * whole adoption, end to end and in the shipped binary: the library's CDI exporters replaced
 * Quarkus' own (nothing reaches the OTLP default of localhost:4317, everything reaches the
 * configured base), each appended its {@code /v1/<signal>}, and the header supplier asked the oidc
 * client for a token and put it on the POST. In native mode it is also the proof that the
 * java.net.http sender was reachable without ServiceLoader and that the producers built at runtime
 * init.
 *
 * <p><b>Its own profile, so its own process.</b> The stories share {@code StoryProfile}, which
 * keeps the SDK dark because an exporter flushing on its own thread would draw arrows into
 * whichever story diagram happened to be open; a second launched qits-docs is the price of keeping
 * that guarantee and still covering the export. This class is no userflow story and draws no
 * diagram.
 */
@QuarkusIntegrationTest
@TestProfile(TelemetryExportIT.Profile.class)
class TelemetryExportIT {

  /** Generous: the native binary's first export waits for its first token and first batch. */
  private static final Duration WITHIN = Duration.ofSeconds(60);

  /** The SDK on, every address on the stub, every schedule short. RUNTIME keys only. */
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      String stub = TelemetryStub.ensureStarted();
      return Map.ofEntries(
          Map.entry("quarkus.otel.sdk.disabled", "false"),
          Map.entry("qits.telemetry.endpoint", stub + TelemetryStub.INGEST),
          Map.entry("quarkus.oidc-client.qits.auth-server-url", stub + "/idp"),
          Map.entry("quarkus.oidc-client.qits.client-id", "test-qits-docs"),
          Map.entry("quarkus.oidc-client.qits.credentials.secret", "secret"),
          Map.entry("quarkus.oidc-client.qits.client-enabled", "true"),
          // Never asked: the probe request below names no site. Pointed here anyway, so a mistake
          // lands on loopback rather than at registry.qits.example.test.
          Map.entry("qits.docs.artifacts-url", stub),
          // Export within a second instead of 5s (spans), 1s (logs) and 60s (metrics).
          Map.entry("quarkus.otel.bsp.schedule.delay", "200ms"),
          Map.entry("quarkus.otel.blrp.schedule.delay", "200ms"),
          Map.entry("quarkus.otel.metric.export.interval", "1s"));
    }
  }

  @Test
  void tracesLogsAndMetricsReachTheReceiverWithTheBearer() {
    // A request the service answers itself (no site, no store read), so a SERVER span exists —
    // whatever it answers (the client's SPA fallback may take it), a span is made. The startup
    // lines are the log records; the JVM and HTTP instruments are the metrics.
    given().get("/telemetry-probe").then().statusCode(org.hamcrest.Matchers.lessThan(500));

    for (String signal : new String[] {"traces", "logs", "metrics"}) {
      TelemetryStub.Request export =
          TelemetryStub.await(TelemetryStub.export(signal), WITHIN)
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "no "
                              + signal
                              + " export in "
                              + WITHIN
                              + "; recorded: "
                              + TelemetryStub.requests()));
      assertTrue(export.hasBearer(), signal + " export carried " + export.authorization());
      assertEquals(
          "application/x-protobuf", export.contentType(), signal + " export's content type");
      assertTrue(export.bytes() > 0, signal + " export had an empty body");
    }

    // The bearer came from the `qits` client's client-credentials mint, on the stub's idp — and
    // that request itself carried no bearer (it is the one that makes it).
    assertTrue(
        TelemetryStub.requests().stream()
            .anyMatch(r -> r.kind().equals("TOKEN") && r.path().equals("POST /idp/token")),
        "the oidc client never asked the stub idp for a token: " + TelemetryStub.requests());
    // Nothing went anywhere on the stub it should not: no store read, no bare base POST.
    assertTrue(
        TelemetryStub.requests().stream().noneMatch(r -> r.kind().equals("OTHER")),
        "unexpected requests: " + TelemetryStub.requests());
  }
}
