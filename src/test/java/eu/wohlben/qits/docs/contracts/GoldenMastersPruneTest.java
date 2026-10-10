package eu.wohlben.qits.docs.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.json.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pact binds only what is read: {@link GoldenMasters#prune}'s rules, one case each. */
class GoldenMastersPruneTest {

  private static final JsonObject BODY =
      new JsonObject(
          """
          {"name":"s","versions":[
            {"version":"2","fileCount":3,"metadata":{"git.branch.name":"main"}},
            {"version":"1","fileCount":2}]}
          """);

  @Test
  void keepsOnlyTheConsumedMembers() {
    assertEquals(
        new JsonObject("{\"versions\":[{\"version\":\"2\"},{\"version\":\"1\"}]}"),
        GoldenMasters.prune(BODY, List.of("$.versions[*].version"), "test"));
  }

  @Test
  void anObjectAtTheEndOfAPathBindsOnlyThatItIsAnObject() {
    assertEquals(
        new JsonObject("{\"versions\":[{\"metadata\":{}},{}]}"),
        GoldenMasters.prune(BODY, List.of("$.versions[*].metadata"), "test"));
  }

  @Test
  void theRootAloneBindsNothing() {
    assertEquals(new JsonObject(), GoldenMasters.prune(BODY, List.of("$"), "test"));
  }

  @Test
  void anUnreadablePathIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GoldenMasters.prune(BODY, List.of("$.versions[0].version"), "test"));
  }
}
