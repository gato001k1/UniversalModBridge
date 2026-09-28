package dev.umb.legacy.boot;

import java.util.List;
import java.util.Map;

import dev.umb.legacy.api.BlockMetaShape;
import dev.umb.legacy.api.BlockShapeEntry;
import dev.umb.legacy.api.EntityEntry;
import dev.umb.legacy.api.FluidEntry;
import dev.umb.legacy.api.ModEntry;
import dev.umb.legacy.api.NamedEntry;
import dev.umb.legacy.api.RegistrySnapshot;
import dev.umb.legacy.api.StageResult;
import dev.umb.legacy.api.TabEntry;
import dev.umb.legacy.api.TileEntityEntry;

/**
 * A hand-rolled JSON writer.
 *
 * <p>No gson: the bootstrap side must stay dependency-free so it can be dropped into any host
 * process (including the 26.2 client, whose gson is a different version) without dragging a
 * library onto the host classpath.</p>
 */
public final class Json {

    private Json() {
    }

    public static String escape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }

    public static String snapshot(RegistrySnapshot s, List<StageResult> stages, Map<String, String> meta) {
        StringBuilder b = new StringBuilder(1 << 20);
        b.append("{\n");
        b.append("  \"schemaVersion\": 2,\n");
        b.append("  \"schema\": \"umb-legacy-snapshot\",\n");
        b.append("  \"source\": {\n");
        b.append("    \"producer\": \"umb-legacy G1 headless legacy universe\",\n");
        b.append("    \"mc\": ").append(escape(s.mcVersion())).append(",\n");
        b.append("    \"forge\": ").append(escape(s.forgeVersion())).append(",\n");
        b.append("    \"fml\": ").append(escape(s.fmlVersion())).append(",\n");
        b.append("    \"side\": ").append(escape(s.side()));
        for (Map.Entry<String, String> e : meta.entrySet()) {
            b.append(",\n    ").append(escape(e.getKey())).append(": ").append(escape(e.getValue()));
        }
        b.append("\n  },\n");

        b.append("  \"counts\": {");
        boolean first = true;
        for (Map.Entry<String, Integer> e : s.counts().entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("\n    ").append(escape(e.getKey())).append(": ").append(e.getValue());
        }
        b.append("\n  },\n");

        b.append("  \"stages\": [");
        for (int i = 0; i < stages.size(); i++) {
            StageResult r = stages.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"stage\": ").append(escape(r.stage()))
                    .append(", \"ok\": ").append(r.ok())
                    .append(", \"millis\": ").append(r.millis())
                    .append(", \"throwableClass\": ").append(escape(r.throwableClass()))
                    .append(", \"throwableMessage\": ").append(escape(r.throwableMessage()))
                    .append("}");
        }
        b.append("\n  ],\n");

        b.append("  \"mods\": [");
        for (int i = 0; i < s.mods().size(); i++) {
            ModEntry m = s.mods().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"modid\": ").append(escape(m.modid()))
                    .append(", \"name\": ").append(escape(m.name()))
                    .append(", \"version\": ").append(escape(m.version()))
                    .append(", \"sourceJarName\": ").append(escape(m.sourceJarName()))
                    .append(", \"state\": ").append(escape(m.state()))
                    .append("}");
        }
        b.append("\n  ],\n");

        named(b, "blocks", s.blocks());
        b.append(",\n");
        named(b, "items", s.items());
        b.append(",\n");

        b.append("  \"creativeTabs\": [");
        for (int i = 0; i < s.tabs().size(); i++) {
            TabEntry t = s.tabs().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"index\": ").append(t.index())
                    .append(", \"label\": ").append(escape(t.label()))
                    .append(", \"className\": ").append(escape(t.className()))
                    .append(", \"translatedLabel\": ").append(escape(t.translatedLabel()))
                    .append(", \"packagePrefix\": ").append(escape(t.packagePrefix()))
                    .append("}");
        }
        b.append("\n  ],\n");

        b.append("  \"tileEntities\": [");
        for (int i = 0; i < s.tileEntities().size(); i++) {
            TileEntityEntry t = s.tileEntities().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"name\": ").append(escape(t.name()))
                    .append(", \"className\": ").append(escape(t.className()))
                    .append(", \"packagePrefix\": ").append(escape(t.packagePrefix()))
                    .append("}");
        }
        b.append("\n  ],\n");

        b.append("  \"fluids\": [");
        for (int i = 0; i < s.fluids().size(); i++) {
            FluidEntry f = s.fluids().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"name\": ").append(escape(f.name()))
                    .append(", \"className\": ").append(escape(f.className()))
                    .append(", \"unlocalizedName\": ").append(escape(f.unlocalizedName()))
                    .append(", \"luminosity\": ").append(f.luminosity())
                    .append(", \"density\": ").append(f.density())
                    .append(", \"temperature\": ").append(f.temperature())
                    .append(", \"viscosity\": ").append(f.viscosity())
                    .append(", \"gaseous\": ").append(f.gaseous())
                    .append("}");
        }
        b.append("\n  ],\n");

        b.append("  \"entities\": [");
        for (int i = 0; i < s.entities().size(); i++) {
            EntityEntry e = s.entities().get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"modid\": ").append(escape(e.modid()))
                    .append(", \"name\": ").append(escape(e.name()))
                    .append(", \"className\": ").append(escape(e.className()))
                    .append(", \"modEntityId\": ").append(e.modEntityId())
                    .append(", \"trackingRange\": ").append(e.trackingRange())
                    .append(", \"updateFrequency\": ").append(e.updateFrequency())
                    .append(", \"sendsVelocityUpdates\": ").append(e.sendsVelocityUpdates())
                    .append("}");
        }
        b.append("\n  ],\n");

        b.append("  \"oreDictionary\": [");
        for (int i = 0; i < s.oreNames().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    ").append(escape(s.oreNames().get(i)));
        }
        b.append("\n  ]\n");

        b.append("}\n");
        return b.toString();
    }

