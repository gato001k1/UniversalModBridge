package dev.umb.legacy.legacyside.network;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.umb.bridge.api.EffectData;

/**
 * Decodes only declarative presentation packet shapes.  This deliberately never tests a mod
 * class, namespace, channel, or packet name: a packet must carry the data needed to be useful.
 * The bounded field walk keeps arbitrary mod packets honest and prevents a whole-program scan.
 */
public final class LegacyEffectPacketDecoder {
    private static final int MAX_CLASS_DEPTH = 8;
    private static final int MAX_FIELDS_PER_CLASS = 64;
    private static final int MAX_EFFECTS = 4;

    private LegacyEffectPacketDecoder() {}

    public static List<EffectData> decode(Object payload, String playerId, long tick) {
        List<FieldValue> fields = fields(payload);
        List<EffectData> out = new ArrayList<EffectData>();
        if (fields.isEmpty()) return out;

        Number x = number(fields, "x", "posx", "centerx", "originx");
        Number y = number(fields, "y", "posy", "centery", "originy");
        Number z = number(fields, "z", "posz", "centerz", "originz");
        if (x == null || y == null || z == null) return out;

        String sound = soundName(fields);
        if (sound != null) {
            float volume = finiteFloat(number(fields, "volume", "vol"), 1.0F);
            float pitch = finiteFloat(number(fields, "pitch"), 1.0F);
            out.add(new EffectData("sound", playerId, sound, tick,
                    x.doubleValue(), y.doubleValue(), z.doubleValue(),
                    0, 0, 0, volume, pitch, 0, new byte[0]));
        }

        Number strength = number(fields, "strength", "size", "radius", "explosionpower");
        boolean affectedBlocks = hasNamed(fields, "affectedblocks", "blocks", "blockpositions");
        boolean explicitExplosionFlag = hasNamed(fields, "flaming", "breakblocks", "smoking", "explosion");
        if (strength != null && (affectedBlocks || explicitExplosionFlag)) {
            boolean breakBlocks = affectedBlocks || booleanValue(fields, "breakblocks", "smoking");
            boolean flaming = booleanValue(fields, "flaming");
            out.add(new EffectData("explosion", playerId, "legacy.packet.explosion", tick,
                    x.doubleValue(), y.doubleValue(), z.doubleValue(),
                    0, 0, 0, finiteFloat(strength, 0.0F),
                    breakBlocks ? 1.0F : 0.0F, flaming ? 1 : 0, new byte[0]));
        } else if (hasNamed(fields, "block", "blockid") && hasNamed(fields, "meta", "metadata")) {
            // Block-position particle bursts have no portable 1.7.10 particle identity.  Keep
            // them visible with the verified generic explosion-puff mapping.
            out.add(new EffectData("particle", playerId, "explode", tick,
                    x.doubleValue(), y.doubleValue(), z.doubleValue(),
                    0, 0, 0, 0, 0, 0, new byte[0]));
        }
        if (out.size() > MAX_EFFECTS) return new ArrayList<EffectData>(out.subList(0, MAX_EFFECTS));
        return out;
    }

    private static List<FieldValue> fields(Object payload) {
        List<FieldValue> out = new ArrayList<FieldValue>();
        if (payload == null) return out;
        Class<?> type = payload.getClass();
        for (int depth = 0; type != null && type != Object.class && depth < MAX_CLASS_DEPTH;
                depth++, type = type.getSuperclass()) {
            Field[] declared = type.getDeclaredFields();
            int limit = Math.min(declared.length, MAX_FIELDS_PER_CLASS);
            for (int i = 0; i < limit; i++) {
                Field field = declared[i];
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    out.add(new FieldValue(normalize(field.getName()), field.get(payload)));
                } catch (Throwable ignored) {
                    // An unrelated inaccessible packet field must not suppress other shapes.
                }
            }
        }
        return out;
    }

    private static String soundName(List<FieldValue> fields) {
        for (FieldValue field : fields) {
            if (!(field.value instanceof String)) continue;
            String name = (String) field.value;
            String key = field.name;
            if (!(key.contains("sound") || key.contains("event"))) continue;
            if (name.indexOf(':') >= 0 || name.indexOf('.') >= 0) return name;
        }
        return null;
    }

    private static Number number(List<FieldValue> fields, String... names) {
        for (String wanted : names) for (FieldValue field : fields)
            if (wanted.equals(field.name) && field.value instanceof Number)
                return (Number) field.value;
        return null;
    }

    private static boolean hasNamed(List<FieldValue> fields, String... names) {
        for (String wanted : names) for (FieldValue field : fields)
            if (wanted.equals(field.name)) return true;
        return false;
    }

    private static boolean booleanValue(List<FieldValue> fields, String... names) {
        for (String wanted : names) for (FieldValue field : fields)
            if (wanted.equals(field.name) && field.value instanceof Boolean)
                return ((Boolean) field.value).booleanValue();
        return false;
    }

    private static float finiteFloat(Number value, float fallback) {
        if (value == null) return fallback;
        float result = value.floatValue();
        return Float.isNaN(result) || Float.isInfinite(result) ? fallback : result;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace("_", "");
    }

    private static final class FieldValue {
        final String name;
        final Object value;
        FieldValue(String name, Object value) { this.name = name; this.value = value; }
    }
}
