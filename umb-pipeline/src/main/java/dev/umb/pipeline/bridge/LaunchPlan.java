package dev.umb.pipeline.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A launch plan for the M7 interop CLI: an ORDERED list of entrypoint drives with optional
 * interop roles, parsed from a JSON array file. Shape is strict and honest — a malformed
 * plan is a {@code MALFORMED_PLAN} D4 refusal naming exactly what was wrong, never a
 * silent guess — but unknown extra keys are ignored, the same tolerance the
 * {@link EntrypointScanner} applies to fabric metadata.
 *
 * <p>Each entry names the entrypoint class and either role, both, or neither:
 * <ul>
 *   <li>{@code publish} — after a completed, no-args drive, if the lifecycle RETURNED an
 *       object recoverable by {@link Materializer#recover} (a materialized proxy or a raw
 *       host-universe instance), the HOST INSTANCE behind it is published under the
 *       identifier. A void or foreign return publishes nothing — no error, nothing
 *       declared.</li>
 *   <li>{@code consume} — the entry drives with arguments injected at the declared
 *       parameter indexes, each resolved by identifier from the {@link InteropRegistry}
 *       and VIEWED behind the lifecycle's OWN declared parameter interface (the class comes
 *       from the entrypoint's method signature, never guessed). The {@code param} indexes
 *       must cover exactly the lifecycle's arity; a missing resolve is per-entry
 *       {@code NOT_PUBLISHED} and the batch continues.</li>
 * </ul>
 * Entries with no role drive exactly as today (no args).
 *
 * @param entries the ordered plan entries, unchanged from the file's array order
 */
public record LaunchPlan(List<PlanEntry> entries) {

    /**
     * @param className the entrypoint class name to launch
     * @param publishId the identifier to publish the lifecycle's returned object under,
     *                  or null when this entry has no publish role
     * @param consume   the consume directives, param-ordered by the plan author (any
     *                  order; the driver sorts by {@code paramIndex} before driving)
     */
    public record PlanEntry(String className, String publishId, List<ConsumeDirective> consume) {

        public boolean hasPublish() {
            return publishId != null;
        }

        public boolean hasConsume() {
            return !consume.isEmpty();
        }
    }

    /**
     * @param paramIndex the lifecycle parameter index this directive injects at
     * @param identifier the identifier to resolve (and view) for that parameter
     */
    public record ConsumeDirective(int paramIndex, String identifier) {
    }

    /**
     * Parses a plan file with the scanner's tolerance discipline. The JSON must be an
     * ARRAY (order is the drive order); each element an object whose {@code class} names
     * an entrypoint; {@code publish} is an optional identifier string and {@code consume}
     * an optional array of {@code {"param":N,"id":"identifier"}} objects. Malformed JSON,
     * a non-array root, a missing/typed-wrong {@code class}, a negative or non-integer
     * {@code param}, or a missing {@code id} all refuse the launch with
     * {@code MALFORMED_PLAN} — never a guess. Unknown extra keys are ignored.
     *
     * @param path the plan file
     * @return the plan, entries in file array order
     * @throws IOException                 the file could not be read
     * @throws MaterializationException MALFORMED_PLAN on any structural violation
     */
    public static LaunchPlan parse(Path path) throws IOException {
        String text = Files.readString(path);
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (JsonParseException e) {
            throw malformed("not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isJsonArray()) {
            throw malformed("expected a JSON array of plan entries, got "
                    + describe(root));
        }
        JsonArray array = root.getAsJsonArray();
        List<PlanEntry> entries = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            entries.add(parseEntry(array.get(i), i));
        }
        return new LaunchPlan(List.copyOf(entries));
    }

    private static PlanEntry parseEntry(JsonElement el, int index) {
        if (el == null || !el.isJsonObject()) {
            throw malformed("plan entry " + index + " is not an object");
        }
        JsonObject o = el.getAsJsonObject();
        String cls = requiredString(o, "class", "plan entry " + index);
        String publish = optionalString(o, "publish", "plan entry " + index);
        List<ConsumeDirective> consume = parseConsume(o, index);
        return new PlanEntry(cls, publish, consume);
    }

    private static List<ConsumeDirective> parseConsume(JsonObject o, int entryIndex) {
        JsonElement c = o.get("consume");
        if (c == null || c.isJsonNull()) {
            return List.of();
        }
        if (!c.isJsonArray()) {
            throw malformed("plan entry " + entryIndex + ": \"consume\" must be an array");
        }
        JsonArray arr = c.getAsJsonArray();
        List<ConsumeDirective> list = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (el == null || !el.isJsonObject()) {
                throw malformed("plan entry " + entryIndex + " consume " + i + " is not an object");
            }
            JsonObject d = el.getAsJsonObject();
            int param = requiredInt(d, "param", "plan entry " + entryIndex + " consume " + i);
            if (param < 0) {
                throw malformed("plan entry " + entryIndex + " consume " + i
                        + ": negative parameter index " + param);
            }
            String id = requiredString(d, "id", "plan entry " + entryIndex + " consume " + i);
            list.add(new ConsumeDirective(param, id));
        }
        return List.copyOf(list);
    }

    private static String requiredString(JsonObject o, String key, String where) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            throw malformed(where + " has no \"" + key + "\" string");
        }
        return v.getAsString();
    }

    private static String optionalString(JsonObject o, String key, String where) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull()) {
            return null;
        }
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
            throw malformed(where + ": \"" + key + "\" must be a string");
        }
        return v.getAsString();
    }

    private static int requiredInt(JsonObject o, String key, String where) {
        JsonElement v = o.get(key);
        if (v == null || v.isJsonNull() || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
            throw malformed(where + " has no \"" + key + "\" integer");
        }
        JsonPrimitive p = v.getAsJsonPrimitive();
        try {
            double d = p.getAsDouble();
            int i = p.getAsInt();
            if (d != (double) i) {
                throw malformed(where + ": \"" + key + "\" is not an integer (" + p.getAsString() + ")");
            }
            return i;
        } catch (NumberFormatException e) {
            // a JSON number with no finite int form (e.g. "1e100") must not wrap silently
            throw malformed(where + ": \"" + key + "\" is not an integer (" + p.getAsString() + ")");
        }
    }

    private static String describe(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return "nothing";
        }
        if (el.isJsonObject()) {
            return "an object";
        }
        if (el.isJsonArray()) {
            return "an array";
        }
        if (el.isJsonPrimitive()) {
            return "a primitive";
        }
        return "something else";
    }

    private static MaterializationException malformed(String detail) {
        return new MaterializationException(MaterializationException.Kind.MALFORMED_PLAN,
                "malformed plan: " + detail);
    }
}