package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, structural GUI-control extraction.  It records ordinary GuiButton constructor calls
 * and raw vanilla mouseClicked overrides; it does not execute handlers or infer a packet payload.
 * The latter is intentional: a value that cannot be proved from one method body is reported as
 * unresolved rather than converted into a guessed click.
 */
public final class ButtonScanner {
    private static final int MAX_HANDLER_METHODS = 64;

    private ButtonScanner() {}

    public static JsonArray scan(ClassNode cn) {
        JsonArray out = new JsonArray();
        HandlerKind handlerKind = classifyHandlers(cn);
        for (MethodNode mn : cn.methods) {
            if (!"func_73866_w_".equals(mn.name) || !"()V".equals(mn.desc)) continue;
            MethodSim.run(cn, mn, (insn, stack, locals) -> {
                if (!(insn instanceof MethodInsnNode call) || !"<init>".equals(call.name)) return;
                if (!isButtonType(call.owner)) return;
                int argc = Type.getArgumentTypes(call.desc).length;
                if (stack.size() < argc + 1) return;
                List<dev.umb.guimap.Val> args = new ArrayList<>();
                for (int i = stack.size() - argc; i < stack.size(); i++) args.add(stack.get(i));
                Integer id = number(args, 0), x = number(args, 1), y = number(args, 2);
                Integer w = argc >= 5 ? number(args, 3) : 200;
                Integer h = argc >= 5 ? number(args, 4) : 20;
                String label = string(args, args.size() - 1);
                if (id == null || x == null || y == null || w == null || h == null) return;
                JsonObject row = new JsonObject();
                row.addProperty("kind", "guiButton");
                row.addProperty("id", id);
                row.add("bounds", bounds(x, y, w, h));
                if (label != null) row.addProperty("label", label);
                else row.addProperty("label", (String) null);
            row.addProperty("handler", handlerKind.name().toLowerCase());
            row.addProperty("sourceMethod", mn.name + mn.desc);
            if (handlerKind == HandlerKind.PACKET) {
                row.add("packetRecipe", unresolvedRecipe("bounded constructor/field data is not proven"));
            }
            out.add(row);
            });
        }
        for (MethodNode mn : cn.methods) {
            if (!"func_73864_a".equals(mn.name) || !"(III)V".equals(mn.desc)) continue;
            JsonObject row = new JsonObject();
            row.addProperty("kind", "mouseRegion");
            row.addProperty("id", (Integer) null);
            row.add("bounds", new JsonObject());
            row.addProperty("label", (String) null);
            row.addProperty("handler", handlerKind.name().toLowerCase());
            row.addProperty("sourceMethod", mn.name + mn.desc);
            row.addProperty("reason", "raw mouseClicked hit-rectangle requires CFG/path extraction");
            if (handlerKind == HandlerKind.PACKET) {
                row.add("packetRecipe", unresolvedRecipe("raw mouseClicked CFG/constructor data is not proven"));
            }
            out.add(row);
        }
        return out;
    }

    private static JsonObject bounds(int x, int y, int w, int h) {
        JsonObject b = new JsonObject();
        b.addProperty("x", x); b.addProperty("y", y);
        b.addProperty("width", w); b.addProperty("height", h);
        b.addProperty("coordinateSpace", "panelRelative");
        return b;
    }

    private static boolean isButtonType(String owner) {
        return owner != null && (owner.equals("net/minecraft/client/gui/GuiButton")
                || owner.endsWith("/GuiButton") || owner.endsWith("/GuiSmallButton")
                || owner.endsWith("/GuiSlider") || owner.endsWith("/GuiCheckBox"));
    }

    private enum HandlerKind { CONTAINER, PACKET, UNRESOLVED }

    private static HandlerKind classifyHandlers(ClassNode cn) {
        int seen = 0;
        boolean container = false, packet = false, handler = false;
        for (MethodNode mn : cn.methods) {
            if (++seen > MAX_HANDLER_METHODS) break;
            if (!("func_146284_a".equals(mn.name) || "func_73864_a".equals(mn.name)
                    || "func_146192_a".equals(mn.name))) continue;
            handler = true;
            for (AbstractInsnNode insn : mn.instructions) {
                if (!(insn instanceof MethodInsnNode call)) continue;
                if ("func_75140_a".equals(call.name) || "enchantItem".equals(call.name)) container = true;
                if (call.owner != null && (call.owner.endsWith("Packet") || call.name.contains("sendTo"))
                        || "sendToServer".equals(call.name)) packet = true;
            }
        }
        if (container && !packet) return HandlerKind.CONTAINER;
        if (packet) return HandlerKind.PACKET;
        return handler ? HandlerKind.UNRESOLVED : HandlerKind.UNRESOLVED;
    }

    private static Integer number(List<Val> args, int i) {
        return i < args.size() && args.get(i).kind == Val.Kind.NUMBER
                ? args.get(i).numberValue.intValue() : null;
    }

    private static String string(List<Val> args, int i) {
        return i >= 0 && i < args.size() && args.get(i).kind == Val.Kind.STRING
                ? args.get(i).stringValue : null;
    }

    private static JsonObject unresolvedRecipe(String reason) {
        JsonObject recipe = new JsonObject();
        recipe.addProperty("resolved", false);
        recipe.addProperty("reason", reason);
        recipe.addProperty("messageClass", (String) null);
        recipe.add("constructorArgs", new JsonArray());
        return recipe;
    }
}
