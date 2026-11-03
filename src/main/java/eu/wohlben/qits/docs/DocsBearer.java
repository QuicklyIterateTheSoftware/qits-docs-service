package eu.wohlben.qits.docs;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The bearer {@link DocsUpstream} presents to qits-artifacts: this service's own {@code
 * qits:system} token, minted by the named oidc client {@code qits} from the deployer's {@code
 * idp:client} resource (qits-1419, the qits-githost pattern of qits-1330).
 *
 * <p><b>Why a read needs one at all.</b> qits-docs reaches the store at its edge host, {@code
 * registry.qits.<domain>} (feature qits-1406), and the edge refuses an anonymous request on a
 * service vhost — reads included, unless a deployment names the app in {@code
 * qits.edge.auth.anonymous-read-apps}, which ships empty and is not set live (a bare GET answered
 * 401 on 2026-11-03).
 *
 * <p><b>A token this cannot mint is empty rather than an exception</b> — qits-maintenance's {@code
 * PeerTokens} stance: the call then goes out bare, the edge answers 401, and the reader gets the
 * 502 that names the store, which is more useful than a mint failure one layer earlier. Off (the
 * extension's own {@code quarkus.oidc-client.qits.client-enabled}, false under %dev and %test) it
 * answers empty without dialling anything.
 */
@ApplicationScoped
public class DocsBearer {

  private static final Logger LOG = Logger.getLogger(DocsBearer.class);

  /** The mint is not the call: this bounds the hop to idp, not the hop to the store. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @Inject
  @NamedOidcClient("qits")
  OidcClient qits;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled", defaultValue = "false")
  boolean enabled;

  /** Caches and refreshes the token, so a page of forty assets is not forty token requests. */
  private final TokensHelper helper = new TokensHelper();

  /** The bearer, or empty when the client is off or cannot mint. */
  public Optional<String> token() {
    if (!enabled) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              helper.getTokens(qits).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for qits-artifacts: %s", e.toString());
      return Optional.empty();
    }
  }
}
