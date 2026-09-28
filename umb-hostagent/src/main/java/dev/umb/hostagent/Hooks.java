package dev.umb.hostagent;

import dev.umb.hostagent.content.Registrar;
import dev.umb.hostagent.content.UmbBridgeHost;
import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.InputData;
import dev.umb.hostagent.effects.LegacyEffectsPayload;
import dev.umb.hostagent.input.LegacyInputPayload;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The bytecode-visible seam. BuiltInRegistries.freeze() calls this as its first instruction,
 * i.e. after createContents() has populated every vanilla registry (creative tabs included)
 * and before anything is made read-only.
 *
 * Contract: idempotent, and it must never propagate a throwable into vanilla bootstrap.
 */
public final class Hooks {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private Hooks() {
    }

    public static void beforeFreeze() {
        if (!DONE.compareAndSet(false, true)) {
            AgentLog.line("beforeFreeze re-entered - ignored");
            return;
        }
        try {
            AgentLog.line("beforeFreeze: registries still writable, materialising legacy content");
            if (HostAgent.contentManifest() != null) Registrar.run(HostAgent.contentManifest());
            else Registrar.run(HostAgent.snapshotPath(), HostAgent.langPath(), HostAgent.blockShapesPath(),
                    HostAgent.guiProfilePath(), HostAgent.namespace());
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED in beforeFreeze: " + t);
            AgentLog.error("Hooks.beforeFreeze", t, 8);
        }
        try {
            // G2 step 2: BlockEntityType umb:legacy_tile + MenuType umb:legacy_menu, both PRE-FREEZE
            // (R5: singleplayer still serialises the open-screen packet by registry id). Separate
            // try/catch on purpose: a failure here must never roll back the block/item/tab
            // registration that already succeeded above.
            if (HostAgent.contentManifest() != null) {
                for (dev.umb.hostagent.content.ModContentRecord r : HostAgent.contentManifest().records())
                    Registrar.registerLegacyTileAndMenu(r.namespace());
            } else Registrar.registerLegacyTileAndMenu(HostAgent.namespace());
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED registering tile/menu: " + t);
            AgentLog.error("Hooks.beforeFreeze (tile/menu)", t, 8);
        }
        try {
            // ENTITY-BRIDGE: umb:legacy_entity, same pre-freeze window, own try/catch so a failure
            // here never rolls back the tile/menu registration above.
            if (HostAgent.contentManifest() != null) {
                for (dev.umb.hostagent.content.ModContentRecord r : HostAgent.contentManifest().records())
                    Registrar.registerLegacyEntityType(r.namespace());
            } else Registrar.registerLegacyEntityType(HostAgent.namespace());
        } catch (Throwable t) {
            AgentLog.loud("UMB-HOSTAGENT FAILED registering entity type: " + t);
            AgentLog.error("Hooks.beforeFreeze (entity)", t, 8);
        }
    }

    /** For the headless probe, which drives the hook directly rather than via bytecode. */
    public static boolean hasRun() {
        return DONE.get();
    }

    /** Serverbound custom-payload seam; listener/player types are kept Object in the patch ABI. */
    public static void serverCustomPayload(Object listener, Object packet) {
        try {
            if (!(packet instanceof ServerboundCustomPayloadPacket)) return;
            Object payload = ((ServerboundCustomPayloadPacket) packet).payload();
            if (!(payload instanceof LegacyInputPayload)) return;
            Object serverPlayer = listener.getClass().getMethod("getPlayer").invoke(listener);
            Class<?> hp = Class.forName("dev.umb.hostagent.content.HostPlayerImpl");
            var ctor = hp.getDeclaredConstructor(ServerPlayer.class); ctor.setAccessible(true);
            dev.umb.bridge.api.HostPlayer host = (dev.umb.bridge.api.HostPlayer) ctor.newInstance(serverPlayer);
            var f = ((LegacyInputPayload) payload).frame();
            dev.umb.hostagent.input.LegacyLatestInputs.put(f);
            if (dev.umb.hostagent.input.LegacyInputDiag.oncePer("payload-received",
                    30_000_000_000L)) {
                dev.umb.hostagent.input.LegacyInputDiag.line("payload received: player="
                        + host.getName() + " keys=" + f.legacyKeys().size());
            }
            UmbBridgeHost.get().acceptInput(host, new InputData(f.tick(), f.useDown(), f.attackDown(), f.sneakDown(),
                    f.usePressed(), f.attackPressed(), f.selectedSlot(), f.yaw(), f.pitch(), f.lookX(), f.lookY(), f.lookZ(),
                    f.heldItemId(), f.heldCount(), f.heldDamage(), f.heldNbt(), f.legacyKeys()));
        } catch (Throwable t) { AgentLog.error("Hooks.serverCustomPayload", t, 3); }
    }

