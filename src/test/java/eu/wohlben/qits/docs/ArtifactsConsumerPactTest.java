package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertFalse;

import au.com.dius.pact.consumer.ConsumerPactRunnerKt;
import au.com.dius.pact.consumer.PactVerificationResult;
import au.com.dius.pact.consumer.model.MockProviderConfig;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.docs.contracts.GoldenMasters;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of the qits-artifacts contract</b> (ticket qits-1149): a real {@link
 * DocsUpstream} making real HTTP calls to a pact-jvm mock server that answers what {@link
 * ArtifactsContract}'s row promises, and the row's own assertions on what it read.
 *
 * <p>One dynamic test per row, one mock server per row (two rows send the identical request and
 * differ only in their trigger). A row whose provider state qits-artifacts does not record yet is
 * SKIPPED, naming the state — it runs the day the golden masters land on the classpath.
 */
class ArtifactsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatDocsUpstreamAsksAndReads() {
    assertFalse(ArtifactsContract.CASES.isEmpty());
    return ArtifactsContract.CASES.stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.description() + " [" + row.state() + "]", () -> verify(row)));
  }

  private static void verify(ArtifactsContract.Case row) {
    Assumptions.assumeTrue(row.recorded(), row.waitingFor());
    PactVerificationResult result =
        ConsumerPactRunnerKt.runConsumerTest(
            ArtifactsContract.pact(List.of(row)),
            MockProviderConfig.createDefault(PactSpecVersion.V4),
            (mockServer, context) -> {
              row.call()
                  .run(
                      upstreamAgainst(mockServer.getUrl()), GoldenMasters.params(row.state()), row);
              return null;
            });
    if (!(result instanceof PactVerificationResult.Ok)) {
      throw new AssertionError(row.description() + " [" + row.state() + "]: " + result);
    }
  }

  /** The bean as CDI would build it, against the mock server's address (no path). */
  private static DocsUpstream upstreamAgainst(String baseUrl) {
    DocsUpstream upstream = new DocsUpstream();
    upstream.artifactsUrl = baseUrl;
    upstream.connectTimeout = Duration.ofSeconds(2);
    upstream.requestTimeout = Duration.ofSeconds(10);
    upstream.open();
    return upstream;
  }
}
