package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.vertx.core.json.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * <b>What qits-docs asks qits-artifacts, and what it reads of the answer</b> (ticket qits-1149,
 * epic qits-546) — the one table both {@code ArtifactsConsumerPactTest} (each row against a pact
 * mock server) and {@code ArtifactsPactFileTest} (the committed {@code
 * pacts/qits-docs-service_qits-artifacts-service.json}) are built from.
 *
 * <p>{@link DocsUpstream} makes every call this service makes to another service: four GETs on
 * qits-artifacts' docs repository. One row per (trigger, call, state). The request comes from
 * qits-artifacts' recording; a row names only what this service reads of the answer.
 */
final class ArtifactsContract {

  static final String CONSUMER = "qits-docs-service";

  static final GoldenMasters ARTIFACTS =
      GoldenMasters.of("qits-artifacts-service", "qits-artifacts");

  // --- qits-artifacts' provider states ----------------------------------------------------------

  /** One site with one version on {@code main}, carrying metadata and one file. */
  static final String A_PUBLISHED_DOCS_SITE = "a published docs site";

  /** A site with versions on two branches, asked with the branch filter. */
  static final String PUBLISHED_FROM_TWO_BRANCHES = "a docs site published from two branches";

  /** A store that knows no site, version or file under the state's params. */
  static final String NO_DOCS_BUNDLE = "no docs bundle published for the coordinate";

  static final String LIST_DOCS_SITES = "listDocsSites";
  static final String LIST_DOCS_VERSIONS = "listDocsVersions";
  static final String GET_DOCS_VERSION = "getDocsVersion";
  static final String GET_DOCS_FILE = "getDocsFile";

  // --- the triggers: this service's own routes --------------------------------------------------

  private static final Trigger SITES_ROUTE = Trigger.operation("GET /docs/api/sites");
  private static final Trigger LATEST_ROUTE = Trigger.operation("GET /docs/{site}");
  private static final Trigger VERSIONS_ROUTE = Trigger.operation("GET /docs/api/versions");
  private static final Trigger VERSION_ROUTE = Trigger.operation("GET /docs/api/version");
  private static final Trigger FILE_ROUTE =
      Trigger.operation("GET /docs/{site}/-/{version}/{path}");

  // --- the rows ---------------------------------------------------------------------------------

  /** {@code GET <store>}: the catalog, grouped by scope in {@code /docs/api/sites}. */
  static final GoldenInteraction CATALOG =
      GoldenInteraction.of(SITES_ROUTE, A_PUBLISHED_DOCS_SITE, LIST_DOCS_SITES)
          .consumes("sites[].name", "sites[].versionCount", "sites[].latestVersion");

  /** {@code GET <store>/<site>}: only the version names, to resolve {@code latest}. */
  static final GoldenInteraction LATEST =
      GoldenInteraction.of(LATEST_ROUTE, A_PUBLISHED_DOCS_SITE, LIST_DOCS_VERSIONS)
          .consumes("versions[].version")
          .anyLength("versions");

  /** {@code GET <store>/<site>}, 404: no such site, so {@code latest} answers 404 too. */
  static final GoldenInteraction LATEST_UNKNOWN =
      GoldenInteraction.of(LATEST_ROUTE, NO_DOCS_BUNDLE, LIST_DOCS_VERSIONS);

  private static final String[] VERSION_DETAILS = {
    "versions[].version",
    "versions[].fileCount",
    "versions[].totalBytes",
    "versions[].publishedAt",
    "versions[].metadata"
  };

  /** {@code GET <store>/<site>}: the versions with their figures, metadata passed through. */
  static final GoldenInteraction DETAILS =
      GoldenInteraction.of(VERSIONS_ROUTE, A_PUBLISHED_DOCS_SITE, LIST_DOCS_VERSIONS)
          .consumes(VERSION_DETAILS)
          .anyLength("versions");

  /** {@code GET <store>/<site>?meta.git.branch.name=<branch>}: the same, pushed upstream. */
  static final GoldenInteraction DETAILS_ON_A_BRANCH =
      GoldenInteraction.of(VERSIONS_ROUTE, PUBLISHED_FROM_TWO_BRANCHES, LIST_DOCS_VERSIONS)
          .consumes(VERSION_DETAILS)
          .anyLength("versions");

  static final GoldenInteraction DETAILS_UNKNOWN =
      GoldenInteraction.of(VERSIONS_ROUTE, NO_DOCS_BUNDLE, LIST_DOCS_VERSIONS);

  /**
   * {@code GET <store>/<site>/-/<version>}: passed through verbatim to the client, which reads
   * {@code files} and {@code metadata} to decide how to show the bundle.
   */
  static final GoldenInteraction DOCUMENT =
      GoldenInteraction.of(VERSION_ROUTE, A_PUBLISHED_DOCS_SITE, GET_DOCS_VERSION)
          .consumes("files", "metadata")
          .anyLength("files");

  static final GoldenInteraction DOCUMENT_UNKNOWN =
      GoldenInteraction.of(VERSION_ROUTE, NO_DOCS_BUNDLE, GET_DOCS_VERSION);

  /**
   * {@code GET <store>/<site>/-/<version>/<path>}: the bytes are streamed, the status read, and
   * {@code Content-Type} and {@code ETag} passed on. {@code Content-Length} is passed on too, but a
   * pact with no body cannot bind it.
   */
  static final GoldenInteraction FILE =
      GoldenInteraction.of(FILE_ROUTE, A_PUBLISHED_DOCS_SITE, GET_DOCS_FILE)
          .readsHeader("Content-Type", "ETag");

