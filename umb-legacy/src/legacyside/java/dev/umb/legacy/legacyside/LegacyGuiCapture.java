package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;

import dev.umb.bridge.api.GlEmulationSession;
import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import dev.umb.legacy.legacyside.input.LegacyLwjglState;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

/** Captures only the legacy GuiContainer panel layers; host-native slots remain outside the mesh. */
final class LegacyGuiCapture {
    private LegacyGuiCapture() {
    }

    static GlEmulationSession.Mesh render(LegacyGuiMouseDispatcher.ClientGuiSession session,
                                          final float partialTicks, final int width, final int height,
                                          final int mouseX, final int mouseY) throws Exception {
        if (session == null || !(session.gui instanceof GuiContainer)) return empty();
        final GuiContainer gui = session.gui;
        final LegacyClientFacade.Binding binding = LegacyClientFacade.install(
                session.context.player, session.context.world);
        LegacyClientFacade.EntityWorldScope worlds = null;
        Object oldMinecraft = null;
        try {
            try {
                Field minecraftField = Minecraft.class.getDeclaredField("field_71432_P");
                minecraftField.setAccessible(true);
                oldMinecraft = minecraftField.get(null);
                minecraftField.set(null, binding.minecraft);
            } catch (Throwable setFailed) {
                // A lost singleton write used to surface pages later as a vanilla
                // "Rendering item" crash; name it here while the cause is adjacent.
                if (LegacyInputDiag.oncePer("gui-singleton-set", 60_000_000_000L)) {
                    LegacyInputDiag.log("gui singleton set failed cause="
                            + setFailed.getClass().getName() + ":"
                            + String.valueOf(setFailed.getMessage()));
                }
            }
            // Bind the shared font/texture services onto THIS facade immediately, not only
            // inside prepareClientUniverse below: install() publishes the facade as the
            // static singleton first and binds later, so any concurrent legacy install on
            // another thread can otherwise observe (or leave behind) an unbound facade.
            // Vanilla GUI item rendering reads the singleton, not the GuiScreen instance.
            LegacyClientFacade.bindClientRenderServices(binding.minecraft, binding.gameSettings,
                    LegacyRenderCapture.currentResourceManager(binding.minecraft));
            LegacyRenderCapture.prepareClientUniverse(binding);
            // MCHeli's GuiContainer background asks W_ScaledResolution for the synthetic
            // Minecraft display dimensions. The HUD path initializes these before capture;
            // GUI capture must do the same or ScaledResolution divides by zero/uses 0x0.
            LegacyClientFacade.ensureDisplayDimensions(binding.minecraft);
            // The persistent screen never went through GuiScreen.setWorldAndResolution, which is
            // what copies mc.fontRenderer into fontRendererObj; drawString on it would NPE.
            field(GuiScreen.class, "field_146297_k").set(gui, binding.minecraft);
            field(GuiScreen.class, "field_146289_q").set(gui, binding.minecraft.field_71466_p);
            // Vanilla RenderItem.renderItemIntoGUI falls back to the STATIC singleton for the
// Legacy compatibility behavior.
            // at RenderItem.java:521), bypassing the GuiScreen.mc instance mod draw calls use.
            // Re-assert the singleton and its TextureManager now, and again between layers:
            // entity/tile captures on other threads install their own facades concurrently.
            healGuiSingleton(binding);
            // Atlas stitch budget lives on the facade now (shared by every capture path).
            LegacyClientFacade.seedMaxTextureSize();
            LegacyLwjglState.begin("gui:" + gui.getClass().getName());
            worlds = LegacyClientFacade.rebindEntityWorlds(binding.player, binding.world,
                    session.context.world, session.context.player, binding.player);
            final int oldWidth = getInt(gui, "field_146294_l", width);
            final int oldHeight = getInt(gui, "field_146295_m", height);
            final int oldLeft = getInt(gui, "field_147003_i", 0);
            final int oldTop = getInt(gui, "field_147009_r", 0);
            setInt(gui, "field_146294_l", width);
            setInt(gui, "field_146295_m", height);
            setInt(gui, "field_147003_i", 0);
            setInt(gui, "field_147009_r", 0);
            shiftButtons(gui, -oldLeft, -oldTop);
            try {
                // Vanilla GUI context enters container drawing with texturing (and usually
                // blending) enabled, and mod layers assume that ambient state.
                return LegacyRenderCapture.captureOverlay(binding, new Runnable() {
                    @Override public void run() {
                        try {
                            invoke(gui, "func_146976_a", Float.valueOf(partialTicks),
                                    Integer.valueOf(mouseX), Integer.valueOf(mouseY));
                            healGuiSingleton(binding);
                            drawButtons(gui, binding.minecraft, mouseX, mouseY);
                            healGuiSingleton(binding);
                            invoke(gui, "func_146979_b", Integer.valueOf(mouseX), Integer.valueOf(mouseY));
                        } catch (Throwable t) {
                            throw new LegacyGuiCaptureFailure(t);
                        }
                    }
                }, true);
            } finally {
                shiftButtons(gui, oldLeft, oldTop);
                setInt(gui, "field_146294_l", oldWidth);
                setInt(gui, "field_146295_m", oldHeight);
                setInt(gui, "field_147003_i", oldLeft);
                setInt(gui, "field_147009_r", oldTop);
            }
        } finally {
            try {
                if (worlds != null) worlds.restore();
            } finally {
                try { LegacyLwjglState.end(); }
                finally {
                    try {
                        Field minecraftField = Minecraft.class.getDeclaredField("field_71432_P");
                        minecraftField.setAccessible(true);
                        minecraftField.set(null, oldMinecraft);
                    } catch (Throwable restoreFailed) {
                        if (LegacyInputDiag.oncePer("gui-singleton-restore", 60_000_000_000L)) {
                            LegacyInputDiag.log("gui singleton restore failed cause="
                                    + restoreFailed.getClass().getName());
                        }
                    }
                    binding.restoreProxies();
                }
            }
        }
    }

