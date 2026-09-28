package dev.umb.hostagent.content;

import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.AgentLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** {@link HostPlayer} over a {@link ServerPlayer}. Server thread only, per the bridge's contract. */
final class HostPlayerImpl implements HostPlayer {

    private final ServerPlayer player;

    HostPlayerImpl(ServerPlayer player) {
        this.player = player;
    }

    ServerPlayer player() {
        return player;
    }

    @Override
    public void openLegacyContainer(ContainerHandle handle, String title, int x, int y, int z) {
        if (handle == null) return;
        try {
            // Key-held legacy handlers can request the same menu on every client tick.  Keep
            // one host menu while it is already open; closing it returns containerMenu to the
            // normal inventory menu and permits the next request to open again.
            if (player.containerMenu instanceof UmbLegacyMenu) return;
            String display = title == null || title.isEmpty() ? "Legacy GUI" : title;
            player.openMenu(new UmbMenuProvider(handle, Component.literal(display), null, x, y, z));
            AgentLog.line("[UMB-GUI] opened legacy container title=" + display);
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.openLegacyContainer", t, 3);
        }
    }

    @Override
    public String getName() {
        return player.getScoreboardName();
    }

    @Override
    public int getPing() {
        try {
            Object connection = player.connection;
            if (connection == null) return 0;
            for (String methodName : new String[] {"latency", "getLatency"}) {
                try {
                    java.lang.reflect.Method method = connection.getClass().getMethod(methodName);
                    Object value = method.invoke(connection);
                    if (value instanceof Number) {
                        return Math.max(0, ((Number) value).intValue());
                    }
                } catch (NoSuchMethodException ignored) {
                    // 26.2 mappings have exposed both names across host mappings.
                }
            }
        } catch (Throwable ignored) {
            // Player-list rendering must remain usable when a headless host has no connection.
        }
        return 0;
    }

    @Override
    public String getIdentityKey() {
        return player.getUUID().toString();
    }

    @Override
    public boolean isCreative() {
        return player.isCreative();
    }

    @Override
    public boolean isSneaking() {
        return player.isShiftKeyDown();
    }

    @Override
    public double getX() {
        return player.getX();
    }

    @Override
    public double getY() {
        return player.getY();
    }

    @Override
    public double getZ() {
        return player.getZ();
    }