  static final GoldenInteraction FILE_UNKNOWN =
      GoldenInteraction.of(FILE_ROUTE, NO_DOCS_BUNDLE, GET_DOCS_FILE);

  static final ConsumerPact PACT =
      ConsumerPact.of(
          CONSUMER,
          ARTIFACTS,
          CATALOG,
          LATEST,
          LATEST_UNKNOWN,
          DETAILS,
          DETAILS_ON_A_BRANCH,
          DETAILS_UNKNOWN,
          DOCUMENT,
          DOCUMENT_UNKNOWN,
          FILE,
          FILE_UNKNOWN);

  // --- what this service does with each row's answer --------------------------------------------

  /** What {@link DocsUpstream} does for one row, asserting what it read. */
  @FunctionalInterface
  interface Call {
    void run(DocsUpstream upstream, Map<String, String> params, GoldenInteraction row);
  }

  static final Map<GoldenInteraction, Call> CALLS =
      Map.ofEntries(
          Map.entry(CATALOG, ArtifactsContract::catalog),
          Map.entry(LATEST, ArtifactsContract::latest),
          Map.entry(
              LATEST_UNKNOWN,
              (upstream, params, row) ->
                  assertTrue(upstream.versions(params.get("site")).isEmpty())),
          Map.entry(DETAILS, ArtifactsContract::details),
          Map.entry(DETAILS_ON_A_BRANCH, ArtifactsContract::details),
          Map.entry(
              DETAILS_UNKNOWN,
              (upstream, params, row) ->
                  assertNull(upstream.versionDetails(params.get("site"), null))),
          Map.entry(DOCUMENT, ArtifactsContract::document),
          Map.entry(
              DOCUMENT_UNKNOWN,
              (upstream, params, row) ->
                  assertNull(upstream.versionDocument(params.get("site"), params.get("version")))),
          Map.entry(FILE, ArtifactsContract::file),
          Map.entry(FILE_UNKNOWN, ArtifactsContract::file));

  private static void catalog(
      DocsUpstream upstream, Map<String, String> params, GoldenInteraction row) {
    List<DocsUpstream.CatalogEntry> catalog = upstream.catalog();
    JsonNode sites = recorded(row).path("sites");
    assertEquals(sites.size(), catalog.size());
    JsonNode first = sites.get(0);
    assertEquals(first.path("name").asText(), catalog.get(0).name());
    assertEquals(first.path("versionCount").asInt(), catalog.get(0).versionCount());
    assertEquals(first.path("latestVersion").asText(), catalog.get(0).latestVersion());
  }

  private static void latest(
      DocsUpstream upstream, Map<String, String> params, GoldenInteraction row) {
    List<String> versions = upstream.versions(params.get("site"));
    JsonNode recorded = recorded(row).path("versions");
    assertEquals(recorded.size(), versions.size());
    assertEquals(recorded.get(0).path("version").asText(), versions.get(0));
  }

  private static void details(
      DocsUpstream upstream, Map<String, String> params, GoldenInteraction row) {
    // The branch is pushed upstream as the store's own filter; the recording names the query.
    String branch = params.get("branch");
    List<DocsUpstream.Version> details = upstream.versionDetails(params.get("site"), branch);
    assertNotNull(details);
    JsonNode first = recorded(row).path("versions").get(0);
    DocsUpstream.Version read = details.get(0);
    assertEquals(first.path("version").asText(), read.version());
    assertEquals(first.path("fileCount").asInt(), read.fileCount());
    assertEquals(first.path("totalBytes").asLong(), read.totalBytes());
    assertEquals(first.path("publishedAt").asText(), read.publishedAt());
    assertEquals(new JsonObject(first.path("metadata").toString()), read.metadata());
  }

  private static void document(
      DocsUpstream upstream, Map<String, String> params, GoldenInteraction row) {
    JsonObject document = upstream.versionDocument(params.get("site"), params.get("version"));
    assertNotNull(document);
    JsonNode recorded = recorded(row);
    assertEquals(recorded.path("files").size(), document.getJsonArray("files").size());
    assertEquals(
        new JsonObject(recorded.path("metadata").toString()), document.getJsonObject("metadata"));
  }

  private static void file(
      DocsUpstream upstream, Map<String, String> params, GoldenInteraction row) {
    GoldenMasters.Operation op = ARTIFACTS.operation(row.state(), row.operationId());
    try (DocsUpstream.Fetched fetched =
        upstream.fetch(params.get("site"), params.get("version"), params.get("path"))) {
      assertEquals(op.status(), fetched.status());
      for (String header : row.readsHeaders()) {
        String read = "ETag".equals(header) ? fetched.etag() : fetched.contentType();
        assertEquals(op.responseHeader(header), read, header);
      }
    }
  }

  private static JsonNode recorded(GoldenInteraction row) {
    return ARTIFACTS.json(row.state(), row.operationId());
  }

  /** The bean as CDI would build it, against the mock server's address (no path). */
  static DocsUpstream upstreamAgainst(String baseUrl) {
    DocsUpstream upstream = new DocsUpstream();
    upstream.artifactsUrl = baseUrl;
    upstream.connectTimeout = Duration.ofSeconds(2);
    upstream.requestTimeout = Duration.ofSeconds(10);
    upstream.open();
    return upstream;
  }

  private ArtifactsContract() {}
}
