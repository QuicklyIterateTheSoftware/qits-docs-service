package eu.wohlben.qits.docs.contracts;

import eu.wohlben.qits.docs.stories.support.StoryStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>The provider states qits-docs answers for</b> (ticket qits-1149) — what a consumer of {@code
 * /docs/api/*} may name in a pact, and what {@code GoldenMasterRecordingTest} records.
 *
 * <p>qits-docs holds no state of its own: every answer is read from qits-artifacts. So a state here
 * is a set of params into {@link StoryStore}'s fixed seed, the stand-in for qits-artifacts this
 * repository's tests already run against. Nothing is set up or torn down per state.
 */
public final class ProviderStates {

  /** {@code @qits/ui-components}: three versions on {@code main}. */
  public static final String A_PUBLISHED_DOCS_SITE = "a published docs site";

  /** {@code @userflows/qits-githost}: one version on {@code main}, one on another branch. */
  public static final String A_DOCS_SITE_PUBLISHED_FROM_TWO_BRANCHES =
      "a docs site published from two branches";

  /** A site name the store has never held. */
  public static final String NO_SUCH_DOCS_SITE = "no such docs site";

  private static final Map<String, Map<String, String>> PARAMS = new LinkedHashMap<>();

  static {
    PARAMS.put(
        A_PUBLISHED_DOCS_SITE,
        Map.of("site", StoryStore.UI_SITE, "version", StoryStore.UI_NEWEST_SEEDED));
    PARAMS.put(
        A_DOCS_SITE_PUBLISHED_FROM_TWO_BRANCHES,
        Map.of("site", StoryStore.GITHOST_USERFLOWS, "branch", StoryStore.MAIN_BRANCH));
    PARAMS.put(NO_SUCH_DOCS_SITE, Map.of("site", StoryStore.UNKNOWN_SITE, "version", "2026.101.0"));
  }

  private ProviderStates() {}

  public static List<String> names() {
    return List.copyOf(PARAMS.keySet());
  }

  /** The state's params, in a stable (sorted) order. */
  public static Map<String, String> params(String state) {
    Map<String, String> params = PARAMS.get(state);
    if (params == null) {
      throw new IllegalArgumentException("qits-docs answers for no state '" + state + "'");
    }
    return new java.util.TreeMap<>(params);
  }
}