/** Legacy compatibility behavior. */
    public static String blockShapes(List<BlockShapeEntry> entries, Map<String, Integer> counts) {
        StringBuilder b = new StringBuilder(1 << 20);
        b.append("{\n");
        b.append("  \"producer\": \"umb-legacy block-shape probe (GAP 1)\",\n");

        b.append("  \"summary\": {");
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append("\n    ").append(escape(e.getKey())).append(": ").append(e.getValue());
        }
        b.append("\n  },\n");

        b.append("  \"blocks\": [");
        for (int i = 0; i < entries.size(); i++) {
            BlockShapeEntry e = entries.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"id\": ").append(escape(e.id()))
                    .append(", \"registryId\": ").append(e.registryId())
                    .append(", \"className\": ").append(escape(e.className()))
                    .append(", \"error\": ").append(escape(e.error()))
                    .append(", \"metaGroups\": [");
            List<BlockMetaShape> groups = e.metaShapes();
            for (int g = 0; g < groups.size(); g++) {
                if (g > 0) {
                    b.append(',');
                }
                appendMetaShape(b, groups.get(g));
            }
            b.append("]}");
        }
        b.append("\n  ]\n");
        b.append("}\n");
        return b.toString();
    }

    private static void appendMetaShape(StringBuilder b, BlockMetaShape m) {
        b.append("\n      {\"metas\": ").append(intArray(m.metas()))
                .append(", \"rawBounds\": ").append(doubleArray(m.rawBounds()))
                .append(", \"collisionAabb\": ").append(doubleArray(m.collisionAabb()))
                .append(", \"selectionAabb\": ").append(doubleArray(m.selectionAabb()))
                .append(", \"collisionBoxes\": ").append(doubleArrayArray(m.collisionBoxes()))
                .append(", \"isOpaqueCube\": ").append(m.isOpaqueCube())
                .append(", \"renderAsNormalBlock\": ").append(m.renderAsNormalBlock())
                .append(", \"isFullCube\": ").append(m.isFullCube())
                .append(", \"error\": ").append(escape(m.error()))
                .append("}");
    }

    private static String intArray(int[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(a.length * 4 + 2);
        b.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(a[i]);
        }
        b.append(']');
        return b.toString();
    }

    private static String doubleArray(double[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(a.length * 10 + 2);
        b.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(a[i]);
        }
        b.append(']');
        return b.toString();
    }

    private static String doubleArrayArray(double[][] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder(a.length * 40 + 2);
        b.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(doubleArray(a[i]));
        }
        b.append(']');
        return b.toString();
    }

    private static void named(StringBuilder b, String key, List<NamedEntry> entries) {
        b.append("  ").append(escape(key)).append(": [");
        for (int i = 0; i < entries.size(); i++) {
            NamedEntry e = entries.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("\n    {\"id\": ").append(escape(e.name()))
                    .append(", \"numericId\": ").append(e.id())
                    .append(", \"className\": ").append(escape(e.className()))
                    .append(", \"unlocalizedName\": ").append(escape(e.unlocalizedName()))
                    .append(", \"creativeTab\": ").append(escape(e.creativeTab()))
                    .append(", \"hasTileEntity\": ").append(e.hasTileEntity())
                    .append(", \"tileEntityClass\": ").append(escape(e.tileEntityClass()))
                    .append("}");
        }
        b.append("\n  ]");
    }
}
