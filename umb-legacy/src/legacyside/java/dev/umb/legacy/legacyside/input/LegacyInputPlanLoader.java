package dev.umb.legacy.legacyside.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import net.minecraft.item.ItemStack;

import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Loads analyzer output; all mod identity remains data in the JSON, never runtime code. */
public final class LegacyInputPlanLoader {
    private static volatile boolean installed;
    private LegacyInputPlanLoader() {}

    public static synchronized int installDefaultPlans() {
        if (installed) return 0;
        Path dir = defaultPlansDir();
        int count = installPlans(dir);
        installed = true;
        LegacyInputDiag.log("input plans: " + count + " registered (dir " + dir.toAbsolutePath() + ")");
        return count;
    }

    /** Plan directory shared with {@link LegacyKeyBindingSynthesis}: system property wins. */
    static Path defaultPlansDir() {
        String configured = System.getProperty("umb.inputPlans");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured);
        }
        // The legacy side cannot see HostAgent.snapshotPath without a cross-loader
        // coupling the boundary contract forbids, so the repo anchor (an established
        // -Dumb.repo convention) is the fallback before the cwd-relative default.
        String repo = System.getProperty("umb.repo");
        if (repo != null && !repo.trim().isEmpty()) {
            Path p = Paths.get(repo, "research/out/legacy");
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return Paths.get("research/out/legacy");
    }

    public static int installPlans(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) return 0;
        int count = 0;
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) {
            for (Path p : (Iterable<Path>)paths::iterator) {
                if (!p.getFileName().toString().endsWith("-input-plans.json")) continue;
                count += installFile(p);
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot enumerate input plans in " + directory, e);
        }
        return count;
    }

    public static int installFile(Path file) {
        int count = 0;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // The legacy build intentionally uses the 1.7.10 Gson API, which predates
            // JsonParser.parseReader(Reader); retain the equivalent instance call for Java 8.
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            JsonArray plans = root.has("plans") ? root.getAsJsonArray("plans") : new JsonArray();
            for (JsonElement e : plans) {
                JsonObject p = e.getAsJsonObject();
                if (!p.has("resolved") || !p.get("resolved").getAsBoolean()) continue;
                String key = p.get("keybinding").getAsString();
                String messageClass = p.get("messageClass").getAsString();
                Class<?> message;
                try {
                    message = LegacyModClasses.forName(messageClass);
                } catch (ClassNotFoundException absentMod) {
                    // A plan file may be present for a mod not loaded in this universe.
                    // It is data-discovery output, not a boot failure.
                    if (LegacyInputDiag.oncePer("plan-noclass:" + messageClass, 0)) {
                        LegacyInputDiag.log("plan " + key + ": message class not visible ("
                                + messageClass + ")");
                    }
                    continue;
                }
                List<JsonObject> args = new ArrayList<JsonObject>();
                if (p.has("constructorArgs")) for (JsonElement a : p.getAsJsonArray("constructorArgs")) args.add(a.getAsJsonObject());
                LegacyInputDispatcher.registerPlan(key,
                        (input, pressed) -> instantiate(message, args, input, pressed));
                count++;
            }
        } catch (Exception e) {
            throw new IllegalStateException("cannot load input plan " + file, e);
        }
        return count;
    }

    private static IMessage instantiate(Class<?> message, List<JsonObject> specs,
            LegacyInputRecord input, boolean pressed) {
        try {
            Constructor<?> selected = null;
            for (Constructor<?> c : message.getDeclaredConstructors()) if (c.getParameterTypes().length == specs.size()) { selected = c; break; }
            if (selected == null) throw new NoSuchMethodException(message.getName() + " args=" + specs.size());
            selected.setAccessible(true);
            Class<?>[] types = selected.getParameterTypes();
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) values[i] = value(types[i], specs.get(i), input, pressed);
            return (IMessage)selected.newInstance(values);
        } catch (Exception e) {
            throw new IllegalStateException("cannot construct derived packet " + message.getName(), e);
        }
    }

    private static Object value(Class<?> type, JsonObject spec, LegacyInputRecord input,
            boolean pressed) throws Exception {
        String source = spec.has("source") ? spec.get("source").getAsString() : "unresolved";
        // The plan fires because THIS key changed state: its level is the pressed argument.
        // Anything else keeps the legacy use/attack fallback (never a guess about the key).
        if ((type == boolean.class || type == Boolean.class)
                && source.startsWith("input.pressedBoolean")) return pressed;
        if (type == boolean.class || type == Boolean.class) return input.useHeld() || input.attackHeld() || input.usePressed() || input.attackPressed();
        if (type == byte.class || type == Byte.class) return (byte)input.heldSlot();
        if (type == short.class || type == Short.class) return (short)input.heldSlot();
        if (type == int.class || type == Integer.class) return input.heldSlot();
        if (type == float.class || type == Float.class) return 0.0f;
        if (type == double.class || type == Double.class) return 0.0d;
        if (type == String.class && source.startsWith("constant:")) return source.substring("constant:".length());
        if (type.isEnum()) {
            String name = source.startsWith("enum:") ? source.substring(source.lastIndexOf('#') + 1) : null;
            Object[] constants = type.getEnumConstants();
            if (name != null) for (Object c : constants) if (((Enum<?>)c).name().equals(name)) return c;
            return constants.length == 0 ? null : constants[0];
        }
        if (ItemStack.class.isAssignableFrom(type)) return input.heldStack();
        if (input.player() != null && type.isAssignableFrom(input.player().getClass())) return input.player();
        throw new IllegalArgumentException("unsupported derived argument " + type.getName() + " from " + source);
    }
}
