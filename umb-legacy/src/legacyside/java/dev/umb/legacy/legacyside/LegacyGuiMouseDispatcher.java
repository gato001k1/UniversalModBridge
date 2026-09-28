package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import cpw.mods.fml.common.registry.GameData;

import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/** Invokes a real legacy GuiContainer input method on the bounded synthetic client facade. */
final class LegacyGuiMouseDispatcher {
    private static volatile ClientGuiSession activeSession;

    private LegacyGuiMouseDispatcher() {
    }

    /** Creates the one legacy client screen associated with a newly opened host menu. */
    static void open(UmbGui.GuiContext context) {
        if (context == null || context.handler == null || context.player == null) return;
        ClientGuiSession current = activeSession;
        if (current != null && current.matches(context)) return;
        LegacyClientFacade.Binding binding = null;
        try {
            binding = LegacyClientFacade.install(context.player, context.world);
            // Bind the font/texture services before construction + initGui: GuiTextFields and
            // similar widgets capture mc.fontRenderer at init time and never re-read it.
            dev.umb.legacy.legacyside.render.LegacyRenderCapture.prepareClientUniverse(binding);
            LegacyClientFacade.ensureDisplayDimensions(binding.minecraft);
            Object gui = context.handler.getClientGuiElement(context.id, binding.player, binding.world,
                    context.x, context.y, context.z);
            if (!(gui instanceof GuiContainer)) return;
            int xSize = getInt(GuiContainer.class, gui, "field_146999_f", 176);
            int ySize = getInt(GuiContainer.class, gui, "field_147000_g", 166);
            // Use the actual vanilla screen lifecycle.  Besides setting mc, dimensions, item
            // renderer, and fontRendererObj, setWorldAndResolution clears buttonList before
            // calling initGui.  Manually setting fields and invoking initGui leaves HBM's
            // inherited GuiInfoContainer state subtly different from a real client screen.
            invokeWithArgs(gui, "func_146280_a", binding.minecraft,
                    Integer.valueOf(854), Integer.valueOf(480));
            activeSession = new ClientGuiSession(context, (GuiContainer) gui, xSize, ySize);
            activeSession.initLeft = getInt(GuiContainer.class, gui, "field_147003_i", 0);
            activeSession.initTop = getInt(GuiContainer.class, gui, "field_147009_r", 0);
            if (LegacyInputDiag.oncePer("gui-screen-open:" + gui.getClass().getName(),
                    5_000_000_000L)) {
                LegacyInputDiag.log("gui legacy screen opened class=" + gui.getClass().getName()
                        + " size=" + xSize + "x" + ySize);
            }
        } catch (Throwable t) {
            System.err.println("[UMB-GUI] legacy screen open failed: " + t);
        } finally {
            if (binding != null) binding.restoreProxies();
        }
    }

    /** Vanilla's per-tick screen lifecycle, kept on the same instance as click dispatch. */
    static void tick(UmbPlayer player) {
        ClientGuiSession session = activeSession;
        if (session == null || player == null || !session.matchesPlayer(player)) return;
        LegacyClientFacade.Binding binding = null;
        try {
            binding = LegacyClientFacade.install(session.context.player, session.context.world);
            // updateScreen runs mod code against the static singleton; a facade that is
            // installed but never service-bound leaves every such read null (the same
            // family as the GUI capture's item-render crash). Direct bind: no resource
            // install needed here, just the shared font/texture services.
            LegacyClientFacade.bindClientRenderServices(binding.minecraft, binding.gameSettings,
                    dev.umb.legacy.legacyside.render.LegacyRenderCapture
                            .currentResourceManager(binding.minecraft));
            // This install is the last singleton writer most server ticks (no restore here),
            // so publish the installed resource manager too: a null manager on the leftover
            // is what trips the render thread's bind verification every frame.
            Object tickResources = dev.umb.legacy.legacyside.render.LegacyRenderCapture
                    .currentResourceManager(binding.minecraft);
            if (tickResources != null) {
                set(net.minecraft.client.Minecraft.class, binding.minecraft,
                        "field_110451_am", tickResources);
            }
            invokeNoArgs(session.gui, "func_73876_c");
            // Refresh the focus cache on the server thread while the GUI is live:
            // the client thread only ever reads the cached flag (see textFocused).
            session.textFocusedCache = computeTextFocused(session.gui);
        } catch (Throwable t) {
            if (LegacyInputDiag.oncePer("gui-screen-tick:" + session.gui.getClass().getName(),
                    60_000_000_000L)) {
                LegacyInputDiag.log("gui legacy screen tick failed class="
                        + session.gui.getClass().getName() + " error=" + t);
            }
        } finally {
            if (binding != null) binding.restoreProxies();
        }
    }