    public static void clientCustomPayload(Object payload) {
        try { if (payload instanceof LegacyEffectsPayload) dev.umb.hostagent.effects.LegacyClientEffectsHook.accept(payload); }
        catch (Throwable t) { AgentLog.error("Hooks.clientCustomPayload", t, 2); }
    }

    /** Drains typed/unknown legacy effects once after the existing server tick END hook. */
    public static void serverEffectsEnd(MinecraftServer server) {
        try {
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null) return;
            java.util.List<EffectData> effects = bridge.drainClientEffects();
            if (effects.isEmpty()) return;
            for (ServerPlayer target : server.getPlayerList().getPlayers()) {
                java.util.ArrayList<EffectData> mine = new java.util.ArrayList<>();
                for (EffectData e : effects) if (e.playerId == null || e.playerId.isEmpty()
                        || e.playerId.equals(target.getScoreboardName())) mine.add(e);
                if (!mine.isEmpty()) target.connection.send(new ClientboundCustomPayloadPacket(new LegacyEffectsPayload(mine)));
            }
        } catch (Throwable t) { AgentLog.error("Hooks.serverEffectsEnd", t, 4); }
    }

    // ---------------------------------------------------------- creative paging
    //
    // Five seams spliced into CreativeModeInventoryScreen by CreativePagingPatcher. All of them
    // delegate to CreativePaging and none of them may throw into the render or input thread.
    // The screen-typed parameters are declared as Object on purpose: the injected call sites just
    // push `this`/`aload_1`, so nothing here links a client class and the headless probe (which
    // never loads a GUI class) keeps working.

    /** Replaces CreativeModeTabs.tabs() at the five call sites inside the creative screen. */
    public static java.util.List<net.minecraft.world.item.CreativeModeTab> visibleTabs() {
        try {
            return CreativePaging.visibleTabs();
        } catch (Throwable t) {
            AgentLog.error("Hooks.visibleTabs", t, 2);
            return net.minecraft.world.item.CreativeModeTabs.tabs();
        }
    }

    /** Replaces tab.column() in getTabX and extractTabButton. */
    public static int tabColumn(net.minecraft.world.item.CreativeModeTab tab) {
        try {
            return CreativePaging.tabColumn(tab);
        } catch (Throwable t) {
            AgentLog.error("Hooks.tabColumn", t, 2);
            return 0;
        }
    }

    /** True when the scroll was consumed as a page change. */
    public static boolean creativeScroll(Object screen, double mouseX, double mouseY,
                                         double scrollX, double scrollY) {
        try {
            return CreativePaging.scroll(screen, mouseX, mouseY, scrollX, scrollY);
        } catch (Throwable t) {
            AgentLog.error("Hooks.creativeScroll", t, 2);
            return false;
        }
    }

    /** True when the key was consumed as a page change. */
    public static boolean creativeKey(Object screen, Object keyEvent) {
        try {
            return CreativePaging.key(screen, keyEvent);
        } catch (Throwable t) {
            AgentLog.error("Hooks.creativeKey", t, 2);
            return false;
        }
    }

    /** Draws the "page N/M" hint at the end of the screen's render pass. */
    public static void renderPageHint(Object extractor, Object screen) {
        try {
            CreativePaging.renderHint(extractor, screen);
        } catch (Throwable t) {
            AgentLog.error("Hooks.renderPageHint", t, 2);
        }
    }

    public static void nextPage() {
        CreativePaging.nextPage(null);
    }

    /** Host HUD seam: dispatches legacy overlay events and submits their GL-EMU mesh to 26.2. */
    public static void renderHud(net.minecraft.client.gui.GuiGraphicsExtractor graphics,
                                 net.minecraft.client.DeltaTracker delta) {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (graphics == null || minecraft.player == null) return;
            // The legacy overlay paints while riding by default (the vehicle HUD case; live on
            // it on foot too, -Dumb.legacyHud=off disables it.
            String hudMode = System.getProperty("umb.legacyHud", "riding");
            if ("off".equals(hudMode)) return;
            if (!minecraft.player.isPassenger() && !"all".equals(hudMode)) return;
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null) return;
            String playerName = minecraft.player.getName().getString();
            float partial = delta == null ? 0.0F : delta.getGameTimeDeltaPartialTick(true);
            dev.umb.bridge.api.GlEmulationSession.Mesh mesh = bridge.renderHud(playerName, partial,
                    graphics.guiWidth(), graphics.guiHeight());
            LegacyHudPainter.paint(graphics, mesh);
        } catch (Throwable t) {
            AgentLog.error("Hooks.renderHud", t, 2);
        }
    }

    /** Host camera seam: mirrors a legacy renderViewEntity without naming a vehicle or mod. */
    public static void syncCamera(Object camera) {
        try {
            if (camera == null) return;
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.player == null) return;
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null) return;
            dev.umb.bridge.api.LegacyBridge.CameraState state = bridge.cameraState(
                    minecraft.player.getName().getString());
            if (state == null || !state.overridden) return;
            // its player, so "overridden" was true while walking and the whole world rendered
            // white. Only mirror a legacy view while actually riding, with a sane position.
            if (!minecraft.player.isPassenger()) return;
            if (!Double.isFinite(state.x) || !Double.isFinite(state.y) || !Double.isFinite(state.z)) return;
            double dx = state.x - minecraft.player.getX(), dy = state.y - minecraft.player.getY(),
                    dz = state.z - minecraft.player.getZ();
            if (dx * dx + dy * dy + dz * dz > 64.0 * 64.0) return;
            java.lang.reflect.Method setPosition = camera.getClass().getDeclaredMethod(
                    "setPosition", double.class, double.class, double.class);
            java.lang.reflect.Method setRotation = camera.getClass().getDeclaredMethod(
                    "setRotation", float.class, float.class);
            setPosition.setAccessible(true);
            setRotation.setAccessible(true);
            setPosition.invoke(camera, state.x, state.y, state.z);
            // Camera.setRotation is (pitch, yaw), matching the legacy Entity fields.
            setRotation.invoke(camera, state.pitch, state.yaw);
            java.lang.reflect.Field detached = camera.getClass().getDeclaredField("detached");
            detached.setAccessible(true);
            detached.setBoolean(camera, false);
        } catch (Throwable t) {
            AgentLog.error("Hooks.syncCamera", t, 2);
        }
    }

    public static void prevPage() {
        CreativePaging.prevPage(null);
    }

    /**
     * Generous-click seam for legacy vehicle twins, called from the patched
     * {@code LocalPlayer.pick} with its vanilla result. Vanilla aim already tests every
     * pickable twin by its own box; this only runs when vanilla MISSED outright (no block, no
     * entity), and re-tests the same ray against legacy twin boxes inflated by
     * {@link #LEGACY_PICK_MARGIN}. A hit returns a real {@code EntityHitResult} for the
     * nearest twin, so the normal interact packet + server validation path runs unchanged -
     * thin slabs (a 0.3-block wing box) and grazing angles that slip through the exact test
     * still board. Never reaches past vanilla's own reach, never steals a vanilla hit, never
     * throws.
     */
    public static net.minecraft.world.phys.HitResult pickLegacyPart(
            net.minecraft.world.entity.Entity shooter,
            double blockReach, double entityReach, float partialTick,
            net.minecraft.world.phys.HitResult vanilla) {
        try {
            if (vanilla == null
                    || vanilla.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                return vanilla;
            }
            if (shooter == null) {
                return vanilla;
            }
            net.minecraft.world.level.Level level = shooter.level();
            if (level == null || !level.isClientSide()) {
                return vanilla;
            }
            double range = Math.max(blockReach, entityReach);
            if (!(range > 0.0D) || !Double.isFinite(range)) {
                return vanilla;
            }
            net.minecraft.world.phys.Vec3 eye = shooter.getEyePosition(partialTick);
            net.minecraft.world.phys.Vec3 view = shooter.getViewVector(partialTick);
            net.minecraft.world.phys.Vec3 end = eye.add(view.scale(range));
            net.minecraft.world.phys.AABB search = shooter.getBoundingBox()
                    .expandTowards(view.scale(range)).inflate(1.0D);
            java.util.List<net.minecraft.world.entity.Entity> twins;
            try {
                twins = level.getEntities(shooter, search,
                        e -> e instanceof dev.umb.hostagent.content.UmbLegacyEntity
                                || e instanceof dev.umb.hostagent.content.UmbLegacyPartTwin);
            } catch (Throwable ignored) {
                return vanilla;
            }
            if (twins == null || twins.isEmpty()) {
                return vanilla;
            }
            java.util.List<net.minecraft.world.phys.AABB> boxes =
                    new java.util.ArrayList<>(twins.size());
            java.util.List<net.minecraft.world.entity.Entity> owners =
                    new java.util.ArrayList<>(twins.size());
            for (net.minecraft.world.entity.Entity twin : twins) {
                if (twin == null || twin == shooter || twin.isRemoved() || twin.isSpectator()) {
                    continue;
                }
                boxes.add(twin.getBoundingBox().inflate(LEGACY_PICK_MARGIN));
                owners.add(twin);
            }
            int hit = nearestHitIndex(eye, end, range * range, boxes);
            if (hit < 0) {
                return vanilla;
            }
            net.minecraft.world.entity.Entity target = owners.get(hit);
            java.util.Optional<net.minecraft.world.phys.Vec3> loc =
                    boxes.get(hit).clip(eye, end);
            return new net.minecraft.world.phys.EntityHitResult(target,
                    loc.orElse(end));
        } catch (Throwable t) {
            AgentLog.error("Hooks.pickLegacyPart", t, 2);
            return vanilla;
        }
    }

    /** Legacy click generosity (blocks) added around twin boxes in the fallback pick only. */
    static final double LEGACY_PICK_MARGIN = 0.3D;

    /**
     * Pure nearest-ray-hit over boxes; directly unit-testable. Returns the winning index or -1.
     * Mirrors vanilla's rule (nearest clip within range wins, no skipping).
     */
    static int nearestHitIndex(net.minecraft.world.phys.Vec3 eye,
            net.minecraft.world.phys.Vec3 end, double rangeSq,
            java.util.List<net.minecraft.world.phys.AABB> boxes) {
        if (eye == null || end == null || boxes == null || !(rangeSq > 0.0D)) {
            return -1;
        }
        int best = -1;
        double bestDist = rangeSq;
        for (int i = 0; i < boxes.size(); i++) {
            net.minecraft.world.phys.AABB box = boxes.get(i);
            if (box == null) {
                continue;
            }
            java.util.Optional<net.minecraft.world.phys.Vec3> hit;
            try {
                hit = box.clip(eye, end);
            } catch (Throwable ignored) {
                continue;
            }
            if (hit == null || hit.isEmpty()) {
                continue;
            }
            double dist = eye.distanceToSqr(hit.get());
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    /**
     * Host camera-distance seam, called from the patched {@code Camera.alignWithEntity} in place
     * of its hardcoded 4.0-block third-person distance. Returns the distance legacy client code wrote
     * into its own EntityRenderer (1.7.10 {@code field_78490_B}, captured per player after the
     * bounded legacy client tick) while that player rides a legacy twin, else the vanilla value
     * untouched. Vanilla block clipping ({@code getMaxZoom}) still applies downstream, so this
     * only changes the desired distance, never the collision-resolved one. Never throws.
     */
    public static float legacyThirdPersonDistance(float vanilla) {
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null) return vanilla;
            if (!minecraft.player.isPassenger()) return vanilla;
            if (!(minecraft.player.getVehicle()
                    instanceof dev.umb.hostagent.content.UmbLegacyEntity)) return vanilla;
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            if (bridge == null) return vanilla;
            float legacy = bridge.thirdPersonDistance(
                    minecraft.player.getName().getString());
            if (!Float.isFinite(legacy)) return vanilla;
            if (legacy < 0.5F || legacy > 64.0F) return vanilla;
            return legacy;
        } catch (Throwable t) {
            AgentLog.error("Hooks.legacyThirdPersonDistance", t, 2);
            return vanilla;
        }
    }

    /**
     * The visibility predicate, exposed for symmetry with CreativeModeTab.shouldDisplay().
     * Special (SEARCH/INVENTORY/HOTBAR) tabs are always visible.
     */
    public static boolean tabVisible(net.minecraft.world.item.CreativeModeTab tab) {
        try {
            if (tab.getType() != net.minecraft.world.item.CreativeModeTab.Type.CATEGORY) return true;
            if (!CreativePaging.isOurs(tab)) return CreativePaging.page() == 0;
            return CreativePaging.pageOfColumn(tab.column()) == CreativePaging.page();
        } catch (Throwable t) {
            return true;
        }
    }

    /** Button factory called from the PauseScreen and OptionsScreen init seams. */
    public static net.minecraft.client.gui.components.Button umbMenuButton(
            net.minecraft.client.gui.screens.Screen parent) {
        int width = Math.min(150, Math.max(80, parent.width - 8));
        int x = Math.max(4, (parent.width - width) / 2);
        // PauseScreen's button rows vary with the available height.  Reserve the bottom
        // slot instead of guessing a fixed row (the old height/2+108 placement clipped on
        // compact live windows).  The one-button margin keeps the full 20px button visible.
        int y = Math.max(8, parent.height - 28);
        return net.minecraft.client.gui.components.Button.builder(
                net.minecraft.network.chat.Component.literal("UMB Settings"),
                ignored -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(
                        new dev.umb.hostagent.content.UmbSettingsScreen(parent)))
                .bounds(x, y, width, 20).build();
    }
}
