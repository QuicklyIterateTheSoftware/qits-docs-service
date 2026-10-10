package eu.wohlben.qits.docs.contracts;

import eu.wohlben.qits.docs.stories.support.StoryProfile;
import eu.wohlben.qits.docs.stories.support.StoryStore;
import java.util.Map;

/**
 * {@link StoryProfile}, for the in-process {@code @QuarkusTest}s of the contract.
 *
 * <p>Under {@code @QuarkusTest} the profile runs inside one of Quarkus' own classloaders, and a
 * {@link StoryStore} started there does not answer once the tests run (its handler hangs up without
 * a byte). So the store is started from the JVM's application classloader first — the one copy that
 * lives as long as the JVM — and the profile then finds its port in the shared system property. The
 * stories run against the packaged artifact and never meet this.
 */
public class ContractProfile extends StoryProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    try {
      ClassLoader.getSystemClassLoader()
          .loadClass(StoryStore.class.getName())
          .getMethod("ensureStarted")
          .invoke(null);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("could not start the story store", e);
    }
    return super.getConfigOverrides();
  }
}
