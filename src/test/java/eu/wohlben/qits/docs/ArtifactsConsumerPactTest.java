package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>The consumer half of the qits-artifacts contract</b> (ticket qits-1149): a real {@link
 * DocsUpstream} making real HTTP calls to a pact-jvm mock server that answers what {@link
 * ArtifactsContract}'s row promises, and the row's own assertions on what it read.
 *
 * <p>One dynamic test per row, one mock server per row. A row whose provider state qits-artifacts
 * does not record is skipped, naming the state.
 */
class ArtifactsConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  @Test
  void everyRowHasItsCall() {
    assertEquals(ArtifactsContract.PACT.rows().size(), ArtifactsContract.CALLS.size());
  }

  @TestFactory
  Stream<DynamicTest> everyRowIsWhatDocsUpstreamAsksAndReads() {
    return ArtifactsContract.PACT.rows().stream()
        .map(
            row ->
                DynamicTest.dynamicTest(
                    row.description() + " [" + row.state() + "]", () -> verify(row)));
  }

  private static void verify(GoldenInteraction row) {
    Assumptions.assumeTrue(
        ArtifactsContract.ARTIFACTS.recorded(row.state(), row.operationId()),
        () -> ArtifactsContract.PACT.needs(row));
    ArtifactsContract.PACT.run(
        row,
        (url, recorded) ->
            ArtifactsContract.CALLS
                .get(row)
                .run(ArtifactsContract.upstreamAgainst(url), recorded.params(), row));
  }
}
