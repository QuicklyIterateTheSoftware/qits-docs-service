package eu.wohlben.qits.docs.contracts;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.pact.consumer.GoldenFiles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * <b>qits-docs' golden masters</b> (ticket qits-1149, epic qits-546): what {@code /docs/api/*}
 * answers in each {@link ProviderStates provider state}, recorded into {@code golden-masters/} —
 * {@code index.json} (format 1, the shape qits-projects writes) plus one file per (state,
 * operation). The release publishes the tree as {@code eu.wohlben.qits:qits-docs-golden-masters}
 * and {@code @qits/qits-docs-golden-masters}, for a consumer's pact to take its answers from.
 *
 * <p>Compare by default; {@code -Dgolden.update=true} rewrites the tree (see {@link GoldenFiles}).
 *
 * <p>The routes are raw Vert.x routes with no openapi document, so the operationIds below are this
 * provider's names for them. A request's query is recorded under {@code query}, with {@code
 * {param}} placeholders, since two of the three routes take their arguments there.
 */
@QuarkusTest
@TestProfile(ContractProfile.class)
class GoldenMasterRecordingTest {

  static final String PROVIDER = "qits-docs";

  static final String LIST_SITES = "listSites";
  static final String LIST_VERSIONS = "listVersions";
  static final String GET_VERSION = "getVersion";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  /** One recorded request: the route, its query template, and the frozen paths of its answer. */
  record Recorded(
      String operationId,
      String path,
      Map<String, String> query,
      int status,
      List<String> instants) {}

  /** What each state records, in index order. */
  static final Map<String, List<Recorded>> RECORDINGS = new LinkedHashMap<>();

  static {
    List<String> versionInstants = List.of("$.versions[*].publishedAt");
    RECORDINGS.put(
        ProviderStates.A_PUBLISHED_DOCS_SITE,
        List.of(
            new Recorded(LIST_SITES, "/docs/api/sites", Map.of(), 200, List.of()),
            new Recorded(
                LIST_VERSIONS,
                "/docs/api/versions",
                Map.of("site", "{site}"),
                200,
                versionInstants),
            new Recorded(
                GET_VERSION,
                "/docs/api/version",
                ordered("site", "{site}", "version", "{version}"),
                200,
                List.of("$.publishedAt"))));
    RECORDINGS.put(
        ProviderStates.A_DOCS_SITE_PUBLISHED_FROM_TWO_BRANCHES,
        List.of(
            new Recorded(
                LIST_VERSIONS,
                "/docs/api/versions",
                ordered("site", "{site}", "branch", "{branch}"),
                200,
                versionInstants)));
    RECORDINGS.put(
        ProviderStates.NO_SUCH_DOCS_SITE,
        List.of(
            new Recorded(
                LIST_VERSIONS, "/docs/api/versions", Map.of("site", "{site}"), 404, List.of()),
            new Recorded(
                GET_VERSION,
                "/docs/api/version",
                ordered("site", "{site}", "version", "{version}"),
                404,
                List.of())));
  }

  @Test
  void theCommittedGoldenMastersAreWhatQitsDocsAnswers() {
    // A single-module repository: surefire runs in the repository root.
    Path root = Path.of("golden-masters").toAbsolutePath();
    List<String> failures = new ArrayList<>();
    JsonArray states = new JsonArray();
    for (Map.Entry<String, List<Recorded>> entry : RECORDINGS.entrySet()) {
      String state = entry.getKey();
      String slug = state.replace(' ', '-');
      Map<String, String> params = ProviderStates.params(state);
      JsonArray operations = new JsonArray();
      for (Recorded recorded : entry.getValue()) {
        Map<String, String> query = new LinkedHashMap<>();
        recorded.query().forEach((name, value) -> query.put(name, substitute(value, params)));
        Response response = given().queryParams(query).get(recorded.path());
        assertEquals(
            recorded.status(), response.statusCode(), state + "/" + recorded.operationId());
        boolean json = response.contentType().startsWith("application/json");
        String file = slug + "/" + recorded.operationId() + (json ? ".json" : ".txt");
        String body =
            json
                ? new JsonObject(response.asString()).encodePrettily() + "\n"
                : response.asString() + "\n";
        check(root.resolve(file), body, failures);
        operations.add(
            new JsonObject()
                .put("operationId", recorded.operationId())
                .put("method", "GET")
                .put("path", recorded.path())
                .put("query", new JsonObject(new LinkedHashMap<String, Object>(recorded.query())))
                .put("status", recorded.status())
                .put("file", file)
                .put(
                    "frozen",
                    new JsonObject()
                        .put("ids", new JsonArray())
                        .put("instants", new JsonArray(new ArrayList<Object>(recorded.instants())))
                        .put("strings", new JsonArray())
                        .putNull("listFilteredTo")));
      }
      states.add(
          new JsonObject()
              .put("name", state)
              .put("slug", slug)
              .put("params", new JsonObject(new LinkedHashMap<String, Object>(params)))
              .put("dependsOn", new JsonArray())
              .put("operations", operations));
    }
    JsonObject index =
        new JsonObject().put("formatVersion", 1).put("provider", PROVIDER).put("states", states);
    check(root.resolve("index.json"), index.encodePrettily() + "\n", failures);
    if (!failures.isEmpty()) {
      throw new AssertionError(String.join("\n\n", failures));
    }
  }

  private static void check(Path golden, String actual, List<String> failures) {
    String failure =
        GoldenFiles.check(
            golden, actual, GoldenFiles.updating(), java.util.function.UnaryOperator.identity());
    if (failure != null) {
      failures.add(failure);
    }
  }

  private static Map<String, String> ordered(String k1, String v1, String k2, String v2) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(k1, v1);
    map.put(k2, v2);
    return map;
  }

  private static String substitute(String template, Map<String, String> params) {
    Matcher m = PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(out, Matcher.quoteReplacement(params.get(m.group(1))));
    }
    m.appendTail(out);
    return out.toString();
  }
}
