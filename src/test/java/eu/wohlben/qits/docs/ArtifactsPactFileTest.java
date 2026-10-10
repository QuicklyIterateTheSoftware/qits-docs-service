package eu.wohlben.qits.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import au.com.dius.pact.core.model.DefaultPactWriter;
import au.com.dius.pact.core.model.PactSpecVersion;
import eu.wohlben.qits.docs.contracts.GoldenFiles;
import eu.wohlben.qits.docs.contracts.GoldenMasters;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * <b>The committed consumer pact, {@code pacts/qits-docs-service_qits-artifacts-service.json}</b>
 * (ticket qits-1149): what {@link ArtifactsContract} writes, normalised, compared byte for byte
 * ({@code -Dgolden.update=true} rewrites it), and the references every interaction must carry.
 *
 * <p>The file can only be written once qits-artifacts records every row's provider state — the
 * answers come from its golden masters, never from here. Until then both tests skip, listing the
 * states still missing.
 */
class ArtifactsPactFileTest {

  static final String FILE = GoldenMasters.CONSUMER + "_" + GoldenMasters.PROVIDER + ".json";

  @Test
  void theCommittedPactIsWhatTheContractWrites() throws IOException {
    assumeEveryRowRecorded();
    String raw = written();
    Path scratch = Path.of("target", "pacts", FILE);
    Files.createDirectories(scratch.getParent());
    Files.writeString(scratch, raw);
    GoldenFiles.compareOrWrite(
        GoldenFiles.repositoryRoot().resolve("pacts").resolve(FILE), normalise(raw));
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    assumeEveryRowRecorded();
    JsonObject pact = new JsonObject(normalise(written()));
    assertEquals(GoldenMasters.CONSUMER, pact.getJsonObject("consumer").getString("name"));
    assertEquals(GoldenMasters.PROVIDER, pact.getJsonObject("provider").getString("name"));
    JsonArray interactions = pact.getJsonArray("interactions");
    assertEquals(ArtifactsContract.CASES.size(), interactions.size(), "one interaction per row");
    Set<String> unique = new HashSet<>();
    for (Object each : interactions) {
      JsonObject interaction = (JsonObject) each;
      String description = interaction.getString("description");
      JsonObject references = interaction.getJsonObject("comments").getJsonObject("references");
      JsonObject call = references.getJsonObject("qits-call");
      assertEquals(GoldenMasters.PROVIDER, call.getString("app"), description);
      assertTrue(!call.getString("operationId", "").isBlank(), description);
      JsonObject trigger = references.getJsonObject("qits-trigger");
      assertEquals("operation", trigger.getString("kind"), description);
      assertEquals(GoldenMasters.CONSUMER, trigger.getString("app"), description);
      assertTrue(description.startsWith(trigger.getString("operationId") + ": "), description);
      String state = interaction.getJsonArray("providerStates").getJsonObject(0).getString("name");
      assertTrue(unique.add(description + "\u0000" + state), "(description, state) repeats");
    }
  }

  private static void assumeEveryRowRecorded() {
    List<ArtifactsContract.Case> waiting = ArtifactsContract.waiting();
    Assumptions.assumeTrue(
        waiting.isEmpty(),
        () ->
            "the pact waits on qits-artifacts' golden masters: "
                + waiting.stream()
                    .map(ArtifactsContract.Case::waitingFor)
                    .distinct()
                    .collect(Collectors.joining("; ")));
  }

  static String written() {
    StringWriter out = new StringWriter();
    try (PrintWriter writer = new PrintWriter(out)) {
      DefaultPactWriter.INSTANCE.writePact(ArtifactsContract.pact(), writer, PactSpecVersion.V4);
    }
    return out.toString();
  }

  /** Sorted by (description, state), pact-jvm's own version dropped, 2-space indented. */
  static String normalise(String raw) {
    JsonObject pact = new JsonObject(raw);
    JsonObject metadata = pact.getJsonObject("metadata");
    if (metadata != null) {
      metadata.remove("pact-jvm");
    }
    JsonArray interactions = pact.getJsonArray("interactions");
    if (interactions != null) {
      List<JsonObject> sorted = new ArrayList<>();
      interactions.forEach(i -> sorted.add((JsonObject) i));
      sorted.sort(
          Comparator.comparing((JsonObject i) -> i.getString("description"))
              .thenComparing(
                  i -> i.getJsonArray("providerStates").getJsonObject(0).getString("name")));
      pact.put("interactions", new JsonArray(new ArrayList<Object>(sorted)));
    }
    return pact.encodePrettily() + "\n";
  }
}
