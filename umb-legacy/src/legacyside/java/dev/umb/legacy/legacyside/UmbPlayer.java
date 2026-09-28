package dev.umb.legacy.legacyside;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.stats.StatBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.DamageSource;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;

import dev.umb.bridge.api.HostPlayer;

/**
 * The EntityPlayer facade .
 * Subclasses {@code EntityPlayerMP} (concrete, and it IS the {@code ICrafting} the legacy {@code Container} pushes progress bars into - twin-first-mvp.md section 1b), allocated with {@code Unsafe.allocateInstance}: {@code...
 */
public final class UmbPlayer extends EntityPlayerMP {

    private static final String FACADE = "UmbPlayer";

    static {
        // real: func_70093_af, func_70005_c_, func_146105_b, func_145747_a, func_70694_bm,
        // func_71029_a, func_71110_a, func_71111_a, func_71112_a,
        // func_70097_a
        // func_71064_a = 11
        // stubbed: func_70670_a(addPotionEffect), func_71019_a(dropOneItem) = 2.
        UmbStub.declare(FACADE, 11, 2);
    }

    private HostPlayer host;
    /** Stable host identity for this facade; never changes when the short-lived adapter is rebound. */
    private String hostIdentityKey;
    private UmbInventoryPlayer umbInventory;
    // NOT a field initializer, deliberately: Unsafe.allocateInstance runs no constructor, so a
    // "= new int[32]" field declaration (woven into every constructor by javac) would never run.
    // create() below assigns it explicitly.
    private int[] syncData;
    /** The motion values {@link #pullMotion} copied in from the host, so {@link #pushMotion} can
     *  tell "legacy code changed the motion during this dispatch" from "nothing happened". */
    private double pulledMotionX, pulledMotionY, pulledMotionZ;

    /** Never actually invoked - instances come from {@link #create}; exists only so javac accepts
     *  an EntityPlayerMP subclass (it has no no-arg constructor). Dead code. */
    @SuppressWarnings("unused")
    private UmbPlayer(MinecraftServer server, WorldServer world, com.mojang.authlib.GameProfile profile,
                       ItemInWorldManager manager) {
        super(server, world, profile, manager);
    }

    public static UmbPlayer create(UmbWorld world, HostPlayer host) {
        return world.registerPlayer(host);
    }

    static UmbPlayer allocate(UmbWorld world, HostPlayer host) {
        UmbPlayer p = UmbUnsafe.allocate(UmbPlayer.class);
        p.bindHost(host);
        p.syncData = new int[32];
        // Replays, in constructor order, everything the skipped chain assigns — each
// Legacy compatibility behavior.
        // (Entity(World), EntityLivingBase(World), EntityPlayer(World,GameProfile),
        // EntityPlayerMP(server,world,profile,iwm)). Virtual calls (entityInit chain,
        // applyAttributes, setHealth, getDefaultEyeHeight, setPosition) resolve to the
        // vanilla bodies — UmbPlayer overrides none of them — so this is exact, not an
        // approximation. Each step logs through the world handle and continues: a
        // half-seeded facade with a precise log beats a dead player.
        seedEntityState(p, world);
        seedLivingBaseState(p, world);
        p.field_70170_p = world;
        p.field_71075_bZ = new PlayerCapabilities();
        p.mirrorHostGameMode(host);
        p.umbInventory = new UmbInventoryPlayer(p, host);
        p.field_71071_by = p.umbInventory;
        seedPlayerState(p, world, host);
        seedPlayerMPState(p, world);
        p.refreshPosition();
        seedForgeEntityState(p, world);
        return p;
    }