    /**
     * mcheli-vehicles lane: look rotation for the legacy facade. 26.2 {@code getYRot}/{@code getXRot}
     * (javap-verified on {@code net.minecraft.world.entity.Entity} against
     * research/jars/26.2/client.jar) carry the same degrees scale/orientation Mojang has always
     * used, which is exactly 1.7.10's rotationYaw/rotationPitch contract - identity mapping.
     */
    @Override
    public float getYaw() {
        try {
            return player.getYRot();
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    @Override
    public float getPitch() {
        try {
            return player.getXRot();
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /**
     * Automation lane (input-lane round 13 follow-up): the server-side selected hotbar
     * slot, so legacy code can read the selection instead of matching it by fallback.
     * -1 when unreadable (never a fabricated slot 0: -1 matches nothing, callers fall
     * back). Out of range (should not happen - vanilla clamps 0-8) is returned as-is,
     * honestly, rather than clamped into a lie.
     */
    @Override
    public int getSelectedSlot() {
        try {
            return player.getInventory().getSelectedSlot();
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.getSelectedSlot", t, 2);
            return -1;
        }
    }

    @Override
    public StackData getHeldItem() {
        try {
            return LegacyStackConv.toLegacy(player.getMainHandItem());
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.getHeldItem", t, 2);
            return StackData.EMPTY;
        }
    }

    /**
     * FM-6: the exact erasure shape already fixed once for {@link #setInventorySlot} (see its
     * javadoc) applies here too - a native 26.2 item with no legacy twin round-trips to
     * {@code StackData.EMPTY} on the way out ({@link #getHeldItem}), so an incoming EMPTY here
     * cannot be told apart from "legacy code never touched this". Never convert that to
     * {@code ItemStack.EMPTY} and write it back over a real, unmappable held item.
     */
    @Override
    public void setHeldItem(StackData s) {
        try {
            ItemStack incoming = LegacyStackConv.toNative(s);
            if (incoming.isEmpty()) {
                ItemStack current = player.getMainHandItem();
                if (!current.isEmpty() && !LegacyStackConv.isKnownLegacyItem(current.getItem())) {
                    return;
                }
            }
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, incoming);
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.setHeldItem", t, 2);
        }
    }

    @Override
    public void startUsingItem() {
        try {
            // 26.2 javap-verified LivingEntity.startUsingItem(InteractionHand). The legacy
            // facade has exactly one held item, so its native counterpart is always MAIN_HAND.
            player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.startUsingItem", t, 2);
        }
    }

    @Override
    public void sendMessage(String text) {
        try {
            if (!ChatDeliveryDeduplicator.accept(player.getUUID().toString(), text,
                    System.nanoTime())) {
                return;
            }
            player.sendSystemMessage(Component.literal(text));
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.sendMessage", t, 2);
        }
    }

    @Override
    public StackData getInventorySlot(int i) {
        try {
            Inventory inv = player.getInventory();
            if (i < 0 || i >= inv.getContainerSize()) return StackData.EMPTY;
            return LegacyStackConv.toLegacy(inv.getItem(i));
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.getInventorySlot", t, 2);
            return StackData.EMPTY;
        }
    }

    @Override
    public void setInventorySlot(int i, StackData s) {
        try {
            Inventory inv = player.getInventory();
            if (i < 0 || i >= inv.getContainerSize()) return;
            ItemStack incoming = LegacyStackConv.toNative(s);
            if (incoming.isEmpty()) {
                ItemStack current = inv.getItem(i);
                // LegacyBridgeImpl.activate() unconditionally round-trips all 36 player-inventory
                // slots through pullInventory()/pushInventory() on EVERY legacy-container open,
                // even though HBM's furnace (and most legacy containers) never touch the player's
                // own inventory. getInventorySlot() already returns StackData.EMPTY for a native
                // item with no legacy id (LegacyStackConv.toLegacy logs "no legacy id for native
                // item ..." -- see research/out/legacy/win-m3/logs/hostagent.log:42-44 for the
                // exact minecraft:oak_log/coal_block/iron_ore hits from the live M1 run), so an
                // untouched slot holding an item with NO legacy representation at all comes back
                // from the legacy side as "empty" too. Writing that EMPTY straight back here is
                // what erased the player's real wood/coal/iron ore the moment they opened the
                // furnace (win-m3/shots 11-crop.png -> 12-crop.png). If the slot's CURRENT native
                // item has no legacy representation either (checked against BOTH Registrar's
                // HBM/modded maps AND VanillaItemBridge's minecraft:* maps -- G2-vanilla-bridge
                // added real vanilla coverage, e.g. minecraft:coal now DOES round-trip, so the
                // guard must stay in sync with everything LegacyStackConv itself can convert),
                // this "empty" cannot be a real legacy-side clear (legacy code was never shown
                // anything but empty for it) -- it is purely a round-trip artifact, so leave the
                // real item alone instead of erasing it.
                if (!current.isEmpty() && !LegacyStackConv.isKnownLegacyItem(current.getItem())) {
                    return;
                }
            }
            inv.setItem(i, incoming);
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.setInventorySlot", t, 2);
        }
    }

    @Override
    public int getInventorySize() {
        try {
            return player.getInventory().getContainerSize();
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- TICK/CONTACT lane: motion + damage ----

    @Override
    public double getMotionX() {
        try {
            return player.getDeltaMovement().x;
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    @Override
    public double getMotionY() {
        try {
            return player.getDeltaMovement().y;
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    @Override
    public double getMotionZ() {
        try {
            return player.getDeltaMovement().z;
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    /**
     * hurtMarked is what makes this REAL for a player: a ServerPlayer's motion is
     * client-authoritative, so a bare setDeltaMovement is invisible until the server sends the
     * motion packet - hurtMarked=true (public field, javap-verified) is vanilla's own "send
     * ClientboundSetEntityMotionPacket next tick" flag (knockback uses the same mechanism).
     */
    @Override
    public void setMotion(double mx, double my, double mz) {
        try {
            player.setDeltaMovement(mx, my, mz);
            player.hurtMarked = true;
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.setMotion", t, 2);
        }
    }

    @Override
    public void hurt(String legacyDamageType, float amount) {
        try {
            if (!(amount > 0.0F)) {
                return; // NaN/zero/negative: nothing 1.7.10 would have applied either
            }
            player.hurtServer(player.level(), resolveDamage(legacyDamageType), amount);
        } catch (Throwable t) {
            AgentLog.error("HostPlayerImpl.hurt", t, 2);
        }
    }

    /** Legacy damage types already seen without a native mapping - logged once each, then generic. */
    private static final Set<String> UNMAPPED_DAMAGE_TYPES = ConcurrentHashMap.newKeySet();

    /**
     * 1.7.10 DamageSource.damageType (field_76373_n) -&gt; a native 26.2 source. The vanilla names
     * below are 1.7.10's own DamageSource statics; every 26.2 factory is javap-verified against
     * DamageSources. Anything else (mod-custom types like HBM's radiation, plus 1.7.10 types with
     * no no-arg 26.2 factory: explosion/anvil/fallingBlock/mob/player/arrow/thrown) is GENERIC -
     * the damage still lands, only the attribution text is lost; logged once per type so the
     * remainder stays countable.
     */
    private DamageSource resolveDamage(String legacyDamageType) {
        DamageSources s = player.level().damageSources();
        if (legacyDamageType != null) {
            switch (legacyDamageType) {
                case "inFire": return s.inFire();
                case "onFire": return s.onFire();
                case "lava": return s.lava();
                case "inWall": return s.inWall();
                case "drown": return s.drown();
                case "starve": return s.starve();
                case "cactus": return s.cactus();
                case "fall": return s.fall();
                case "outOfWorld": return s.fellOutOfWorld();
                case "magic": return s.magic();
                case "wither": return s.wither();
                case "lightningBolt": return s.lightningBolt();
                case "generic": return s.generic();
                default:
                    if (UNMAPPED_DAMAGE_TYPES.add(legacyDamageType)) {
                        AgentLog.line("HostPlayerImpl.hurt: no native mapping for legacy damage type '"
                                + legacyDamageType + "' - dealing generic damage (attribution lost)");
                    }
            }
        }
        return s.generic();
    }
}