    static void close(UmbPlayer player) {
        close(player, null);
    }

    /**
     * Ends the client screen of {@code container}'s menu. A reopened GUI gets its new session
     * before the previous menu's close arrives, so a close only ends the session that belongs to
     * its own container (null = any, for callers without one).
     */
    static void close(UmbPlayer player, Object container) {
        ClientGuiSession session = activeSession;
        if (session != null && (player == null || session.matchesPlayer(player))
                && (container == null || session.container == null || session.container == container)) {
            activeSession = null;
            if (LegacyInputDiag.oncePer("gui-screen-close", 5_000_000_000L)) {
                LegacyInputDiag.log("gui legacy screen closed");
            }
        }
    }

    /** Captures the persistent GUI panel and keeps the sealed mesh on the same session used by clicks. */
    static dev.umb.bridge.api.GlEmulationSession.Mesh render(float partialTicks, int width, int height,
                                                              int mouseX, int mouseY) {
        ClientGuiSession session = activeSession;
        if (session == null || session.context == null || session.context.player == null) return null;
        try {
            dev.umb.bridge.api.GlEmulationSession.Mesh mesh = LegacyGuiCapture.render(
                    session, partialTicks, width, height, mouseX, mouseY);
            session.mesh = mesh;
            return mesh;
        } catch (Throwable t) {
            if (LegacyInputDiag.oncePer("gui-screen-render:" + session.gui.getClass().getName(),
                    60_000_000_000L)) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                LegacyInputDiag.log("gui legacy screen render failed class="
                        + session.gui.getClass().getName() + " error=" + cause);
                t.printStackTrace();
            }
            return null;
        }
    }

    static boolean dispatch(String guiClass, int x, int y, int z, int guiX, int guiY, int button,
                            int screenX, int screenY) {
        UmbGui.GuiContext context = UmbGui.lastContext();
        if (context == null || context.handler == null || context.player == null) return false;
        ClientGuiSession session = activeSession;
        if (session == null || !session.matches(context)) {
            open(context);
            session = activeSession;
        }
        boolean ownsClientPacketBinding = LegacyNetworkLoopback.bindClientPlayerIfAbsent(
                context.player instanceof net.minecraft.entity.player.EntityPlayerMP
                        ? (net.minecraft.entity.player.EntityPlayerMP) context.player : null);
        try {
            synchronized (LegacyClientTickDispatcher.class) {
            Object oldMinecraft = null;
            try {
                java.lang.reflect.Field f = net.minecraft.client.Minecraft.class.getDeclaredField("field_71432_P");
                f.setAccessible(true);
                oldMinecraft = f.get(null);
            } catch (Throwable ignored) {}
            LegacyClientFacade.Binding binding = LegacyClientFacade.install(context.player, context.world);
            // Click handlers run against the singleton too; keep this facade service-bound
            // for the same reason as tick() above.
            LegacyClientFacade.bindClientRenderServices(binding.minecraft, binding.gameSettings,
                    dev.umb.legacy.legacyside.render.LegacyRenderCapture
                            .currentResourceManager(binding.minecraft));
            boolean legacyClickInvoked = false;
            try {
                if (session == null) {
                    return false;
                }
                GuiContainer gui = session.gui;
                // Packet/key-opened screens are constructed outside Minecraft's normal screen
                // lifecycle.  Run the legacy init hook once so GuiButton-backed controls (and plain
                // GuiContainer button hit-testing) exist before the generic host gui_click arrives.
                // Seed the geometry BEFORE initGui: GuiContainer.initGui derives guiLeft/guiTop from
                // width/height and xSize/ySize, and mods place their GuiButtons relative to guiLeft /
                // guiTop at init time. Pick width/height so that derivation yields the host panel origin.
                int left = screenX - guiX;
                int top = screenY - guiY;
                set(GuiScreen.class, gui, "field_146297_k", binding.minecraft);
                if (left != session.initLeft || top != session.initTop) {
                    // Lay the screen out at the host panel origin the way a real client does on
                    // resize: setWorldAndResolution -> initGui with a width/height whose centring
                    // yields exactly this origin. Text fields keep their init-time (final)
                    // coordinates, so shifting GuiButtons alone left every legacy text field
                    // unclickable; a real re-layout places buttons and fields together.
                    invokeWithArgs(gui, "func_146280_a", binding.minecraft,
                            Integer.valueOf(2 * left + session.xSize),
                            Integer.valueOf(2 * top + session.ySize));
                    session.initLeft = left;
                    session.initTop = top;
                }
                int oldLeft = getInt(GuiContainer.class, gui, "field_147003_i", 0);
                int oldTop = getInt(GuiContainer.class, gui, "field_147009_r", 0);
                shiftButtons(gui, left - oldLeft, top - oldTop);
                set(GuiContainer.class, gui, "field_147003_i", Integer.valueOf(left));
                set(GuiContainer.class, gui, "field_147009_r", Integer.valueOf(top));
                logGateSnapshot(gui, binding.player, context.player.field_71070_bA);
                // A profile pairing is useful as a diagnostic, but raw GUI clicks must remain
                // universal: GUIMachineAssemblyMachine handles its selector in func_73864_a rather
                // than through a GuiButton, and a missing/stale profile must not suppress it. The
                // authoritative GUI instance is the one returned by this handler at this position.
                if (guiClass != null && !guiClass.equals(gui.getClass().getName())) {
                    System.err.println("[UMB-GUI] legacy mouse class mismatch requested=" + guiClass
                            + " actual=" + gui.getClass().getName() + "; dispatching actual GUI");
                }
                // GuiContainer's protected guiLeft/guiTop are computed from the legacy display.  The
                // host already supplied the scaled panel origin, so seed the same fields directly and
                // preserve the exact event coordinate rather than recomputing it in another scale.
                logButtons(gui, screenX, screenY);
                invoke(gui, "func_73864_a", screenX, screenY, button);
                legacyClickInvoked = true;
                try {
                    invoke(gui, "func_146286_b", screenX, screenY, button);
                } catch (Throwable releaseFailure) {
                    Throwable cause = releaseFailure;
                    if (cause instanceof java.lang.reflect.InvocationTargetException
                            && ((java.lang.reflect.InvocationTargetException) cause).getCause() != null) {
                        cause = ((java.lang.reflect.InvocationTargetException) cause).getCause();
                    }
                    // A legacy press may already have sent a c2s packet.  Do not turn a real
                    // click into legacyQueued=false merely because an optional release hook is
                    // not safe on this synthetic screen; raw release remains observable in the
                    // host automation result and this log preserves the cause.
                    System.err.println("[UMB-GUI] legacy mouse release failed for " + guiClass
                            + ": " + cause);
                }
                // A click can move focus between text fields; refresh the server-side
                // cache while the GUI is live (see textFocused).
                session.textFocusedCache = computeTextFocused(gui);
                return true;
            } catch (Throwable t) {
                Throwable cause = t;
                if (cause instanceof java.lang.reflect.InvocationTargetException
                        && ((java.lang.reflect.InvocationTargetException) cause).getCause() != null) {
                    cause = ((java.lang.reflect.InvocationTargetException) cause).getCause();
                }
                System.err.println("[UMB-GUI] legacy mouse dispatch failed for " + guiClass
                        + ": " + cause);
                return legacyClickInvoked;
            } finally {
                binding.restoreProxies();
                try {
                    java.lang.reflect.Field f = net.minecraft.client.Minecraft.class.getDeclaredField("field_71432_P");
                    f.setAccessible(true);
                    f.set(null, oldMinecraft);
                } catch (Throwable ignored) {}
            }
            }
        } finally {
            if (ownsClientPacketBinding) {
                // Deliver queued sendToServer packets only after the legacy client facade has
                // been restored, so server handlers observe the authoritative server world.
                LegacyNetworkLoopback.clearClientPlayer();
            }
        }
    }

    /**
     * Delivers one keyTyped(char, keyCode) to the live legacy screen - the same instance clicks
     * and captures use - under the same facade/loopback binding as a click, so a text field's
     * own packet (for example an "apply" that reads the typed value) reaches its server handler.
     */
    static boolean dispatchKey(String guiClass, char typedChar, int keyCode) {
        UmbGui.GuiContext context = UmbGui.lastContext();
        if (context == null || context.handler == null || context.player == null) return false;
        ClientGuiSession session = activeSession;
        if (session == null || !session.matches(context)) {
            open(context);
            session = activeSession;
        }
        if (session == null) return false;
        boolean ownsClientPacketBinding = LegacyNetworkLoopback.bindClientPlayerIfAbsent(
                context.player instanceof net.minecraft.entity.player.EntityPlayerMP
                        ? (net.minecraft.entity.player.EntityPlayerMP) context.player : null);
        try {
            synchronized (LegacyClientTickDispatcher.class) {
                Object oldMinecraft = null;
                Field singleton = null;
                try {
                    singleton = net.minecraft.client.Minecraft.class.getDeclaredField("field_71432_P");
                    singleton.setAccessible(true);
                    oldMinecraft = singleton.get(null);
                } catch (Throwable ignored) { }
                LegacyClientFacade.Binding binding =
                        LegacyClientFacade.install(context.player, context.world);
                try {
                    LegacyClientFacade.bindClientRenderServices(binding.minecraft, binding.gameSettings,
                            dev.umb.legacy.legacyside.render.LegacyRenderCapture
                                    .currentResourceManager(binding.minecraft));
                    set(GuiScreen.class, session.gui, "field_146297_k", binding.minecraft);
                    invokeKey(session.gui, typedChar, keyCode);
                    // A key can move focus too (tab/enter); refresh while live.
                    session.textFocusedCache = computeTextFocused(session.gui);
                    return true;
                } catch (Throwable t) {
                    Throwable cause = t;
                    if (cause instanceof java.lang.reflect.InvocationTargetException
                            && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (LegacyInputDiag.oncePer("gui-key:" + session.gui.getClass().getName(),
                            60_000_000_000L)) {
                        LegacyInputDiag.log("gui legacy key dispatch failed class="
                                + session.gui.getClass().getName() + " error=" + cause);
                    }
                    return false;
                } finally {
                    binding.restoreProxies();
                    if (singleton != null) {
                        try { singleton.set(null, oldMinecraft); } catch (Throwable ignored) { }
                    }
                }
            }
        } finally {
            if (ownsClientPacketBinding) LegacyNetworkLoopback.clearClientPlayer();
        }
    }

    private static final java.util.Map<Class<?>, Field[]> TEXT_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>();

    /**
     * Client-thread read of the focus state. The server thread recomputes the cached
     * flag after every tick, click and key on the live session; walking the legacy
     * GUI's GuiTextFields here would race the server thread mutating them, so this
     * only ever reads the session's volatile flag (false when there is no session).
     */
    static boolean textFocused() {
        ClientGuiSession session = activeSession;
        return session != null && session.textFocusedCache;
    }

    /**
     * Server-thread computation of the focus state: true while the given GUI holds a
     * focused GuiTextField (direct field or array). Runs only where the session GUI
     * is already live on this thread (tick/dispatch/dispatchKey), never on the
     * client thread. Package-visible for the unit test.
     */
    static boolean computeTextFocused(Object gui) {
        if (gui == null) return false;
        Field[] fields = TEXT_FIELDS.get(gui.getClass());
        if (fields == null) {
            java.util.List<Field> found = new java.util.ArrayList<Field>();
            for (Class<?> type = gui.getClass(); type != null && type != Object.class;
                    type = type.getSuperclass()) {
                for (Field f : type.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> t = f.getType();
                    if (net.minecraft.client.gui.GuiTextField.class.isAssignableFrom(t)
                            || (t.isArray() && net.minecraft.client.gui.GuiTextField.class
                                    .isAssignableFrom(t.getComponentType()))) {
                        f.setAccessible(true);
                        found.add(f);
                    }
                }
            }
            fields = found.toArray(new Field[0]);
            TEXT_FIELDS.put(gui.getClass(), fields);
        }
        for (Field f : fields) {
            try {
                Object value = f.get(gui);
                if (value instanceof net.minecraft.client.gui.GuiTextField) {
                    if (((net.minecraft.client.gui.GuiTextField) value).func_146206_l()) return true;
                } else if (value instanceof Object[]) {
                    for (Object o : (Object[]) value) {
                        if (o instanceof net.minecraft.client.gui.GuiTextField
                                && ((net.minecraft.client.gui.GuiTextField) o).func_146206_l()) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static void invokeKey(Object receiver, char typedChar, int keyCode) throws Exception {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod("func_73869_a", char.class, int.class);
                method.setAccessible(true);
                method.invoke(receiver, Character.valueOf(typedChar), Integer.valueOf(keyCode));
                return;
            } catch (NoSuchMethodException ignored) {
                // Search the SRG-named keyTyped up the GuiScreen hierarchy.
            }
        }
        throw new NoSuchMethodException("func_73869_a");
    }

    /** Bounded diagnostic: legacy GuiButton geometry/state vs the click (vanilla hit-test inputs). */
    private static void logButtons(Object gui, int screenX, int screenY) {
        try {
            java.lang.reflect.Field listField = GuiScreen.class.getDeclaredField("field_146292_n");
            listField.setAccessible(true);
            java.util.List<?> buttons = (java.util.List<?>) listField.get(gui);
            StringBuilder out = new StringBuilder("[UMB-GUI] legacy buttons click=" + screenX + "," + screenY);
            int shown = 0;
            for (Object o : buttons) {
                if (!(o instanceof net.minecraft.client.gui.GuiButton) || shown++ >= 12) continue;
                net.minecraft.client.gui.GuiButton bt = (net.minecraft.client.gui.GuiButton) o;
                out.append(" [id=").append(bt.field_146127_k).append(" ").append(bt.field_146128_h)
                        .append(",").append(bt.field_146129_i).append(" en=").append(bt.field_146124_l)
                        .append(" vis=").append(bt.field_146125_m).append(" '").append(bt.field_146126_j).append("']");
            }
            System.err.println(out);
        } catch (Throwable ignored) {
            // Diagnostic only.
        }
    }

    private static final Pattern GATE_FIELD_NAMES = Pattern.compile(
            "(?i).*(ammo|rest|allAmmo|onGround|water|supply|weapon|reload|block|health|aircraft|vehicle|entity).*");

    /**
     * Generic, bounded state probe for disabled legacy GUI actions.  It follows ordinary object
     * fields by type, not by mod/class name, so a different vehicle or machine GUI can expose the
     * same grounded/resource gate without adding another adapter.  This is diagnostic only and
     * never changes the callback or its return value.
     */
    private static void logGateSnapshot(Object gui, Entity player, Object container) {
        if (!LegacyInputDiag.oncePer("gui-gate-snapshot:" + gui.getClass().getName(),
                5_000_000_000L)) return;
        try {
            StringBuilder out = new StringBuilder("gui gate snapshot class=")
                    .append(gui.getClass().getName());
            Boolean callback = invokeBoolean(gui, "canReload", player);
            if (callback != null) out.append(" canReload=").append(callback);
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            appendState(container, 0, seen, out, player);
            appendState(gui, 0, seen, out, player);
            appendInventoryState(player, out);
            if (out.length() > 1800) out.setLength(1800);
            LegacyInputDiag.log(out.toString());
        } catch (Throwable ignored) {
            // A diagnostic must not make a GUI click fail.
        }
    }

    private static Boolean invokeBoolean(Object receiver, String name, Entity player) {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != 1
                        || method.getReturnType() != Boolean.TYPE
                        || !method.getParameterTypes()[0].isAssignableFrom(player.getClass())) continue;
                try {
                    method.setAccessible(true);
                    return Boolean.valueOf(((Boolean) method.invoke(receiver, player)).booleanValue());
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void appendState(Object value, int depth, Set<Object> seen, StringBuilder out,
                                   Entity player) {
        if (value == null || depth > 3 || seen.contains(value)) return;
        seen.add(value);
        if (value instanceof Entity) {
            Entity entity = (Entity) value;
            World world = entity.field_70170_p;
            out.append(" entity=").append(entity.getClass().getSimpleName())
                    .append(" pos=").append(entity.field_70165_t).append(',')
                    .append(entity.field_70163_u).append(',').append(entity.field_70161_v)
                    .append(" onGround=").append(entity.field_70122_E)
                    .append(" remote=").append(world != null && world.field_72995_K);
            if (world != null) {
                int bx = (int) (entity.field_70165_t + 0.5D);
                int by = (int) (entity.field_70163_u + 0.5D);
                int bz = (int) (entity.field_70161_v + 0.5D);
                for (int i = 0; i < 3; i++) {
                    int y = by - i;
                    net.minecraft.block.Block block = world.func_147439_a(bx, y, bz);
                    boolean opaque = block != null && block.func_149678_a(0, true);
                    String blockId = block == null ? "null" : GameData.getBlockRegistry().func_148750_c(block);
                    out.append(" block[").append(bx).append(',').append(y).append(',').append(bz)
                            .append("]=").append(blockId == null ? block.getClass().getSimpleName() : blockId)
                            .append(" opaque=").append(opaque);
                }
            }
            appendMethodState(entity, player, out);
        }
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !GATE_FIELD_NAMES.matcher(field.getName()).matches()) continue;
                try {
                    field.setAccessible(true);
                    Object item = field.get(value);
                    if (item == null || item instanceof Number || item instanceof Boolean
                            || item instanceof Character) {
                        out.append(" ").append(field.getName()).append('=').append(item);
                    } else if (item.getClass().isArray() && depth < 2) {
                        out.append(" ").append(field.getName()).append("=[");
                        int length = java.lang.reflect.Array.getLength(item);
                        for (int i = 0; i < length && i < 8; i++) {
                            if (i != 0) out.append(',');
                            Object element = java.lang.reflect.Array.get(item, i);
                            if (element == null || element instanceof Number
                                    || element instanceof Boolean || element instanceof Character) {
                                out.append(element);
                            } else {
                                appendState(element, depth + 1, seen, out, player);
                            }
                        }
                        out.append(']');
                    } else if (depth < 2 && !(item instanceof String) && !(item instanceof Class<?>)) {
                        appendState(item, depth + 1, seen, out, player);
                    }
                    if (out.length() > 1800) return;
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void shiftButtons(Object gui, int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        try {
            Field listField = GuiScreen.class.getDeclaredField("field_146292_n");
            listField.setAccessible(true);
            java.util.List<?> buttons = (java.util.List<?>) listField.get(gui);
            for (Object value : buttons) {
                if (!(value instanceof net.minecraft.client.gui.GuiButton)) continue;
                net.minecraft.client.gui.GuiButton button = (net.minecraft.client.gui.GuiButton) value;
                button.field_146128_h += dx;
                button.field_146129_i += dy;
            }
        } catch (Throwable ignored) {
            // A screen without standard buttons still receives its normal raw click callback.
        }
    }

    private static void appendMethodState(Object value, Entity player, StringBuilder out) {
        for (String name : new String[] {"canSupply", "getAmmoNum", "getRestAllAmmoNum", "getAllAmmoNum"}) {
            try {
                Object result = invokeValue(value, name);
                if (result instanceof Number || result instanceof Boolean) {
                    out.append(' ').append(name).append('=').append(result);
                }
            } catch (Throwable ignored) { }
        }
        if (player != null) {
            Boolean supply = invokeBoolean(value, "canPlayerSupplyAmmo", player, Integer.valueOf(0));
            if (supply != null) out.append(" canPlayerSupplyAmmo[0]=").append(supply);
        }
    }

    private static Object invokeValue(Object receiver, String name) throws Exception {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != 0) continue;
                method.setAccessible(true);
                return method.invoke(receiver);
            }
        }
        return null;
    }

    private static Boolean invokeBoolean(Object receiver, String name, Entity player, Integer weapon) {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!name.equals(method.getName()) || params.length != 2
                        || method.getReturnType() != Boolean.TYPE
                        || !params[0].isAssignableFrom(player.getClass())
                        || !(params[1] == Integer.TYPE || params[1] == Integer.class)) continue;
                try {
                    method.setAccessible(true);
                    return Boolean.valueOf(((Boolean) method.invoke(receiver, player, weapon)).booleanValue());
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Logs the actual legacy inventory the aircraft gate scans, without naming any mod. */
    private static void appendInventoryState(Entity player, StringBuilder out) {
        if (!(player instanceof EntityPlayer)) return;
        try {
            ItemStack[] stacks = ((EntityPlayer) player).field_71071_by.field_70462_a;
            out.append(" inventory=");
            int shown = 0;
            for (int i = 0; i < stacks.length && shown < 12; i++) {
                ItemStack stack = stacks[i];
                if (stack == null || stack.field_77994_a <= 0) continue;
                String id = GameData.getItemRegistry().func_148750_c(stack.func_77973_b());
                if (shown++ != 0) out.append(',');
                out.append(i).append(':').append(id).append('x').append(stack.field_77994_a)
                        .append('@').append(stack.func_77960_j());
            }
            if (shown == 0) out.append("empty");
        } catch (Throwable t) {
            out.append(" inventory=<error:").append(t.getClass().getSimpleName()).append('>');
        }
    }

    static final class ClientGuiSession {
        final UmbGui.GuiContext context;
        final GuiContainer gui;
        final int xSize;
        final int ySize;
        volatile dev.umb.bridge.api.GlEmulationSession.Mesh mesh;
        /**
         * Server-thread focus snapshot, recomputed after every tick/click/key on the
         * live GUI. The client thread (UmbLegacyScreen key handling via the bridge)
         * reads only this volatile flag and never walks the mod-owned text fields
         * the server thread mutates. False for a fresh session until its first tick.
         */
        volatile boolean textFocusedCache;
        /** Panel origin the screen was last laid out (initGui) at. */
        int initLeft, initTop;
        /** The legacy Container this screen was opened for (the player's open container then). */
        final Object container;
        ClientGuiSession(UmbGui.GuiContext context, GuiContainer gui, int xSize, int ySize) {
            this.context = context;
            this.container = context.player == null ? null : context.player.field_71070_bA;
            this.gui = gui;
            this.xSize = xSize;
            this.ySize = ySize;
        }

        boolean matches(UmbGui.GuiContext other) {
            return other != null && context.handler == other.handler && context.player == other.player
                    && context.world == other.world && context.id == other.id
                    && (other.player == null || container == other.player.field_71070_bA);
        }

        boolean matchesPlayer(UmbPlayer player) {
            return context.player == player;
        }
    }

    private static int getInt(Class<?> owner, Object receiver, String field, int fallback) {
        try {
            java.lang.reflect.Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(receiver);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void invoke(Object receiver, String name, int x, int y, int button)
            throws Exception {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(receiver, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(button));
                return;
            } catch (NoSuchMethodException ignored) {
                // Search the SRG-named method up the GuiContainer hierarchy.
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void invokeNoArgs(Object receiver, String name) throws Exception {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                method.invoke(receiver);
                return;
            } catch (NoSuchMethodException ignored) {
                // Search the SRG-named method up the GuiScreen hierarchy.
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void invokeWithArgs(Object receiver, String name, Object... args)
            throws Exception {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                method.setAccessible(true);
                try {
                    method.invoke(receiver, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw e;
                }
                return;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static void set(Class<?> owner, Object receiver, String name, Object value)
            throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(receiver, value);
    }
}
