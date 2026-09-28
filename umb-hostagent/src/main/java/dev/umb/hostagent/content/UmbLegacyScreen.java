package dev.umb.hostagent.content;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.umb.hostagent.LegacyGuiPainter;
import dev.umb.hostagent.AgentLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * A generic panel for {@link UmbLegacyMenu}: the UNIVERSAL bottom section of a real vanilla
 * container-panel texture (the player-inventory + hotbar grid every 176x166 vanilla container
 * screen shares byte-for-byte, at the exact same pixel offsets the standard {@code (8,84)} slot
 * anchor already uses) for the part of the panel that is genuinely correct for every menu, plus a
 * plain neutral fill for the machine-slot area at the top (where HBM's actual, non-3x3 slot
 * layout lives) with an 18x18 outline per real machine slot from the menu's own layout. No 1.7.10
 * GL, ever -- 26.2 primitives only ({@link GuiGraphicsExtractor}).
 *
 * <p>M1-POST-FIX (cosmetic): the previous version blit the WHOLE {@code dispenser.png} texture,
 * including its own baked-in 3x3 slot grid -- painted art that has nothing to do with HBM's real
 * 4-slot layout and sits at different coordinates, so the screen showed a false 3x3 grid of empty
 * wells ALONGSIDE the real 18x18 outlines drawn below (visible in
 * research/out/legacy/win-m3/shots/12-crop.png). Fix: split the blit in two --
 * {@code assets/minecraft/textures/gui/container/dispenser.png} (176x256 texture, real/always
 * present, `unzip -l`-verified in research/jars/26.2/client.jar) is blit ONLY for source rows
 * {@code TOP_ART_HEIGHT..PANEL_HEIGHT} (the player-inventory/hotbar grid every vanilla container
 * texture draws identically at that same pixel offset -- this is exactly why {@code (8,84)}/
 * {@code (8,142)} already work for ANY borrowed container texture), landing at the same
 * {@code (8,84)} screen position the real slots occupy 1:1 since MC GUI textures are authored in
 * the same coordinate space they render in. The top {@code TOP_ART_HEIGHT}px (where the borrowed
 * texture's OWN, wrong-for-us slot art lives) is instead a flat neutral fill matching that
 * texture's own background tone, so nothing painted there can visually contradict the real
 * machine-slot wells drawn on top of it. The idiom (draw the dimmed/panorama screen background via
 * {@code super.extractBackground}, then blit with {@code RenderPipelines.GUI_TEXTURED}) and the
 * exact {@code blit} overload are unchanged from before and were originally copied from
 * {@code javap -c} on {@code DispenserScreen.extractBackground} -- see g2-integration-progress.md
 * for the full disassembly this was verified against.</p>
 *
 * <p>{@link #extractLabels} was, until the SCREEN-RENDER lane, deliberately NOT overridden:
 * {@code AbstractContainerScreen}'s own default implementation (javap-verified) already draws
 * {@code title} at {@code (titleLabelX, titleLabelY)} and {@code playerInventoryTitle} at
 * {@code (inventoryLabelX, inventoryLabelY)} -- the exact same two calls the previous version of
 * this class duplicated by hand -- and those four fields already default to sane positions for a
 * 176x166 panel ({@code AbstractContainerScreen}'s 5-arg constructor sets
 * {@code titleLabelX=8, titleLabelY=6, inventoryLabelX=8, inventoryLabelY=imageHeight-94=72}, the
 * same defaults {@code DispenserScreen}/{@code HopperScreen} rely on). It is now overridden to ALSO
 * draw the mod's own foreground labels ({@link UmbLegacyMenu#labels}) on top of that unchanged
 * default -- see {@link #extractLabels}'s own javadoc. Every draw call that IS overridden here is
 * still wrapped defensively: a rendering mistake must never crash the client, matching the same
 * defensive style already used by {@code CreativePaging.renderHint}.</p>
 *
 * <p>An earlier version also drew one horizontal bar per {@code ContainerData} index
 * (0..{@code dataCount()-1}) unconditionally. {@code HandleContainerData.getCount()} mirrors
 * {@code ContainerHandle.syncData().length}, which is a FIXED 32-slot register bank (see
 * {@code UmbPlayer.syncData = new int[32]} in umb-legacy) -- not "32 meaningful progress values"
 * -- so that loop painted 32 stacked bars top-to-bottom, i.e. exactly the horizontal-stripe defect
 * from research/out/legacy/win-m2/shots/09-GUI.png. Removed rather than bounded: M1's Definition
 * of Done does not require progress bars (see the class javadoc above), and a correct version
 * needs the real per-index "is this one meaningful" descriptor the placeholder API does not carry.</p>
 */
public final class UmbLegacyScreen extends AbstractContainerScreen<UmbLegacyMenu> {

    private static final int TEXTURE_SIZE = 256;
    private static final int SLOT_COLOR = 0xFF8B8B8B;
    /** Vanilla's own flat panel-background tone (matches dispenser.png's own border/background
     *  pixels, javap/asset-verified by eye against every vanilla 176x166 container texture), used
     *  to fill the machine-slot area so it does not clash with the blit below it. */
    private static final int PANEL_BG_COLOR = 0xFFC6C6C6;
    /** Everything at or below this source/dest row is the UNIVERSAL player-inventory + hotbar
     *  grid every vanilla 176x166 container texture draws identically; everything above it is
     *  the container-specific art we must not borrow (HBM's own 4-slot layout does not match
     *  dispenser.png's baked 3x3 grid). One pixel above the (8,84) slot anchor so its top border
     *  is included. */
    private static final int TOP_ART_HEIGHT = 83;

    /** A real, always-present vanilla panel texture; only its universal bottom section is used (see class javadoc). */
    private static final Identifier CONTAINER_LOCATION = Identifier.withDefaultNamespace("textures/gui/container/dispenser.png");

    public UmbLegacyScreen(UmbLegacyMenu menu, Inventory playerInventory, Component title) {
        // Task B (laneConsume-progress.md): the real per-GUI panel size, not a hardcoded 176x166 -
        // menu.xSize/ySize already default to 176x166 themselves when no gui-profile.json pairing
        // was found, so this is a strict superset of the old behaviour.
        super(menu, playerInventory, title, menu.xSize, menu.ySize);
    }

    @Override
    protected void init() {
        super.init();
        // GuiButton records are already panel-relative.  Only records with a proven rectangle
        // and a proven route become native widgets; unresolved raw mouse handlers remain counted
        // in the profile but are never made clickable by guessing.
        for (GuiProfile.Button b : getMenu().buttons) {
            if (!b.hasBounds || b.id < 0 || "unresolved".equals(b.handler)) continue;
            Component label = b.label != null ? Component.literal(b.label) : Component.literal("");
            addRenderableWidget(Button.builder(label, ignored -> sendLegacyButton(b.id))
                    .bounds(leftPos + b.x, topPos + b.y, b.width, b.height).build());
        }
    }

    private void sendLegacyButton(int id) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameMode != null) {
                // javap-verified 26.2 route: MultiPlayerGameMode.handleInventoryButtonClick(int,int)
                // creates ServerboundContainerButtonClickPacket(containerId, buttonId).
                mc.gameMode.handleInventoryButtonClick(getMenu().containerId, id);
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyScreen.sendLegacyButton", t, 2);
        }
    }

    private dev.umb.bridge.api.GlEmulationSession.Mesh legacyGuiMesh() {
        return UmbLegacyMenu.currentGuiMesh(getMenu().containerId);
    }

    /**
     * Queues a raw legacy GuiContainer click after native 26.2 children have declined it.  The
     * caller supplies GUI-relative coordinates; leftPos/topPos are the same scaled panel origin
     * that AbstractContainerScreen uses for its slots, so the legacy side receives the exact
     * absolute screen coordinate expected by GuiContainer.func_73864_a.
     */
    public boolean queueLegacyMouseClick(double guiX, double guiY, int button) {
        int gx = (int) Math.floor(guiX);
        int gy = (int) Math.floor(guiY);
        int sx = (int) Math.floor(leftPos + guiX);
        int sy = (int) Math.floor(topPos + guiY);
        int request = LegacyGuiClickChannel.put(gx, gy, button, sx, sy);
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameMode == null) return false;
            mc.gameMode.handleInventoryButtonClick(getMenu().containerId, request);
            return true;
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyScreen.queueLegacyMouseClick", t, 2);
            LegacyGuiClickChannel.take(request);
            return false;
        }
    }

    /** True when the panel point belongs to a native slot or a statically proven native widget. */
    public boolean isNativeRegion(double guiX, double guiY) {
        int x = (int) Math.floor(guiX);
        int y = (int) Math.floor(guiY);
        for (Slot slot : getMenu().slots) {
            if (x >= slot.x && x < slot.x + 16 && y >= slot.y && y < slot.y + 16) return true;
        }
        for (GuiProfile.Button b : getMenu().buttons) {
            if (legacyGuiMesh() != null) continue;
            if (!b.hasBounds || b.id < 0 || "unresolved".equals(b.handler)) continue;
            if (x >= b.x && x < b.x + b.width && y >= b.y && y < b.y + b.height) return true;
        }
        return false;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        try {
            // Screen's own dimmed/panorama world backdrop -- every vanilla container screen
            // (DispenserScreen included) calls this first, before its own panel texture.
            super.extractBackground(g, mouseX, mouseY, partialTick);
            RenderPipeline pipeline = RenderPipelines.GUI_TEXTURED;
            UmbLegacyMenu menu = getMenu();
            dev.umb.bridge.api.GlEmulationSession.Mesh legacyMesh = legacyGuiMesh();
            if (legacyMesh != null) {
                int painted = LegacyGuiPainter.paint(g, legacyMesh, leftPos, topPos,
                        imageWidth, imageHeight);
                if (painted > 0) {
                    AgentLog.line("[UMB-GUI] legacy mesh draws=" + painted
                            + " vertices=" + legacyMesh.vertexCount());
                }
                return;
            }
            if (menu.textureLocation != null) {
                // Task B: the mod's OWN background art (copied into the generated pack by
                // PackGen.copyGuiTextures) -- it already contains the correct slot wells for THIS
                // GUI's real machine-slot layout at THIS GUI's real panel size, so no neutral fill
                // or slot-highlight overlay is needed here (unlike the borrowed-dispenser
                // fallback below, this texture IS the real thing).
                g.blit(pipeline, menu.textureLocation, leftPos, topPos, 0.0F, 0.0F,
                        imageWidth, imageHeight, menu.sheetWidth, menu.sheetHeight);
                // SCREEN-RENDER lane (GENERALIZATION-PLAN.md GAP 2): everything ELSE the mod's own
                // drawGuiContainerBackgroundLayer draws -- progress-bar frames, gauge borders,
                // tank/heat indicator icons -- that GuiProfile could statically prove is safe to
                // blit (see GuiProfile.buildRects for the exact skip policy and
                // research/out/legacy/guimap-notes/SCREEN-RENDER.md for the numbers). The rect
                // list never includes the full-panel background blit just drawn above (skipped by
                // GuiProfile at parse time, matched by shape not list position), so this can never
                // double-draw it.
                drawExtraRects(g, pipeline, menu, mouseX, mouseY);
            } else {
                // FALLBACK: no resolvable background texture for this GUI (~14/181 real HBM GUIs -
                // see GuiProfile) -- keep the pre-Task-B borrowed-dispenser-bottom + neutral-fill +
                // highlight idiom so the panel stays legible instead of a pure vanilla
                // missing-texture checkerboard.
                g.fill(leftPos, topPos, leftPos + imageWidth, topPos + TOP_ART_HEIGHT, PANEL_BG_COLOR);
                // The universal player-inventory/hotbar grid (bottom) -- identical pixel offsets to
                // where the real slots already render, borrowed from the same real vanilla texture.
                int bottomHeight = Math.max(0, imageHeight - TOP_ART_HEIGHT);
                g.blit(pipeline, CONTAINER_LOCATION, leftPos, topPos + TOP_ART_HEIGHT, 0.0F, TOP_ART_HEIGHT,
                        imageWidth, bottomHeight, TEXTURE_SIZE, TEXTURE_SIZE);
                for (Slot slot : menu.slots) {
                    if (slot.index >= menu.machineSlotCount) continue;
                    int x = leftPos + slot.x - 1;
                    int y = topPos + slot.y - 1;
                    g.fill(x, y, x + 18, y + 18, SLOT_COLOR);
                }
            }
        } catch (Throwable t) {
            AgentLog.error("UmbLegacyScreen.extractBackground", t, 2);
        }
    }

    /**
     * Draws every {@link UmbLegacyMenu#rects} entry GuiProfile already proved is statically
     * drawable: destination is {@code leftPos+dx, topPos+dy} (both were guiLeft/guiTop-relative in
     * the original 1.7.10 draw call), source is the rect's own {@code u,v,w,h} against whichever
     * texture {@link GuiProfile.Rect#textureIndex} names (a GUI can bind more than one texture in
     * one draw method -- see {@code GuiProfile.buildRects}). Logs at most ONCE per distinct texture
     * identity (a reasonable proxy for "per GUI class" -- this menu carries no class name) rather
     * than per frame, so a rect that throws does not flood the log or tank the framerate.
     *
     * <p><b>TILE-FIELD-SNAPSHOT lane:</b> {@code menu.currentTileFieldLookup()} is called ONCE per
     * frame here (never per rect) — a single {@code TileSnapshotChannel} map read whose underlying
     * value is only ever refreshed at server-TICK rate by {@code UmbLegacyMenu#broadcastChanges} —
     * and reused for every rect this frame needs it, satisfying the brief's "snapshot on the tick,
     * cache per frame" requirement literally. This method itself never reads legacy state directly:
     * it only ever touches the cached {@link GuiProfile.TileExpr}/{@link GuiProfile.TileGuard}
     * evaluators and the plain per-frame lookup closure — no cross-loader call happens on this
     * (render) thread.</p>
     */
    private void drawExtraRects(GuiGraphicsExtractor g, RenderPipeline pipeline, UmbLegacyMenu menu,
                                 int mouseX, int mouseY) {
        if (menu.rects.isEmpty()) return;
        java.util.function.Function<String, Double> tileFields = menu.currentTileFieldLookup();
        try {
            for (GuiProfile.Rect r : menu.rects) {
                if (r.textureIndex < 0 || r.textureIndex >= menu.rectTextures.length) continue;
                Identifier tex = menu.rectTextures[r.textureIndex];
                if (tex == null) continue;
                // SYNC-BINDING lane: a rect guarded by ONE bound-field condition (GuiProfile.SyncGuard)
                // draws only when the ORIGINAL bytecode's own skip test is false this frame - a live
                // per-frame check against the current register value, never a one-time decision.
                if (r.guard != null && r.guard.shouldSkip(menu::getData)) continue;
                // TILE-FIELD-SNAPSHOT lane: same idea, but the skip test is evaluated against the
                // live tile-field snapshot / mouse position / panel origin instead of a sync
                // register - see GuiProfile.TileGuard#shouldSkip for why an UNDECIDABLE guard
                // (an absent field this frame) counts as "skip", never as "safe to draw".
                if (r.tileGuard != null && r.tileGuard.shouldSkip(tileFields, mouseX, mouseY, leftPos, topPos)) {
                    continue;
                }
                // Up to four of u/v/w/h can instead be a GuiProfile.SyncExpr (bound to a live
                // ContainerHandle.syncData() register) or, new in this lane, a GuiProfile.TileExpr
                // (bound to a live per-tile-entity snapshot field) - evaluated fresh every frame,
                // the SAME formula umb-guimap extracted, never a frozen/guessed value. A null from
                // a TileExpr means the field is absent THIS frame (tile removed, snapshot not yet
                // arrived) - the WHOLE rect is skipped rather than drawn with a substituted value.
                Integer u = evalArg(r.u, r.uExpr, r.uTileExpr, menu, tileFields);
                Integer v = evalArg(r.v, r.vExpr, r.vTileExpr, menu, tileFields);
                Integer w = evalArg(r.w, r.wExpr, r.wTileExpr, menu, tileFields);
                Integer h = evalArg(r.h, r.hExpr, r.hTileExpr, menu, tileFields);
                if (u == null || v == null || w == null || h == null) continue;
                if (w <= 0 || h <= 0) continue; // a live value can legitimately go to/below zero
                // Mandatory clamp: a live width/height can legitimately overshoot its own texture
                // region (e.g. before a divisor field initialises, or between two ticks) - clamp to
                // the panel's own bounds rather than draw off-panel garbage.
                Integer dynamicX = r.xTileExpr != null
                        ? r.xTileExpr.evaluate(tileFields, leftPos, topPos) : null;
                Integer dynamicY = r.yTileExpr != null
                        ? r.yTileExpr.evaluate(tileFields, leftPos, topPos) : null;
                if (r.xTileExpr != null && dynamicX == null) continue;
                if (r.yTileExpr != null && dynamicY == null) continue;
                int x = dynamicX != null ? dynamicX : leftPos + r.dx;
                int y = dynamicY != null ? dynamicY : topPos + r.dy;
                int panelX = x - leftPos;
                int panelY = y - topPos;
                int clampedW = GuiProfile.clampToPanel(panelX, w, menu.xSize);
                int clampedH = GuiProfile.clampToPanel(panelY, h, menu.ySize);
                if (clampedW <= 0 || clampedH <= 0) continue;
                int sheetW = menu.rectTextureSheetWidths[r.textureIndex];
                int sheetH = menu.rectTextureSheetHeights[r.textureIndex];
                g.blit(pipeline, tex, x, y, (float) u, (float) v, clampedW, clampedH, sheetW, sheetH);
            }
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyScreen.drawExtraRects:"
                    + (menu.textureLocation != null ? menu.textureLocation : "fallback"), t, 2);
        }
    }

    /** One arg slot's live value, trying the sync-register binding first (pre-existing), then the
     *  per-tile-snapshot binding (this lane), falling back to the static value - never more than
     *  one of {@code sync}/{@code tile} is non-null for the same slot (see GuiProfile.buildRects).
     *  Returns null (never a substituted default) only when a TileExpr's required field is absent
     *  from this frame's snapshot. */
    private Integer evalArg(int staticVal, GuiProfile.SyncExpr sync, GuiProfile.TileExpr tile,
                             UmbLegacyMenu menu, java.util.function.Function<String, Double> tileFields) {
        if (sync != null) return sync.evaluate(menu::getData);
        if (tile != null) return tile.evaluate(tileFields, leftPos, topPos);
        return staticVal;
    }

    /**
     * M1-POST-FIX kept {@link #extractLabels} unoverridden because the default title/
     * playerInventoryTitle drawing was already correct and every draw call this class added was a
     * background-layer one. SCREEN-RENDER lane adds real, foreground-layer content of its own
     * (custom gauge labels, item names, mode text -- {@link UmbLegacyMenu#labels}) that the default
     * implementation knows nothing about, so this now calls {@code super.extractLabels} FIRST
     * (preserving title/playerInventoryTitle byte-for-byte) and then draws the mod's own labels on
     * top -- foreground-layer coordinates are already panel-relative in vanilla 1.7.10 (see
     * {@link GuiProfile.Label}'s javadoc), so {@code leftPos}/{@code topPos} is added the same way
     * {@code titleLabelX}/{@code titleLabelY} already are.
     */
    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (legacyGuiMesh() != null) return;
        super.extractLabels(g, mouseX, mouseY);
        UmbLegacyMenu menu = getMenu();
        if (menu.labels.isEmpty()) return;
        try {
            for (GuiProfile.Label lbl : menu.labels) {
                Component text = lbl.translated ? Component.translatable(lbl.text) : Component.literal(lbl.text);
                g.text(this.font, text, lbl.x, lbl.y, 0x404040);
            }
        } catch (Throwable t) {
            AgentLog.errorOnce("UmbLegacyScreen.extractLabels:"
                    + (menu.textureLocation != null ? menu.textureLocation : "fallback"), t, 2);
        }
    }
}
