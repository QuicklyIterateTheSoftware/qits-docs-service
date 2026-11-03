package eu.wohlben.qits.docs;

import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import org.junit.jupiter.api.Test;

/**
 * <b>The consumer pact against qits-idp</b> (qits-1419), {@code
 * pacts/qits-docs-service_qits-idp-service.json}: the client-credentials mint of the named client
 * {@code qits} — {@code POST /idp/token} with HTTP Basic, {@code grant_type=client_credentials} and
 * {@code audience=qits-platform} — recorded by qits-idp as {@code issueToken} in {@value
 * #A_SERVICE_CLIENT_CALLING_ITS_PEERS}. qits-githost-service's row of the same name, copied.
 *
 * <p><b>One row and no tenant rows</b>: this service validates no bearer, so it never reads the
 * discovery document or the keys. {@link DocsBearer} spends the token on every read of
 * qits-artifacts, so those reads are the row's trigger. {@link IdpTokenMintPactTest} runs it
 * through the real oidc client, because the client only exists inside the application; {@code
 * -Dgolden.update=true} rewrites the file.
 */
class IdpConsumerPactTest {

  static {
    // pact-jvm reports usage metrics over the network unless told not to; a test never should.
    System.setProperty("pact_do_not_track", "true");
  }

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  /** The qits-idp state that records the client-credentials mint of a service client. */
  static final String A_SERVICE_CLIENT_CALLING_ITS_PEERS = "a service client calling its peers";

  /** The mint, caused by whichever read of qits-artifacts needs a bearer first. */
  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of("call", "call", "DocsUpstream (every read of qits-artifacts)"),
              A_SERVICE_CLIENT_CALLING_ITS_PEERS,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  static final ConsumerPact PACT = ConsumerPact.of(ArtifactsContract.CONSUMER, IDP, MINT);

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.assertEveryInteractionCarriesBothReferences();
    PACT.compareOrWritePactFile();
  }
}