    /**
     * Entity(World) @0-176: flags, unique id, size, randomness, world, position,
     * dimension, dataWatcher + slots 0/1, then the virtual entityInit chain (slots
     * 6/7/8/9 via EntityLivingBase + 16/17/18 via EntityPlayer). boundingBox seeded
     * here like before (refreshPosition mutates it in place later).
     */
    private static void seedEntityState(UmbPlayer p, UmbWorld world) {
        try {
            Class<?> entity = net.minecraft.entity.Entity.class;
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(entity, "captureDrops"), false);
            UmbUnsafe.setInt(p, UmbUnsafe.field(entity, "field_145783_c"), nextEntityId());
            UmbUnsafe.setDouble(p, UmbUnsafe.field(entity, "field_70155_l"), 1.0D);
            UmbUnsafe.setField(p, UmbUnsafe.field(entity, "field_70121_D"),
                    AxisAlignedBB.func_72330_a(0, 0, 0, 0, 0, 0));
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(entity, "field_70135_K"), true);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(entity, "field_70130_N"), 0.6F);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(entity, "field_70131_O"), 1.8F);
            UmbUnsafe.setInt(p, UmbUnsafe.field(entity, "field_70150_b"), 1);
            UmbUnsafe.setField(p, UmbUnsafe.field(entity, "field_70146_Z"), new java.util.Random());
            UmbUnsafe.setInt(p, UmbUnsafe.field(entity, "field_70174_ab"), 1);
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(entity, "field_70148_d"), true);
            UmbUnsafe.setField(p, UmbUnsafe.field(entity, "field_96093_i"),
                    java.util.UUID.randomUUID());
            UmbUnsafe.setField(p, UmbUnsafe.field(entity, "field_70168_am"),
                    net.minecraft.entity.Entity.EnumEntitySize.SIZE_2);
            UmbUnsafe.setField(p, UmbUnsafe.field(entity, "field_70170_p"), world);
            p.func_70107_b(0.0D, 0.0D, 0.0D);
            try {
                net.minecraft.world.WorldProvider provider = world.field_73011_w;
                if (provider != null) {
                    UmbUnsafe.setInt(p, UmbUnsafe.field(entity, "field_71093_bK"),
                            provider.field_76574_g);
                }
            } catch (Throwable t) {
                world.host().log("[UMB] UmbPlayer dimension failed (non-fatal): " + t);
            }
            net.minecraft.entity.DataWatcher watcher =
                    new net.minecraft.entity.DataWatcher(p);
            UmbUnsafe.setField(p, UmbUnsafe.field(entity, "field_70180_af"), watcher);
            watcher.func_75682_a(0, Byte.valueOf((byte) 0));
            watcher.func_75682_a(1, Short.valueOf((short) 300));
            p.func_70088_a();
        } catch (Throwable t) {
            world.host().log("[UMB] UmbPlayer seedEntityState failed (non-fatal): " + t);
        }
    }

    private static int nextEntityId() {
        try {
            java.lang.reflect.Field next =
                    net.minecraft.entity.Entity.class.getDeclaredField("field_70152_a");
            next.setAccessible(true);
            int id = next.getInt(null);
            next.setInt(null, id + 1);
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** EntityLivingBase(World): combat/potions/equipment stores, hurt constants, attributes + health. */
    private static void seedLivingBaseState(UmbPlayer p, UmbWorld world) {
        try {
            Class<?> living = net.minecraft.entity.EntityLivingBase.class;
            UmbUnsafe.setField(p, UmbUnsafe.field(living, "field_94063_bt"),
                    new net.minecraft.util.CombatTracker(p));
            UmbUnsafe.setField(p, UmbUnsafe.field(living, "field_70713_bf"),
                    new java.util.HashMap<Object, Object>());
            UmbUnsafe.setField(p, UmbUnsafe.field(living, "field_82180_bT"),
                    new net.minecraft.item.ItemStack[5]);
            UmbUnsafe.setInt(p, UmbUnsafe.field(living, "field_70771_an"), 20);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(living, "field_70747_aH"), 0.02F);
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(living, "field_70752_e"), true);
            p.func_110147_ax();
            p.func_70606_j(p.func_110138_aP());
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(
                    net.minecraft.entity.Entity.class, "field_70156_m"), true);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(living, "field_70770_ap"),
                    (float) ((Math.random() + 1.0D) * 0.01D));
            UmbUnsafe.setFloat(p, UmbUnsafe.field(living, "field_70769_ao"),
                    (float) (Math.random() * 12398.0F));
            float yaw = (float) (Math.random() * Math.PI * 2.0D);
            p.field_70177_z = yaw;
            UmbUnsafe.setFloat(p, UmbUnsafe.field(living, "field_70759_as"), yaw);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(
                    net.minecraft.entity.Entity.class, "field_70138_W"), 0.5F);
        } catch (Throwable t) {
            world.host().log("[UMB] UmbPlayer seedLivingBaseState failed (non-fatal): " + t);
        }
    }

    private static volatile boolean containerFailedLogged;

    /**
     * EntityPlayer(World,GameProfile): spawn maps, ender chest, food, speeds, profile +
     * offline-style UUID, containers, eye height. The inventory is the facade's own
     * (assigned before this runs); spawn positioning is skipped (refreshPosition owns
     * the pose right after).
     */
    private static void seedPlayerState(UmbPlayer p, UmbWorld world, HostPlayer host) {
        try {
            Class<?> player = net.minecraft.entity.player.EntityPlayer.class;
            UmbUnsafe.setField(p, UmbUnsafe.field(player, "spawnChunkMap"),
                    new java.util.HashMap<Object, Object>());
            UmbUnsafe.setField(p, UmbUnsafe.field(player, "spawnForcedMap"),
                    new java.util.HashMap<Object, Object>());
            try {
                UmbUnsafe.setField(p, UmbUnsafe.field(player, "field_71078_a"),
                        new net.minecraft.inventory.InventoryEnderChest());
            } catch (Throwable t) {
                world.host().log("[UMB] UmbPlayer ender chest failed (non-fatal): " + t);
            }
            UmbUnsafe.setField(p, UmbUnsafe.field(player, "field_71100_bB"),
                    new net.minecraft.util.FoodStats());
            UmbUnsafe.setFloat(p, UmbUnsafe.field(player, "field_71108_cd"), 0.1F);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(player, "field_71102_ce"), 0.02F);
            if (host.getName() != null) {
                UmbUnsafe.setField(p, UmbUnsafe.field(player, "field_146106_i"),
                        new com.mojang.authlib.GameProfile(
                                java.util.UUID.nameUUIDFromBytes(("UmbPlayer:" + host.getName())
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                host.getName()));
            }
            try {
                // UmbWorld seeds isRemote=false at create: the universe is server-side,
                // matching the ctor's !world.isRemote argument exactly.
                net.minecraft.inventory.ContainerPlayer containers =
                        new net.minecraft.inventory.ContainerPlayer(
                                p.field_71071_by, true, p);
                UmbUnsafe.setField(p, UmbUnsafe.field(player, "field_71069_bz"), containers);
                UmbUnsafe.setField(p, UmbUnsafe.field(player, "field_71070_bA"), containers);
            } catch (Throwable t) {
                // Logged once: allocate() runs per player join, and every allocation
                // would otherwise reprint this.
                if (!containerFailedLogged) {
                    containerFailedLogged = true;
                    world.host().log("[UMB] UmbPlayer containers failed (non-fatal): " + t);
                }
            }
            UmbUnsafe.setFloat(p, UmbUnsafe.field(player, "eyeHeight"), p.getDefaultEyeHeight());
            UmbUnsafe.setInt(p, UmbUnsafe.field(
                    net.minecraft.entity.Entity.class, "field_70174_ab"), 20);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(
                    net.minecraft.entity.EntityLivingBase.class, "field_70741_aB"), 180.0F);
        } catch (Throwable t) {
            world.host().log("[UMB] UmbPlayer seedPlayerState failed (non-fatal): " + t);
        }
    }

    /**
     * EntityPlayerMP(server,world,profile,iwm): language, packet queues, anti-spam
     * trackers, item-in-world manager (+backref), a directly constructed StatisticsFile
     * (the real one needs a dedicated server's save handler; the ctor is pure), and the
     * two zero-outs. Spawn positioning/collision nudge skipped (refreshPosition owns
     * the pose). The network field is a minimal loopback handler: MP code may announce
     * attach/position state, but no packet is sent to a native connection.
     */
    private static void seedPlayerMPState(UmbPlayer p, UmbWorld world) {
        try {
            Class<?> mp = net.minecraft.entity.player.EntityPlayerMP.class;
            UmbUnsafe.setField(p, UmbUnsafe.field(mp, "field_71148_cg"), "en_US");
            UmbUnsafe.setField(p, UmbUnsafe.field(mp, "field_71129_f"),
                    new java.util.LinkedList<Object>());
            UmbUnsafe.setField(p, UmbUnsafe.field(mp, "field_71130_g"),
                    new java.util.LinkedList<Object>());
            UmbUnsafe.setFloat(p, UmbUnsafe.field(mp, "field_130068_bO"), Float.MIN_VALUE);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(mp, "field_71149_ch"), -1.0E8F);
            UmbUnsafe.setInt(p, UmbUnsafe.field(mp, "field_71146_ci"), -99999999);
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(mp, "field_71147_cj"), true);
            UmbUnsafe.setInt(p, UmbUnsafe.field(mp, "field_71144_ck"), -99999999);
            UmbUnsafe.setInt(p, UmbUnsafe.field(mp, "field_147101_bU"), 60);
            UmbUnsafe.setBoolean(p, UmbUnsafe.field(mp, "field_71140_co"), true);
            UmbUnsafe.setLong(p, UmbUnsafe.field(mp, "field_143005_bX"),
                    System.currentTimeMillis());
            UmbUnsafe.setField(p, UmbUnsafe.field(mp, "field_71135_a"),
                    UmbLoopbackNetHandler.create(p));
            try {
                net.minecraft.server.management.ItemInWorldManager iwm =
                        new net.minecraft.server.management.ItemInWorldManager(world);
                UmbUnsafe.setField(iwm,
                        UmbUnsafe.field(net.minecraft.server.management.ItemInWorldManager.class,
                                "field_73090_b"), p);
                UmbUnsafe.setField(p, UmbUnsafe.field(mp, "field_71134_c"), iwm);
            } catch (Throwable t) {
                world.host().log("[UMB] UmbPlayer itemInWorldManager failed (non-fatal): " + t);
            }
            try {
                UmbUnsafe.setField(p, UmbUnsafe.field(mp, "field_147103_bO"),
                        new net.minecraft.stats.StatisticsFile(null,
                                new java.io.File(System.getProperty("java.io.tmpdir", "."),
                                        "umb-facade-stats.json")));
            } catch (Throwable t) {
                world.host().log("[UMB] UmbPlayer stats file failed (non-fatal): " + t);
            }
            UmbUnsafe.setFloat(p, UmbUnsafe.field(
                    net.minecraft.entity.Entity.class, "field_70138_W"), 0.0F);
            UmbUnsafe.setFloat(p, UmbUnsafe.field(
                    net.minecraft.entity.Entity.class, "field_70129_M"), 0.0F);
        } catch (Throwable t) {
            world.host().log("[UMB] UmbPlayer seedPlayerMPState failed (non-fatal): " + t);
        }
    }

    /**
 * Replays the Forge part of {@code Entity(World)} that Unsafe.allocateInstance skipped : {@code extendedProperties = new HashMap}, post {@code EntityEvent.EntityConstructing} (where every mod registers its IExtendedEntityProperties), then {@code init(entity,...
 */
    private static void seedForgeEntityState(UmbPlayer p, net.minecraft.world.World world) {
        if (p.extendedProperties == null) p.extendedProperties = new java.util.HashMap<>();
        if (p.capturedDrops == null) p.capturedDrops = new java.util.ArrayList<>();
        try {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new net.minecraftforge.event.entity.EntityEvent.EntityConstructing(p));
        } catch (Throwable t) {
            System.out.println("[UMB] UmbPlayer EntityConstructing failed (props registered so far kept): " + t);
        }
        for (net.minecraftforge.common.IExtendedEntityProperties props
                : new java.util.ArrayList<>(p.extendedProperties.values())) {
            try {
                props.init(p, world);
            } catch (Throwable t) {
                System.out.println("[UMB] UmbPlayer extended property init failed: " + props.getClass().getName() + ": " + t);
            }
        }
    }

    /**
     * Forge's EntityPlayer.openGui forwards to FMLNetworkHandler.openGui, whose original body
     * sends the container to a network channel nobody reads when the class is not transformed.
     * Every legacy player is an UmbPlayer, so route here directly: the GUI bridge then publishes
     * the container to the host menu for key/packet-opened GUIs as well as block interactions.
     */
    @Override
    public void openGui(Object mod, int modGuiId, net.minecraft.world.World world, int x, int y, int z) {
        if (dev.umb.legacy.legacyside.input.LegacyInputDiag.oncePer("umbplayer-opengui", 3_000_000_000L)) {
            dev.umb.legacy.legacyside.input.LegacyInputDiag.log("UmbPlayer.openGui mod="
                    + (mod == null ? "null" : mod.getClass().getName()) + " id=" + modGuiId
                    + " at " + x + "," + y + "," + z);
        }
        UmbGui.openGui(this, mod, modGuiId, world, x, y, z);
    }

    void bindHost(HostPlayer host) {
        // HostPlayerImpl is a short-lived adapter.  Keep this UmbPlayer object (and its
        // field_70154_o/field_70153_n links) as the only legacy identity for the native player;
        // replacing the facade is what makes vehicle code treat a mounted rider as stale.
        net.minecraft.entity.Entity vehicle = field_70154_o;
        net.minecraft.entity.Entity passenger = field_70153_n;
        if (this.hostIdentityKey == null && host != null) {
            String key = host.getIdentityKey();
            if (key == null || key.length() == 0) {
                key = host.getName();
            }
            this.hostIdentityKey = key == null ? "<unnamed>" : key;
        }
        this.host = host;
        mirrorHostGameMode(host);
        if (vehicle != null && vehicle.field_70153_n != this) {
            vehicle.field_70153_n = this;
        }
        if (passenger != null && passenger.field_70154_o != this) {
            passenger.field_70154_o = this;
        }
    }

    /**
     * Carries the host game mode into the legacy PlayerCapabilities on EVERY rebind (dispatch,
     * rider mirroring, interact), not only on the input path: vehicle/fuel/damage logic reads
     * capabilities from whichever facade is the rider (SRG field_75098_d isCreativeMode,
     * field_75101_c allowFlying, field_75102_a disableDamage).
     */
    void mirrorHostGameMode(HostPlayer host) {
        if (host == null || field_71075_bZ == null) return;
        boolean creative;
        try {
            creative = host.isCreative();
        } catch (Throwable ignored) {
            return;
        }
        PlayerCapabilities caps = field_71075_bZ;
        if (caps.field_75098_d != creative) {
            caps.field_75098_d = creative;
            caps.field_75101_c = creative;
            caps.field_75102_a = creative;
            if (!creative) caps.field_75100_b = false;
        }
    }

    /** Package-private identity used by UmbWorld's canonical player registry. */
    String hostIdentityKey() {
        return hostIdentityKey;
    }

    private static volatile long lastTickLog;
    private static volatile long lastTickFailLog;
    private static volatile int lastLoggedSelected = Integer.MIN_VALUE;

    /**
     * INPUT-BRIDGE inventory tick (universal vanilla {@code Item.onUpdate} contract).
     *
     * <p>The SOLE per-slot tick path (exactly-once): the host-driven
     * {@code itemInventoryTick} forwarder must not run {@code onUpdate} (its throwaway
     * copies + write-back corrupt stateful items — e.g. the gun's unselected-slot
     * reset loop refires DRAWING every tick). Ticks every non-null stack of the main
     * inventory with the real slot index, {@code selected} only for the current slot,
     * plus the armor array (best-effort indices, never selected) — mirroring vanilla
     * {@code EntityPlayer.onUpdate} coverage. A misbehaving item is logged throttled,
     * never thrown: it must not break the server tick.
     */
    public void tickInventoryItems(UmbWorld world) {
        pullInventory();
        if (field_71071_by == null) {
            return;
        }
        int ticked = 0;
        ticked += tickArray(world, field_71071_by.field_70462_a, 0);
        ticked += tickArray(world, field_71071_by.field_70460_b, 0);
        // Selection changes log immediately (short sessions may never reach the
        // heartbeat); otherwise throttled. Proves per-tick selection + state live.
        long now = System.nanoTime();
        int selected = field_71071_by.field_70461_c;
        if (ticked > 0 && (selected != lastLoggedSelected || now - lastTickLog > 60_000_000_000L)) {
            lastTickLog = now;
            lastLoggedSelected = selected;
            ItemStack held = field_71071_by.func_70448_g();
            world.host().log("[UMB-INPUT] inventory tick: " + ticked + " stack(s), selected="
                    + selected + heldInfo(held));
        }
    }

    /** Identity hash for live object-identity proofs (same object ticked vs fired?). */
    static String idHex(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }

    /** Generic held-stack dump (class + NBT, truncated): no mod knowledge. */
    private static String heldInfo(ItemStack held) {
        try {
            if (held == null || held.func_77973_b() == null) {
                return " held=null";
            }
            String nbt = "";
            try {
                if (held.field_77990_d != null) {
                    nbt = held.field_77990_d.toString();
                    if (nbt.length() > 300) {
                        nbt = nbt.substring(0, 300) + "...";
                    }
                }
            } catch (Throwable ignored) {
            }
            return " held=" + held.func_77973_b().getClass().getName() + " stackId="
                    + idHex(held) + " nbt={" + nbt + "}";
        } catch (Throwable t) {
            return "";
        }
    }

    private int tickArray(UmbWorld world, ItemStack[] array, int base) {
        if (array == null) {
            return 0;
        }
        int selected = field_71071_by.field_70461_c;
        int ticked = 0;
        for (int i = 0; i < array.length; i++) {
            ItemStack stack = array[i];
            if (stack == null || stack.func_77973_b() == null) {
                continue;
            }
            try {
                stack.func_77973_b().func_77663_a(stack, world, this, base + i,
                        i == selected);
                ticked++;
            } catch (Throwable t) {
                long now = System.nanoTime();
                if (now - lastTickFailLog > 60_000_000_000L) {
                    lastTickFailLog = now;
                    world.host().log("[UMB-INPUT] inventory tick failed (non-fatal): " + t);
                }
            }
        }
        return ticked;
    }

    /**
 * Copies the host's live pose into the inherited fields - call before each bridge entry.
 * Position AND rotation: until the , only posX/Y/Z crossed, so {@code EntityPlayer.func_70676_i} (getLook, methods.csv) always aimed along a fixed zero-rotation vector and...
 */
    public void refreshPosition() {
        // Position refreshes happen from World entity queries during aircraft onUpdate.  They
        // must never turn a cached facade refresh into a graph refresh: preserve both vanilla
        // riding links before and after copying the host pose.
        net.minecraft.entity.Entity vehicle = field_70154_o;
        net.minecraft.entity.Entity passenger = field_70153_n;
        field_70165_t = host.getX();
        field_70163_u = host.getY();
        field_70161_v = host.getZ();
        field_70169_q = field_70165_t;
        field_70167_r = field_70163_u;
        field_70166_s = field_70161_v;
        // fields.csv: field_70177_z=rotationYaw, field_70125_A=rotationPitch,
        // field_70126_B=prevRotationYaw, field_70127_C=prevRotationPitch. HostPlayer.getYaw/
        // getPitch carry identical degrees semantics (see their javadoc) - identity mapping.
        field_70177_z = host.getYaw();
        field_70125_A = host.getPitch();
        field_70126_B = field_70177_z;
        field_70127_C = field_70125_A;
        // TICK/: field_70121_D (boundingBox, fields.csv) was never seeded, so any
        // legacy contact/AABB code reading entity.boundingBox NPE'd. Standard 1.7.10 player size
        // (0.6 wide, 1.8 tall), anchored at the feet like EntityPlayer.setPosition does.
        // field_70121_D is FINAL on 1.7.10 Entity (javac: "cannot assign a value to final
        // variable") - mutate it in place via func_72324_b (setBounds, methods.csv) instead.
        field_70121_D.func_72324_b(
                field_70165_t - 0.3D, field_70163_u, field_70161_v - 0.3D,
                field_70165_t + 0.3D, field_70163_u + 1.8D, field_70161_v + 0.3D);
        if (vehicle != null && vehicle.field_70153_n != this) {
            vehicle.field_70153_n = this;
        }
        if (passenger != null && passenger.field_70154_o != this) {
            passenger.field_70154_o = this;
        }
    }

    /** Host -&gt; legacy motion (26.2 deltaMovement -&gt; field_70159_w/field_70181_x/field_70179_y,
     *  fields.csv) - call before an entity-contact dispatch so legacy code that ADDS to the
     *  motion (conveyor push) starts from the truth, not from zero. */
    public void pullMotion() {
        pulledMotionX = host.getMotionX();
        pulledMotionY = host.getMotionY();
        pulledMotionZ = host.getMotionZ();
        field_70159_w = pulledMotionX;
        field_70181_x = pulledMotionY;
        field_70179_y = pulledMotionZ;
    }

    /** Legacy motion -&gt; host, ONLY when legacy code changed it during the dispatch - an
     *  unconditional write-back every contact tick would stomp the host's own physics with a
     *  stale copy. */
    public void pushMotion() {
        if (field_70159_w != pulledMotionX || field_70181_x != pulledMotionY
                || field_70179_y != pulledMotionZ) {
            host.setMotion(field_70159_w, field_70181_x, field_70179_y);
        }
    }

    /** Host -&gt; legacy inventory array; call before handing control to legacy code. */
    public void pullInventory() {
        umbInventory.pull();
    }

    /** Legacy inventory array -&gt; host; call after legacy code returns control. */
    public void pushInventory() {
        umbInventory.push();
    }

    /** The last value written into each {@code ICrafting} data slot (progress bars). */
    public int[] syncData() {
        return syncData.clone();
    }

    // ---- real overrides ----

    @Override
    public boolean func_70093_af() {
        return host.isSneaking();
    }