    /**
     * Re-asserts the two vanilla seams GUI item rendering needs: the static singleton must be
     * this capture's facade, and its TextureManager must be bound. Healing (never just logging)
     * is what makes item draws survive concurrent installs from entity/tile captures on other
     * threads; every healed facade is service-bound, so this cannot poison their reads. Throws
     * descriptively when unhealable so the next live log settles the class-loader topology
     * instead of surfacing another anonymous "Rendering item" wrapper.
     */
    private static void healGuiSingleton(LegacyClientFacade.Binding binding) throws Exception {
        Minecraft minecraft = binding.minecraft;
        Minecraft singleton = Minecraft.func_71410_x();
        if (singleton != minecraft) {
            if (LegacyInputDiag.oncePer("gui-singleton-heal", 60_000_000_000L)) {
                LegacyInputDiag.log("gui singleton healed expected="
                        + System.identityHashCode(minecraft) + " observed="
                        + (singleton == null ? "null"
                                : String.valueOf(System.identityHashCode(singleton))));
            }
            Field singletonField = Minecraft.class.getDeclaredField("field_71432_P");
            singletonField.setAccessible(true);
            singletonField.set(null, minecraft);
            singleton = Minecraft.func_71410_x();
        }
        Object textures = singleton == null ? null : singleton.func_110434_K();
        if (textures == null) {
            LegacyClientFacade.bindClientRenderServices(minecraft, binding.gameSettings,
                    LegacyRenderCapture.currentResourceManager(minecraft));
            textures = minecraft.func_110434_K();
        }
        if (singleton != minecraft || textures == null) {
            throw new IllegalStateException("gui-singleton-unhealable singleton="
                    + describeMinecraft(singleton) + " binding=" + describeMinecraft(minecraft)
                    + " textureManager=" + (textures == null ? "null"
                            : textures.getClass().getName()));
        }
    }

    private static String describeMinecraft(Minecraft minecraft) {
        if (minecraft == null) return "null";
        ClassLoader loader = null;
        try { loader = minecraft.getClass().getClassLoader(); } catch (Throwable ignored) { }
        return System.identityHashCode(minecraft) + "@"
                + (loader == null ? "null-loader" : loader.getClass().getName());
    }

    private static void drawButtons(GuiContainer gui, Minecraft minecraft, int x, int y) throws Exception {
        Field field = field(GuiScreen.class, "field_146292_n");
        Object value = field.get(gui);
        if (!(value instanceof List<?>)) return;
        for (Object button : (List<?>) value) {
            if (!(button instanceof GuiButton)) continue;
            invoke(button, "func_146112_a", minecraft, Integer.valueOf(x), Integer.valueOf(y));
        }
    }

    private static void shiftButtons(GuiContainer gui, int dx, int dy) {
        try {
            Field field = field(GuiScreen.class, "field_146292_n");
            Object value = field.get(gui);
            if (!(value instanceof List<?>)) return;
            for (Object button : (List<?>) value) {
                if (!(button instanceof GuiButton)) continue;
                GuiButton b = (GuiButton) button;
                b.field_146128_h += dx;
                b.field_146129_i += dy;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void invoke(Object receiver, String name, Object... args) throws Exception {
        for (Class<?> type = receiver.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != args.length) continue;
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

    private static Field field(Class<?> owner, String name) throws Exception {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int getInt(Object receiver, String name, int fallback) {
        try { return field(receiver.getClass(), name).getInt(receiver); }
        catch (Throwable ignored) { return fallback; }
    }

    private static void setInt(Object receiver, String name, int value) throws Exception {
        field(receiver.getClass(), name).setInt(receiver, value);
    }

    private static GlEmulationSession.Mesh empty() {
        return new GlEmulationSession(false).seal();
    }

    private static final class LegacyGuiCaptureFailure extends RuntimeException {
        LegacyGuiCaptureFailure(Throwable cause) { super(cause); }
    }
}
