package eu.wohlben.qits.docs;

import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pact, {@code pacts/qits-docs-service_qits-artifacts-service.json}</b>
 * (ticket qits-1149): what {@link ArtifactsContract} writes, normalised and compared byte for byte
 * ({@code -Dgolden.update=true} rewrites it), with both references on every interaction. It is
 * published by {@code contracts: pacts:} in {@code .config/qits/release.yml}.
 */
class ArtifactsPactFileTest {

  @Test
  void theCommittedPactIsWhatTheContractWrites() {
    ArtifactsContract.PACT.assertEveryInteractionCarriesBothReferences();
    ArtifactsContract.PACT.compareOrWritePactFile();
  }
}