/** Legacy compatibility behavior. */
    @Override
    public void func_70078_a(net.minecraft.entity.Entity vehicle) {
        long now = System.nanoTime();
        net.minecraft.entity.Entity beforeVehicle = field_70154_o;
        net.minecraft.entity.Entity beforePassenger = vehicle == null ? null : vehicle.field_70153_n;
// Legacy compatibility behavior.
        // it calls EntityPlayer.func_70078_a and then unconditionally sends S1B through
        // field_71135_a. The loopback NetHandlerPlayServer is link-safe, but the direct
        // Entity.func_70078_a operation avoids a native-network side effect on this path.
        mountVanillaEntity(vehicle);
        net.minecraft.entity.Entity afterVehicle = field_70154_o;
        net.minecraft.entity.Entity afterPassenger = vehicle == null ? null : vehicle.field_70153_n;
        boolean changed = beforeVehicle != afterVehicle || beforePassenger != afterPassenger;
        if (changed || now - lastMountTraceNanos >= 1_000_000_000L) {
            lastMountTraceNanos = now;
            traceMount(vehicle, beforeVehicle, beforePassenger, afterVehicle, afterPassenger);
        }
    }

    private static volatile long lastMountTraceNanos;

    /** The 1.7.10 Entity.func_70078_a body, without EntityPlayerMP's network notification. */
    private void mountVanillaEntity(net.minecraft.entity.Entity vehicle) {
        UmbUnsafe.setDouble(this, UmbUnsafe.field(net.minecraft.entity.Entity.class, "field_70149_e"), 0.0D);
        UmbUnsafe.setDouble(this, UmbUnsafe.field(net.minecraft.entity.Entity.class, "field_70147_f"), 0.0D);
        if (vehicle == null) {
            net.minecraft.entity.Entity current = field_70154_o;
            if (current != null) {
                func_70012_b(current.field_70165_t,
                        current.field_70121_D.field_72338_b + current.field_70131_O,
                        current.field_70161_v, field_70177_z, field_70125_A);
                current.field_70153_n = null;
            }
            field_70154_o = null;
            return;
        }
        if (field_70154_o != null) {
            field_70154_o.field_70153_n = null;
        }
        net.minecraft.entity.Entity cursor = vehicle;
        while (cursor.field_70154_o != null) {
            if (cursor.field_70154_o == this) {
                return;
            }
            cursor = cursor.field_70154_o;
        }
        field_70154_o = vehicle;
        vehicle.field_70153_n = this;
    }

    private void traceMount(net.minecraft.entity.Entity vehicle,
            net.minecraft.entity.Entity beforeVehicle, net.minecraft.entity.Entity beforePassenger,
            net.minecraft.entity.Entity afterVehicle, net.minecraft.entity.Entity afterPassenger) {
        try {
            if (!(field_70170_p instanceof UmbWorld)) {
                return;
            }
            StringBuilder line = new StringBuilder("[UMB-ENTITY] legacy mountEntity player=")
                    .append(func_70005_c_()).append(" vehicle=")
                    .append(vehicle == null ? "null" : vehicle.getClass().getName() + "@"
                            + System.identityHashCode(vehicle))
                    .append(" beforeVehicle=").append(identity(beforeVehicle))
                    .append(" beforePassenger=").append(identity(beforePassenger))
                    .append(" afterVehicle=").append(identity(afterVehicle))
                    .append(" afterPassenger=").append(identity(afterPassenger));
            StackTraceElement[] stack = new Throwable().getStackTrace();
            int limit = Math.min(8, stack.length);
            for (int i = 1; i < limit; i++) {
                line.append(" <- ").append(stack[i].getClassName()).append('#')
                        .append(stack[i].getMethodName()).append(':').append(stack[i].getLineNumber());
            }
            ((UmbWorld) field_70170_p).host().log(line.toString());
        } catch (Throwable ignored) {
            // Mount diagnostics must never alter the legacy riding operation.
        }
    }

    private static String identity(net.minecraft.entity.Entity value) {
        return value == null ? "null" : value.getClass().getName() + "@"
                + System.identityHashCode(value);
    }

    @Override
    public java.lang.String func_70005_c_() {
        return host.getName();
    }

    @Override
    public void func_146105_b(IChatComponent chat) {
        host.sendMessage(chat == null ? "" : chat.func_150260_c());
    }

    @Override
    public void func_145747_a(IChatComponent chat) {
        host.sendMessage(chat == null ? "" : chat.func_150260_c());
    }

    @Override
    public ItemStack func_70694_bm() {
        // Persistent facade-array stack, never a fresh conversion: mod packet handlers
        // mutate the returned stack's NBT (gun flags/timers) and read it back on later
        // ticks — a throwaway would discard every mutation silently (found live: gun
        // packets arrived, the handler ran, NBT landed on garbage). Falls back to a
        // fresh conversion only when the facade array holds nothing.
        if (field_71071_by != null) {
            ItemStack current = field_71071_by.func_70448_g();
            if (current != null) {
                return current;
            }
        }
        return UmbItemConv.toLegacy(host.getHeldItem());
    }

    @Override
    public void func_71029_a(StatBase stat) {
        // no-op: 26.2 has its own stat/achievement system, legacy stats do not cross the boundary
    }

    /**
     * func_71064_a - addStat(StatBase, int) (methods.csv: "Adds a value to a statistic field").
     * The inherited EntityPlayerMP body dereferences {@code field_147103_bO} (theStatisticsFile),
     * which this facade never initializes (Unsafe.allocateInstance runs no constructor), so ANY
     * mod granting a stat/achievement (MC Heli's MCH_Achievement.addStat on every vehicle spawn,
     * vanilla/HBM craft/pickup/kill stats on their paths) NPE'd the whole dispatch AFTER doing
     * its real work - the spawn landed but useItemRightClick still returned null. Same no-op
     * rationale as {@link #func_71029_a}: 26.2 tracks its own stats; legacy ones stay local.
     * Universal: every mod shares this one player facade.
     */
    @Override
    public void func_71064_a(StatBase stat, int amount) {
        // no-op: see above - drop the stat instead of NPEing the dispatch that earned it
    }

    /**
 * func_70097_a - attackEntityFrom (methods.csv), TICK/: real via {@code HostPlayer.hurt}.
 * The inherited EntityPlayerMP body is unusable here anyway (it reads combat-tracker/capability fields Unsafe.allocateInstance never initialized).
 */
    @Override
    public boolean func_70097_a(DamageSource source, float amount) {
        host.hurt(source == null ? null : source.field_76373_n, amount);
        return true;
    }

    @Override
    protected void func_70670_a(PotionEffect effect) {
        UmbStub.hit("UmbPlayer", "func_70670_a(addPotionEffect)");
    }

    @Override
    public EntityItem func_71019_a(ItemStack stack, boolean flag) {
// Legacy compatibility behavior.
        // furnace path drops items into the world) - counted stub, not a silent no-op.
        UmbStub.hit("UmbPlayer", "func_71019_a(dropOneItem)");
        return null;
    }

    // ---- ICrafting: override all 3 so the inherited network-facing bodies do not emit
    //      unsolicited slot traffic during host-owned synchronization ----

    @Override
    public void func_71110_a(Container container, java.util.List currentlyUsedItemStacks) {
        // no-op: 26.2 syncs slot contents itself
    }

    @Override
    public void func_71111_a(Container container, int slot, ItemStack stack) {
        // no-op: 26.2 syncs slot contents itself
    }

    @Override
    public void func_71112_a(Container container, int id, int value) {
        if (id >= 0 && id < syncData.length) {
            // fml/conf/fields.csv:419 documents sendProgressBarUpdate: non-local 1.7.10
            // transport truncates both ints to signed shorts before GuiContainer.updateProgressBar
            // observes them.  26.2 DataSlot carries ints, so preserve the legacy wire result here.
            syncData[id] = (short) value;
        }
    }
}
