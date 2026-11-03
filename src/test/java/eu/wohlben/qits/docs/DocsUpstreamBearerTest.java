package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>Every read of the store carries this service's bearer</b> (qits-1419): the edge in front of
 * registry.qits.&lt;domain&gt; refuses an anonymous request, so a read that went out bare would be
 * a 401 on every page. {@link IdpTokenMintPactTest} proves the token is minted; this proves the
 * request carries it, and that a client with nothing to give sends no header at all.
 */
class DocsUpstreamBearerTest {

  @Test
  void aReadCarriesTheMintedBearer() throws Exception {
    assertEquals(List.of("Bearer minted-token"), authorizationSent(Optional.of("minted-token")));
  }

  @Test
  void aClientWithNothingToGiveSendsNoHeader() throws Exception {
    List<String> sent = authorizationSent(Optional.empty());
    assertEquals(List.of("<none>"), sent);
  }

  private static List<String> authorizationSent(Optional<String> token) throws Exception {
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String header = exchange.getRequestHeaders().getFirst("Authorization");
          seen.add(header == null ? "<none>" : header);
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    try {
      DocsUpstream upstream =
          ArtifactsContract.upstreamAgainst("http://127.0.0.1:" + server.getAddress().getPort());
      upstream.bearer =
          new DocsBearer() {
            @Override
            public Optional<String> token() {
              return token;
            }
          };
      upstream.versions("contract-docs-site");
    } finally {
      server.stop(0);
    }
    return List.copyOf(seen);
  }
}
