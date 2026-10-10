package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import eu.wohlben.qits.docs.contracts.GoldenMasters;
import eu.wohlben.qits.docs.contracts.GoldenMasters.Trigger;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-docs asks qits-artifacts, and what it reads of the answer</b> (ticket qits-1149,
 * epic qits-546) — the one table both {@code ArtifactsConsumerPactTest} (each row against a pact
 * mock server) and {@code ArtifactsPactFileTest} (the committed {@code
 * pacts/qits-docs-service_qits-artifacts-service.json}) are built from.
 *
 * <p>{@link DocsUpstream} makes every call this service makes to another service: four GETs on
 * qits-artifacts' docs repository. One row per (trigger, call, state). The trigger is this
 * service's own route; qits-artifacts' doors are raw Vert.x routes with no openapi, so the
 * operationIds here are the names this contract proposes for them.
 *
 * <p><b>Every row waits on qits-artifacts.</b> It publishes no golden masters yet, so each row
 * skips naming the provider state it needs — see {@link GoldenMasters#waitingFor}.
 */
final class ArtifactsContract {

  /** One site with versions from two branches, each carrying metadata. */
  static final String A_PUBLISHED_DOCS_SITE = "a published docs site";

  /** The same, asked with the branch filter: the recording must hold the filtered answer. */
  static final String A_DOCS_SITE_FILTERED_TO_A_BRANCH = "a docs site filtered to a branch";

  /** A store that knows no site, version or file under the state's params. */
  static final String A_DOCS_STORE_WITHOUT_THE_SITE = "a docs store without the site";

  static final String LIST_DOCS_SITES = "listDocsSites";
  static final String LIST_DOCS_VERSIONS = "listDocsVersions";
  static final String GET_DOCS_VERSION = "getDocsVersion";
  static final String GET_DOCS_FILE = "getDocsFile";

  /** What this service does with {@link DocsUpstream} for one row, asserting what it read. */
  @FunctionalInterface
  interface Call {
    void run(DocsUpstream upstream, Map<String, String> params, Case row);
  }

  record Case(
      Trigger trigger,
      String state,
      String operationId,
      Map<String, String> query,
      List<String> consumes,
      Call call) {

    String description() {
      return GoldenMasters.description(operationId, trigger);
    }

    boolean recorded() {
      return GoldenMasters.recorded(state, operationId);
    }

    String waitingFor() {
      return GoldenMasters.waitingFor(state, operationId);
    }
  }

  // --- what each call reads ---------------------------------------------------------------------

  private static final List<String> CATALOG =
      List.of("$.sites[*].name", "$.sites[*].versionCount", "$.sites[*].latestVersion");

  private static final List<String> VERSION_NAMES = List.of("$.versions[*].version");

  private static final List<String> VERSION_DETAILS =
      List.of(
          "$.versions[*].version",
          "$.versions[*].fileCount",
          "$.versions[*].totalBytes",
          "$.versions[*].publishedAt",
          "$.versions[*].metadata");

  /** The version document is passed through verbatim: this service reads only "an object". */
  private static final List<String> A_JSON_OBJECT = List.of("$");

  private static final List<String> STATUS_ONLY = List.of();

  private static final Map<String, String> NO_QUERY = Map.of();

  private static final Map<String, String> BRANCH_QUERY =
      Map.of("meta.git.branch.name", "{branch}");

  // --- the calls --------------------------------------------------------------------------------

  /** {@code GET <store>}: the catalog, grouped by scope in {@code /docs/api/sites}. */
  private static final Call CATALOG_CALL =
      (upstream, params, row) -> {
        List<DocsUpstream.CatalogEntry> catalog = upstream.catalog();
        JsonArray sites = recorded(row).getJsonArray("sites");
        assertEquals(sites.size(), catalog.size());
        JsonObject first = sites.getJsonObject(0);
        assertEquals(first.getString("name"), catalog.get(0).name());
        assertEquals(first.getInteger("versionCount"), catalog.get(0).versionCount());
        assertEquals(first.getString("latestVersion"), catalog.get(0).latestVersion());
      };

  /** {@code GET <store>/<site>}: only the version names, to resolve {@code latest}. */
  private static final Call LATEST_CALL =
      (upstream, params, row) -> {
        List<String> versions = upstream.versions(params.get("site"));
        JsonArray recorded = recorded(row).getJsonArray("versions");
        assertEquals(recorded.size(), versions.size());
        assertEquals(recorded.getJsonObject(0).getString("version"), versions.get(0));
      };

  /** {@code GET <store>/<site>}, 404: no such site, so {@code latest} answers 404 too. */
  private static final Call LATEST_UNKNOWN_CALL =
      (upstream, params, row) -> assertTrue(upstream.versions(params.get("site")).isEmpty());

  /** {@code GET <store>/<site>[?meta.git.branch.name=]}: the versions with their figures. */
  private static final Call DETAILS_CALL =
      (upstream, params, row) -> {
        String branch = row.query().isEmpty() ? null : params.get("branch");
        List<DocsUpstream.Version> details = upstream.versionDetails(params.get("site"), branch);
        assertNotNull(details);
        JsonObject first = recorded(row).getJsonArray("versions").getJsonObject(0);
        assertEquals(first.getString("version"), details.get(0).version());
        assertEquals(first.getInteger("fileCount"), details.get(0).fileCount());
        assertEquals(first.getLong("totalBytes"), details.get(0).totalBytes());
        assertEquals(first.getString("publishedAt"), details.get(0).publishedAt());
      };

  private static final Call DETAILS_UNKNOWN_CALL =
      (upstream, params, row) -> assertNull(upstream.versionDetails(params.get("site"), null));

  /** {@code GET <store>/<site>/-/<version>}: passed through, so only "is it an object". */
  private static final Call DOCUMENT_CALL =
      (upstream, params, row) ->
          assertNotNull(upstream.versionDocument(params.get("site"), params.get("version")));

  private static final Call DOCUMENT_UNKNOWN_CALL =
      (upstream, params, row) ->
          assertNull(upstream.versionDocument(params.get("site"), params.get("version")));

  /** {@code GET <store>/<site>/-/<version>/<path>}: the bytes are streamed, the status read. */
  private static Call fileCall(int status) {
    return (upstream, params, row) -> {
      try (DocsUpstream.Fetched fetched =
          upstream.fetch(params.get("site"), params.get("version"), params.get("path"))) {
        assertEquals(status, fetched.status());
      }
    };
  }

  private static final String SITES_ROUTE = "GET /docs/api/sites";
  private static final String LATEST_ROUTE = "GET /docs/{site}";
  private static final String VERSIONS_ROUTE = "GET /docs/api/versions";
  private static final String VERSION_ROUTE = "GET /docs/api/version";
  private static final String FILE_ROUTE = "GET /docs/{site}/-/{version}/{path}";

  static final List<Case> CASES =
      List.of(
          new Case(
              Trigger.route(SITES_ROUTE),
              A_PUBLISHED_DOCS_SITE,
              LIST_DOCS_SITES,
              NO_QUERY,
              CATALOG,
              CATALOG_CALL),
          new Case(
              Trigger.route(LATEST_ROUTE),
              A_PUBLISHED_DOCS_SITE,
              LIST_DOCS_VERSIONS,
              NO_QUERY,
              VERSION_NAMES,
              LATEST_CALL),
          new Case(
              Trigger.route(LATEST_ROUTE),
              A_DOCS_STORE_WITHOUT_THE_SITE,
              LIST_DOCS_VERSIONS,
              NO_QUERY,
              STATUS_ONLY,
              LATEST_UNKNOWN_CALL),
          new Case(
              Trigger.route(VERSIONS_ROUTE),
              A_PUBLISHED_DOCS_SITE,
              LIST_DOCS_VERSIONS,
              NO_QUERY,
              VERSION_DETAILS,
              DETAILS_CALL),
          new Case(
              Trigger.route(VERSIONS_ROUTE),
              A_DOCS_SITE_FILTERED_TO_A_BRANCH,
              LIST_DOCS_VERSIONS,
              BRANCH_QUERY,
              VERSION_DETAILS,
              DETAILS_CALL),
          new Case(
              Trigger.route(VERSIONS_ROUTE),
              A_DOCS_STORE_WITHOUT_THE_SITE,
              LIST_DOCS_VERSIONS,
              NO_QUERY,
              STATUS_ONLY,
              DETAILS_UNKNOWN_CALL),
          new Case(
              Trigger.route(VERSION_ROUTE),
              A_PUBLISHED_DOCS_SITE,
              GET_DOCS_VERSION,
              NO_QUERY,
              A_JSON_OBJECT,
              DOCUMENT_CALL),
          new Case(
              Trigger.route(VERSION_ROUTE),
              A_DOCS_STORE_WITHOUT_THE_SITE,
              GET_DOCS_VERSION,
              NO_QUERY,
              STATUS_ONLY,
              DOCUMENT_UNKNOWN_CALL),
          new Case(
              Trigger.route(FILE_ROUTE),
              A_PUBLISHED_DOCS_SITE,
              GET_DOCS_FILE,
              NO_QUERY,
              STATUS_ONLY,
              fileCall(200)),
          new Case(
              Trigger.route(FILE_ROUTE),
              A_DOCS_STORE_WITHOUT_THE_SITE,
              GET_DOCS_FILE,
              NO_QUERY,
              STATUS_ONLY,
              fileCall(404)));

  private ArtifactsContract() {}

  private static JsonObject recorded(Case row) {
    return GoldenMasters.json(row.state(), row.operationId());
  }

  /** The rows whose provider state qits-artifacts does not record yet. */
  static List<Case> waiting() {
    return CASES.stream().filter(c -> !c.recorded()).toList();
  }

  static V4Pact pact() {
    return pact(CASES);
  }

  static V4Pact pact(List<Case> cases) {
    PactBuilder builder =
        new PactBuilder(GoldenMasters.CONSUMER, GoldenMasters.PROVIDER, PactSpecVersion.V4);
    for (Case c : cases) {
      GoldenMasters.interaction(
          builder, c.state(), c.operationId(), c.trigger(), c.query(), c.consumes());
    }
    return builder.toPact();
  }
}
