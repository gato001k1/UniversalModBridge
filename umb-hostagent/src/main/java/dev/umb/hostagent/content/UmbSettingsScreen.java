package dev.umb.hostagent.content;

import dev.umb.hostagent.AgentLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The universal UMB pause/options screen. */
public final class UmbSettingsScreen extends Screen {
    private final Screen parent;

    public UmbSettingsScreen(Screen parent) {
        super(Component.literal("UMB Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int left = Math.max(8, width / 2 - 205);
        int right = left + 210;
        int top = 34;
        add(left, top, "Animation distance: " + UmbSettings.animationDistance(), b -> {
            int next = UmbSettings.animationDistance() >= 128 ? 8 : UmbSettings.animationDistance() * 2;
            UmbSettings.setAnimationDistance(next); rebuildWidgets();
        });
        add(left, top + 24, "Far-machine updates: " + onOff(UmbSettings.farMachineThrottle()), b -> {
            UmbSettings.setFarMachineThrottle(!UmbSettings.farMachineThrottle()); rebuildWidgets();
        });
        add(left, top + 48, "Render cache: " + onOff(UmbSettings.cacheEnabled()), b -> {
            UmbSettings.setCacheEnabled(!UmbSettings.cacheEnabled()); rebuildWidgets();
        });
        add(left, top + 72, "Capture memory: " + (UmbSettings.captureCacheBytes() / (1024 * 1024)) + " MB", b -> {
            long next = UmbSettings.captureCacheBytes() <= 128L * 1024L * 1024L
                    ? 256L * 1024L * 1024L : 128L * 1024L * 1024L;
            UmbSettings.setCaptureCacheBytes(next); rebuildWidgets();
        });
        add(left, top + 96, "Era 1.7.10: " + eraText("1.7.10"), b -> {
            UmbSettings.setEraEnabled("1.7.10", !UmbSettings.eraEnabled("1.7.10")); rebuildWidgets();
        });
        add(left, top + 120, "Era 1.12.2: " + eraText("1.12.2"), b -> {
            UmbSettings.setEraEnabled("1.12.2", !UmbSettings.eraEnabled("1.12.2")); rebuildWidgets();
        });
        add(left, top + 144, "Era 1.16.5: " + eraText("1.16.5"), b -> {
            UmbSettings.setEraEnabled("1.16.5", !UmbSettings.eraEnabled("1.16.5")); rebuildWidgets();
        });

        add(right, top, "Live hitboxes: " + onOff(UmbSettings.showHitboxes()), b -> {
            UmbSettings.setShowHitboxes(!UmbSettings.showHitboxes()); rebuildWidgets();
        });
        add(right, top + 24, "Crosshair inspector: " + onOff(UmbSettings.crosshairInspector()), b -> {
            UmbSettings.setCrosshairInspector(!UmbSettings.crosshairInspector()); rebuildWidgets();
        });
        add(right, top + 48, "Performance HUD: " + onOff(UmbSettings.perfHud()), b -> {
            UmbSettings.setPerfHud(!UmbSettings.perfHud()); rebuildWidgets();
        });
        add(right, top + 82, "Copy last error", b -> copyLastError());
        add(right, top + 130, "Done", b -> onClose());
    }

    private void add(int x, int y, String label, java.util.function.Consumer<Button> action) {
        addRenderableWidget(Button.builder(Component.literal(label), action::accept)
                .bounds(x, y, 190, 20).build());
    }

    private static String onOff(boolean value) { return value ? "ON" : "OFF"; }

    private static String eraText(String era) {
        return (UmbSettings.eraEnabled(era) ? "ON" : "OFF") + " / " + eraStatus(era);
    }

    private static String eraStatus(String era) {
        try {
            if (UmbBridgeHost.get() instanceof BridgeRouter router) return router.statusLine(era);
        } catch (Throwable t) { AgentLog.error("UmbSettingsScreen.eraStatus", t, 1); }
        return "not installed";
    }

    private void copyLastError() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.keyboardHandler != null) mc.keyboardHandler.setClipboard(UmbSettings.lastError());
        } catch (Throwable t) { AgentLog.error("UmbSettingsScreen.copyLastError", t, 1); }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.fill(width / 2 - 220, 20, width / 2 + 220, Math.min(height - 20, 215), 0xE0101010);
        g.centeredText(font, Component.literal("UMB Settings"), width / 2, 8, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
