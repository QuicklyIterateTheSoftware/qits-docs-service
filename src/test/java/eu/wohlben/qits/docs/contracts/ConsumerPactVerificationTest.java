package eu.wohlben.qits.docs.contracts;

import static org.junit.jupiter.api.Assertions.fail;

import au.com.dius.pact.core.model.Interaction;
import au.com.dius.pact.core.model.Pact;
import au.com.dius.pact.core.model.ProviderState;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactSource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * <b>Every consumer pact against qits-docs-service, replayed against this service</b> (ticket
 * qits-1149, epic qits-546) — the provider half, after qits-projects-service's class of the same
 * name. Each pinned {@code pacts/*_qits-docs-service.json} on the test classpath ({@link
 * ClasspathPactLoader}) is verified interaction by interaction against the running application,
 * with qits-artifacts stood in for by the stories' {@code StoryStore}.
 *
 * <p>An interaction must name a state {@link ProviderStates} answers for and carry both {@code
 * comments.references} groups. No consumer pact is pinned yet, so today this verifies nothing
 * ({@link IgnoreNoPactsToVerify}); the expected first consumer is qits-docs-frontend.
 */
@QuarkusTest
@TestProfile(ContractProfile.class)
@Provider(ConsumerPactVerificationTest.PROVIDER)
@PactSource(ClasspathPactLoader.class)
@IgnoreNoPactsToVerify
class ConsumerPactVerificationTest {

  static final String PROVIDER = "qits-docs-service";

  static {
    System.setProperty("pact_do_not_track", "true");
  }

  @TestHTTPResource("/")
  URL base;

  @BeforeEach
  void target(PactVerificationContext context, Pact pact, Interaction interaction) {
    if (context == null) {
      return; // No pact: nothing to target.
    }
    String consumer = pact.getConsumer().getName();
    for (ProviderState state : interaction.getProviderStates()) {
      if (!ProviderStates.names().contains(state.getName())) {
        fail(
            "Consumer '"
                + consumer
                + "' needs the provider state '"
                + state.getName()
                + "' (interaction '"
                + interaction.getDescription()
                + "'), which qits-docs does not answer for — it answers for "
                + ProviderStates.names());
      }
    }
    var references = interaction.getComments().get("references");
    for (String key : List.of("qits-call", "qits-trigger")) {
      if (references == null || !references.isObject() || !references.asObject().has(key)) {
        fail(
            "Consumer '"
                + consumer
                + "' interaction '"
                + interaction.getDescription()
                + "' carries no comments.references."
                + key);
      }
    }
    context.setTarget(new HttpTestTarget(base.getHost(), base.getPort()));
  }

  @TestTemplate
  @ExtendWith(PactVerificationInvocationContextProvider.class)
  void consumerPactHolds(PactVerificationContext context) {
    if (context != null) {
      context.verifyInteraction();
    }
  }

  @State(ProviderStates.A_PUBLISHED_DOCS_SITE)
  Map<String, String> aPublishedDocsSite() {
    return ProviderStates.params(ProviderStates.A_PUBLISHED_DOCS_SITE);
  }

  @State(ProviderStates.A_DOCS_SITE_PUBLISHED_FROM_TWO_BRANCHES)
  Map<String, String> aDocsSitePublishedFromTwoBranches() {
    return ProviderStates.params(ProviderStates.A_DOCS_SITE_PUBLISHED_FROM_TWO_BRANCHES);
  }

  @State(ProviderStates.NO_SUCH_DOCS_SITE)
  Map<String, String> noSuchDocsSite() {
    return ProviderStates.params(ProviderStates.NO_SUCH_DOCS_SITE);
  }
}
