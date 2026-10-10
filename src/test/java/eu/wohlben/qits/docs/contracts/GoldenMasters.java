package eu.wohlben.qits.docs.contracts;

import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.Matchers;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonArray;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.core.model.matchingrules.NullMatcher;
import au.com.dius.pact.core.model.matchingrules.RegexMatcher;
import au.com.dius.pact.core.model.matchingrules.TypeMatcher;
import au.com.dius.pact.core.support.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>qits-artifacts' recorded answers, as this service's consumer pact reads them</b> (ticket
 * qits-1149, epic qits-546).
 *
 * <p>A provider records what it answers for each provider state — {@code golden-masters/index.json}
 * (format 1, the shape qits-projects writes) plus one file per (state, operation) — and publishes
 * the tree as {@code eu.wohlben.qits:<application>-golden-masters}. This class reads it off the
 * test classpath and turns one recorded (state, operation) into a pact-jvm V4 interaction.
 *
 * <p><b>qits-artifacts publishes no golden masters yet.</b> Until it does, {@link #recorded}
 * answers false for every row and the contract tests skip, each naming the provider state it waits
 * for. The day the jar lands as a test dependency, the rows run with no edit here.
 *
 * <p><b>The pact binds only what this consumer reads.</b> Each row names its consumed body paths
 * ({@code $.versions[*].version}); the recording is pruned to them before matchers are built, so a
 * field the provider adds or drops that this service never reads cannot break the contract. A row
 * with no consumed path is status-only. The rules:
 *
 * <ul>
 *   <li>a consumed path ending at an OBJECT binds "is an object", never its members — that is how
 *       {@code metadata} and the version document are passed through verbatim;
 *   <li>a field missing from some recorded element of an array is not bound: the recording itself
 *       proves the provider may omit it;
 *   <li>an array of objects is {@code minArrayLike(1)} over the merge of its recorded elements;
 *   <li>{@code frozen.ids} paths get a UUID matcher, {@code frozen.instants} an ISO-8601 regex,
 *       every other leaf a type match ({@code or null} where some element held null).
 * </ul>
 */
public final class GoldenMasters {

  /** The consumer, as the pact names it: the repository name. */
  public static final String CONSUMER = "qits-docs-service";

  /** The provider, as the pact names it: the repository name. */
  public static final String PROVIDER = "qits-artifacts-service";

  /** The provider as its golden-master index names it: the application name. */
  public static final String INDEX_PROVIDER = "qits-artifacts";

  /** Where the provider's jar puts the tree on the classpath. */
  public static final String ROOT = "golden-masters/";

  /** An ISO-8601 timestamp, any fraction length, Z or a numeric offset. */
  public static final String ISO_INSTANT =
      "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})$";

  private static final String UUID_REGEX =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  private static final Pattern PARAM = Pattern.compile("\\{([A-Za-z0-9_]+)}");

  private GoldenMasters() {}

  // --- the index --------------------------------------------------------------------------------

  /** One recorded (state, operation), as the index describes it. */
  public record Operation(
      String state,
      Map<String, String> params,
      String operationId,
      String method,
      String path,
      int status,
      String file,
      Set<String> ids,
      Set<String> instants) {

    public String examplePath() {
      return substitute(path, params, params::get);
    }

    public String expressionPath() {
      return substitute(path, params, name -> "${" + name + "}");
    }

    /** A constant path must not get a provider-state generator (pact-jvm reads it as a key). */
    public boolean hasPathParams() {
      return PARAM.matcher(path).find();
    }
  }

  /** Whether the provider's jar is on the classpath and records this (state, operation). */
  public static boolean recorded(String state, String operationId) {
    JsonObject index = index();
    return index != null && find(index, state, operationId) != null;
  }

  /** The reason a row waits, in the words the inventory lists it under. */
  public static String waitingFor(String state, String operationId) {
    return "needs provider state '"
        + state
        + "' for "
        + operationId
        + " in "
        + PROVIDER
        + " (no "
        + ROOT
        + "index.json recording it on the test classpath)";
  }

  /** The provider state's frozen example params. */
  public static Map<String, String> params(String state) {
    Map<String, String> params = new LinkedHashMap<>();
    JsonObject node = stateNode(requireIndex(), state);
    JsonObject recorded = node.getJsonObject("params", new JsonObject());
    for (String name : recorded.fieldNames()) {
      params.put(name, String.valueOf(recorded.getValue(name)));
    }
    return params;
  }

  public static Operation operation(String state, String operationId) {
    JsonObject index = requireIndex();
    JsonObject op = find(index, state, operationId);
    if (op == null) {
      throw new IllegalArgumentException(waitingFor(state, operationId));
    }
    JsonObject frozen = op.getJsonObject("frozen", new JsonObject());
    return new Operation(
        state,
        params(state),
        operationId,
        op.getString("method"),
        op.getString("path"),
        op.getInteger("status"),
        op.getString("file"),
        strings(frozen.getJsonArray("ids")),
        strings(frozen.getJsonArray("instants")));
  }

  /** The recorded JSON body for one (state, operation). */
  public static JsonObject json(String state, String operationId) {
    return new JsonObject(resource(ROOT + operation(state, operationId).file()));
  }

  // --- the pact ---------------------------------------------------------------------------------

  /**
   * What made this service make the call — the {@code qits-trigger} reference. qits-docs has no
   * openapi document; its routes are raw Vert.x routes, so the {@code operation} kind names the
   * route ({@code GET /docs/api/sites}) where another service names an operationId.
   */
  public record Trigger(String kind, String app, String key, String value) {

    public Trigger {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(app, "app");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }

    public static Trigger route(String route) {
      return new Trigger("operation", CONSUMER, "operationId", route);
    }

    public Map<String, String> reference() {
      Map<String, String> ref = new LinkedHashMap<>();
      ref.put("kind", kind);
      ref.put("app", app);
      ref.put(key, value);
      return ref;
    }
  }

  /** The interaction's description: the trigger first, so (description, state) stays unique. */
  public static String description(String operationId, Trigger trigger) {
    return trigger.value() + ": " + operationId;
  }

  /**
   * Add the V4 HTTP interaction for one recorded (state, operation), reached from {@code trigger}.
   *
   * @param query the request's query, this consumer's own; a {@code "{param}"} value is the state's
   *     frozen example
   * @param consumes the body paths this consumer reads; empty for a status-only interaction
   */
  public static PactBuilder interaction(
      PactBuilder builder,
      String state,
      String operationId,
      Trigger trigger,
      Map<String, String> query,
      List<String> consumes) {
    Objects.requireNonNull(
        trigger, "trigger: every interaction names the entry point that makes it");
    Operation op = operation(state, operationId);
    DslPart body = consumes.isEmpty() ? null : responseBody(op, consumes);
    Map<String, Object> references = new LinkedHashMap<>();
    Map<String, String> call = new LinkedHashMap<>();
    call.put("app", PROVIDER);
    call.put("operationId", operationId);
    references.put("qits-call", call);
    references.put("qits-trigger", trigger.reference());
    return builder.expectsToReceiveHttpInteraction(
        description(operationId, trigger),
        http -> {
          http.state(state, new LinkedHashMap<String, Object>(op.params()));
          http.withRequest(
              request -> {
                request.method(op.method());
                if (op.hasPathParams()) {
                  request.path(Matchers.fromProviderState(op.expressionPath(), op.examplePath()));
                } else {
                  request.path(op.examplePath());
                }
                query.forEach(
                    (name, value) ->
                        request.queryParameter(
                            name, substitute(value, op.params(), op.params()::get)));
                return request;
              });
          http.willRespondWith(
              response -> {
                response.status(op.status());
                if (body != null) {
                  response
                      .header(
                          "Content-Type", Matchers.regexp("application/json.*", "application/json"))
                      .body(body);
                }
                return response;
              });
          // pact-jvm 4.6's DSL has no setter for an arbitrary comment group, but the V4 model's
          // comments map is mutable and written verbatim.
          http.getInteraction().getComments().put("references", Json.toJson(references));
          return http;
        });
  }

  /** The recording pruned to {@code consumes}, with matchers. */
  static DslPart responseBody(Operation op, List<String> consumes) {
    JsonObject pruned = prune(json(op.state(), op.operationId()), consumes, op);
    PactDslJsonBody root = new PactDslJsonBody();
    fillObject(root, pruned, "$", op);
    return root;
  }

  // --- pruning to what is read ------------------------------------------------------------------

  /**
   * The recording reduced to the consumed paths. A path is {@code $}, then {@code .name} or {@code
   * [*]} steps. An object at the end of a path is kept empty: "is an object", never its members.
   */
  public static JsonObject prune(JsonObject recorded, List<String> consumes, Object where) {
    JsonObject out = new JsonObject();
    for (String path : consumes) {
      if (!path.startsWith("$")) {
        throw new IllegalArgumentException(where + ": a consumed path starts with $, got " + path);
      }
      List<String> steps = steps(path.substring(1));
      if (steps.isEmpty()) {
        continue; // "$": the root is an object, which the empty body already says.
      }
      copy(recorded, out, steps, path, where);
    }
    return out;
  }

  private static List<String> steps(String rest) {
    List<String> steps = new ArrayList<>();
    Matcher m = Pattern.compile("\\.([A-Za-z0-9_]+)|\\[\\*]").matcher(rest);
    int at = 0;
    while (m.find()) {
      if (m.start() != at) {
        throw new IllegalArgumentException("unreadable consumed path: $" + rest);
      }
      steps.add(m.group(1) == null ? "[*]" : m.group(1));
      at = m.end();
    }
    if (at != rest.length()) {
      throw new IllegalArgumentException("unreadable consumed path: $" + rest);
    }
    return steps;
  }

  private static void copy(
      JsonObject from, JsonObject to, List<String> steps, String path, Object where) {
    String name = steps.get(0);
    if (!from.containsKey(name)) {
      return; // Absent in this recording (or this element): nothing to bind.
    }
    Object value = from.getValue(name);
    List<String> rest = steps.subList(1, steps.size());
    if (rest.isEmpty()) {
      to.put(name, value instanceof JsonObject ? new JsonObject() : value);
      return;
    }
    if (value instanceof JsonObject child) {
      JsonObject target = to.getJsonObject(name);
      if (target == null) {
        target = new JsonObject();
        to.put(name, target);
      }
      copy(child, target, rest, path, where);
    } else if (value instanceof JsonArray array && "[*]".equals(rest.get(0))) {
      List<String> inner = rest.subList(1, rest.size());
      JsonArray target = to.getJsonArray(name);
      if (target == null) {
        target = new JsonArray();
        for (int i = 0; i < array.size(); i++) {
          target.add(new JsonObject());
        }
        to.put(name, target);
      }
      for (int i = 0; i < array.size(); i++) {
        if (inner.isEmpty() || !(array.getValue(i) instanceof JsonObject element)) {
          throw new IllegalStateException(
              where + ": " + path + " must reach a member of an array's objects");
        }
        copy(element, target.getJsonObject(i), inner, path, where);
      }
    } else {
      throw new IllegalStateException(where + ": " + path + " does not fit the recording");
    }
  }

  // --- matchers ---------------------------------------------------------------------------------

  private static void fillObject(
      PactDslJsonBody target, JsonObject object, String path, Operation op) {
    for (String name : object.fieldNames()) {
      Object value = object.getValue(name);
      String childPath = path + "." + name;
      if (value == null) {
        target.nullValue(name);
      } else if (value instanceof JsonObject child) {
        PactDslJsonBody nested = target.object(name);
        fillObject(nested, child, childPath, op);
        nested.closeObject();
      } else if (value instanceof JsonArray array) {
        array(target, name, array, childPath + "[*]", op);
      } else {
        leaf(target, name, value, false, childPath, op);
      }
    }
  }

  private static void array(
      PactDslJsonBody target, String name, JsonArray array, String elementPath, Operation op) {
    if (array.isEmpty()) {
      target.array(name).closeArray();
      return;
    }
    if (!(array.getValue(0) instanceof JsonObject)) {
      throw new IllegalStateException(
          "golden master "
              + op.state()
              + "/"
              + op.operationId()
              + ": "
              + elementPath
              + " is not an object; consume the array's members instead");
    }
    // The merge: a field every element carries, its first non-null example, nullable where some
    // element held null. A field some element lacks is not bound.
    Map<String, Object> example = new LinkedHashMap<>();
    Set<String> nullable = new LinkedHashSet<>();
    Set<String> everywhere = new LinkedHashSet<>(array.getJsonObject(0).fieldNames());
    for (int i = 0; i < array.size(); i++) {
      JsonObject element = array.getJsonObject(i);
      everywhere.retainAll(element.fieldNames());
      for (String field : element.fieldNames()) {
        Object value = element.getValue(field);
        if (value == null) {
          nullable.add(field);
        } else {
          example.putIfAbsent(field, value);
        }
      }
    }
    PactDslJsonBody template = target.minArrayLike(name, 1, array.size());
    for (String field : everywhere) {
      Object value = example.get(field);
      String fieldPath = elementPath + "." + field;
      if (value == null) {
        template.nullValue(field);
      } else if (value instanceof JsonObject object) {
        if (nullable.contains(field)) {
          throw new IllegalStateException(
              "golden master "
                  + op.state()
                  + "/"
                  + op.operationId()
                  + ": "
                  + fieldPath
                  + " is an object in one element and null in another");
        }
        PactDslJsonBody nested = template.object(field);
        fillObject(nested, object, fieldPath, op);
        nested.closeObject();
      } else if (value instanceof JsonArray) {
        throw new IllegalStateException(
            "golden master "
                + op.state()
                + "/"
                + op.operationId()
                + ": "
                + fieldPath
                + " is a nested array, which this helper cannot express yet");
      } else {
        leaf(template, field, value, nullable.contains(field), fieldPath, op);
      }
    }
    DslPart closed = template.closeObject();
    ((PactDslJsonArray) closed).closeArray();
  }

  private static void leaf(
      PactDslJsonBody target,
      String name,
      Object value,
      boolean nullable,
      String path,
      Operation op) {
    if (op.ids().contains(path)) {
      if (nullable) {
        target.or(
            name, value, new RegexMatcher(UUID_REGEX, value.toString()), NullMatcher.INSTANCE);
      } else {
        target.uuid(name, value.toString());
      }
    } else if (op.instants().contains(path)) {
      if (nullable) {
        target.or(
            name, value, new RegexMatcher(ISO_INSTANT, value.toString()), NullMatcher.INSTANCE);
      } else {
        target.stringMatcher(name, ISO_INSTANT, value.toString());
      }
    } else if (nullable) {
      target.or(name, value, TypeMatcher.INSTANCE, NullMatcher.INSTANCE);
    } else if (value instanceof String s) {
      target.stringType(name, s);
    } else if (value instanceof Number n) {
      target.numberType(name, n);
    } else if (value instanceof Boolean b) {
      target.booleanType(name, b);
    } else {
      throw new IllegalStateException(
          "golden master "
              + op.state()
              + "/"
              + op.operationId()
              + ": "
              + path
              + " holds "
              + value.getClass().getSimpleName());
    }
  }

  // --- reading the jar --------------------------------------------------------------------------

  private static volatile JsonObject index;
  private static volatile boolean looked;

  /** The index, or null when no provider jar is on the classpath. */
  private static JsonObject index() {
    if (!looked) {
      String raw = optionalResource(ROOT + "index.json");
      JsonObject loaded = null;
      if (raw != null) {
        JsonObject candidate = new JsonObject(raw);
        // Another provider's golden masters on the same classpath are not this provider's.
        if (INDEX_PROVIDER.equals(candidate.getString("provider"))) {
          if (candidate.getInteger("formatVersion", 0) != 1) {
            throw new IllegalStateException(
                ROOT
                    + "index.json is formatVersion "
                    + candidate.getValue("formatVersion")
                    + "; GoldenMasters reads formatVersion 1");
          }
          loaded = candidate;
        }
      }
      index = loaded;
      looked = true;
    }
    return index;
  }

  private static JsonObject requireIndex() {
    JsonObject loaded = index();
    if (loaded == null) {
      throw new IllegalStateException(
          "eu.wohlben.qits:" + INDEX_PROVIDER + "-golden-masters is not on the test classpath");
    }
    return loaded;
  }

  private static JsonObject stateNode(JsonObject index, String state) {
    for (Object node : index.getJsonArray("states", new JsonArray())) {
      if (node instanceof JsonObject s && state.equals(s.getString("name"))) {
        return s;
      }
    }
    throw new IllegalArgumentException(
        INDEX_PROVIDER + "'s golden masters record no state '" + state + "'");
  }

  private static JsonObject find(JsonObject index, String state, String operationId) {
    for (Object node : index.getJsonArray("states", new JsonArray())) {
      if (node instanceof JsonObject s && state.equals(s.getString("name"))) {
        for (Object op : s.getJsonArray("operations", new JsonArray())) {
          if (op instanceof JsonObject o && operationId.equals(o.getString("operationId"))) {
            return o;
          }
        }
      }
    }
    return null;
  }

  private static Set<String> strings(JsonArray array) {
    Set<String> out = new LinkedHashSet<>();
    if (array != null) {
      array.forEach(e -> out.add(String.valueOf(e)));
    }
    return Set.copyOf(out);
  }

  private static String substitute(
      String template,
      Map<String, String> params,
      java.util.function.Function<String, String> value) {
    Matcher m = PARAM.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String name = m.group(1);
      if (!params.containsKey(name)) {
        throw new IllegalStateException(
            template + " names {" + name + "}, which the state's params do not hold");
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value.apply(name)));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String optionalResource(String name) {
    ClassLoader loader = GoldenMasters.class.getClassLoader();
    InputStream found = loader == null ? null : loader.getResourceAsStream(name);
    if (found == null) {
      return null;
    }
    try (InputStream in = found) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String resource(String name) {
    String found = optionalResource(name);
    if (found == null) {
      throw new IllegalStateException(name + " is not on the test classpath");
    }
    return found;
  }
}
