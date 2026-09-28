package dev.umb.legacy1165.legacyside;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * {@code dev.umb.bridge.api.TileHandle} over a raw 1.16.5 {@code TileEntity} - same shape as
 * 1.7.10's {@code TileHandleImpl} (poison-on-throw ticking, NBT as {@code byte[]} via
 * stream-based {@code CompressedStreamTools}, validity from the removed-flag).
 *
 * with 1.7.10), write = {@code func_189515_b(CompoundNBT)}, read =
 * {@code func_230337_a_(BlockState, CompoundNBT)}, removed = {@code func_145837_r} (stable),
 * NBT stream pair {@code func_74799_a/func_74796_a}.</p>
 */
public final class TileHandle1165 implements TileHandle {

    private final TileEntity te;
    private final UmbWorld1165 world;
    private final BlockPos pos;
    private volatile boolean poisoned = false;
    private volatile String poisonReason = null;
    private volatile boolean tickMethodResolved = false;
    private volatile Method reflectiveTickMethod = null;
    private long tickCalls;
    private long interfaceTickCalls;
    private long reflectiveTickCalls;
    private long noCallbackCalls;
    private long worldBindCalls;
    private volatile boolean lastWorldRemote;
    private volatile String lastCallback = "none";

    public TileHandle1165(TileEntity te, UmbWorld1165 world, BlockPos pos) {
        this.te = te;
        this.world = world;
        this.pos = pos;
    }

    /** The raw legacy TileEntity - package-private, for Legacy1165BridgeImpl only. */
    TileEntity raw() {
        return te;
    }

    BlockPos pos() {
        return pos;
    }

    /**
     * Render-capture seam: same signature as {@code EntityHandle1165.renderCapture(float)}
     * so {@code LegacyTileCaptureClient} can invoke it via reflection on any
     * {@code TileHandle1165} instance.
     *
     * <p>Delegates to {@code LegacyTileRenderCapture1165Client} which holds the headless
     * {@code TileEntityRendererDispatcher} populated during the lifecycle's
     * {@code installEntityRenderers} stage.</p>
     *
     * @param partialTick fractional tick [0,1] forwarded to the renderer
     * @return an {@code EntityRenderCapture} wrapping the recorded vertex draw calls, or an
     *         empty capture with a diagnostic reason when no renderer is registered or the
     *         dispatcher was not installed
     */
    public EntityRenderCapture renderCapture(float partialTick) {
        return LegacyTileRenderCapture1165Client.capture(te, partialTick);
    }

    /** Vanilla World.setTileEntity binds the TE before it enters the world's tick list. */
    void bindWorldAndPos() {
        te.func_226984_a_(world, pos);
        worldBindCalls++;
    }

    @Override
    public void tick() {
        if (poisoned) {
            return;
        }
        tickCalls++;
        lastWorldRemote = world.field_72995_K;
        try {
            if (te instanceof ITickableTileEntity) {
                ((ITickableTileEntity) te).func_73660_a();
                interfaceTickCalls++;
                lastCallback = "ITickableTileEntity.func_73660_a";
                return;
            }
            // Forge's registered TileEntityType ticker is allowed to be supplied by a mod
            // without making the concrete TileEntity implement the legacy marker interface.
            // Preserve the direct SRG path above, then honor the same callback by name for
            // those mod-owned master TEs (the MCP-transformed runtime may expose `tick`).
            Method callback = reflectiveTickMethod();
            if (callback != null) {
                callback.invoke(te);
                reflectiveTickCalls++;
                lastCallback = callback.getName();
            } else {
                noCallbackCalls++;
                lastCallback = "none";
            }
        } catch (Throwable t) {
            poisoned = true;
            poisonReason = String.valueOf(t);
            System.err.println("[UMB-TILE-1165] " + te.getClass().getName() + " at " + pos
                    + " poisoned on tick: " + t);
        }
    }

    /** Bounded, generic live evidence for the 1.16.5 tile lifecycle. */
    String diagnostics() {
        StringBuilder out = new StringBuilder();
        out.append(te.getClass().getName())
                .append(" pos=").append(pos)
                .append(" ticks=").append(tickCalls)
                .append(" interface=").append(interfaceTickCalls)
                .append(" reflective=").append(reflectiveTickCalls)
                .append(" noCallback=").append(noCallbackCalls)
                .append(" binds=").append(worldBindCalls)
                .append(" facadeRemote=").append(lastWorldRemote)
                .append(" callback=").append(lastCallback)
                .append(" state={").append(lifecycleFields()).append('}');
        return out.toString();
    }

    private String lifecycleFields() {
        StringBuilder out = new StringBuilder();
        for (Class<?> c = te.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                String n = f.getName();
                String lower = n.toLowerCase(java.util.Locale.ROOT);
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                        || !(lower.contains("formed") || lower.contains("master")
                        || lower.contains("dummy") || lower.contains("offsettomaster")
                        || lower.equals("world") || lower.equals("pos")
                        || lower.contains("active"))) {
                    continue;
                }
                if (out.length() > 0) out.append(',');
                out.append(n).append('=');
                try {
                    f.setAccessible(true);
                    out.append(shortValue(f.get(te)));
                } catch (Throwable t) {
                    out.append("<unreadable>");
                }
            }
        }
        return out.toString();
    }

    private static String shortValue(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return String.valueOf(value);
        }
        if (value instanceof net.minecraft.world.World) {
            net.minecraft.world.World w = (net.minecraft.world.World) value;
            return value.getClass().getName() + "(isRemote=" + w.field_72995_K + ')';
        }
        String text = String.valueOf(value);
        return text.length() > 120 ? text.substring(0, 120) + "..." : text;
    }

    private Method reflectiveTickMethod() {
        if (tickMethodResolved) return reflectiveTickMethod;
        synchronized (this) {
            if (tickMethodResolved) return reflectiveTickMethod;
            for (String name : new String[] {"func_73660_a", "tick"}) {
                try {
                    Method m = te.getClass().getMethod(name);
                    if (m.getParameterTypes().length == 0) {
                        reflectiveTickMethod = m;
                        tickMethodResolved = true;
                        return m;
                    }
                } catch (Throwable ignored) {
                    // A protected/package-private transformed callback is handled below.
                }
                for (Class<?> c = te.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Method m = c.getDeclaredMethod(name);
                        if (m.getParameterTypes().length == 0) {
                            m.setAccessible(true);
                            reflectiveTickMethod = m;
                            tickMethodResolved = true;
                            return m;
                        }
                    } catch (Throwable ignored) {
                        // Try the next superclass/name; absence is an honest no-tick TE.
                    }
                }
            }
            tickMethodResolved = true;
            return null;
        }
    }

    @Override
    public byte[] saveNbt() {
        try {
            CompoundNBT tag = te.func_189515_b(new CompoundNBT());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CompressedStreamTools.func_74799_a(tag, bos);
            return bos.toByteArray();
        } catch (Throwable t) {
            System.err.println("[UMB-TILE-1165] saveNbt failed for " + te.getClass().getName()
                    + ": " + t);
            return null;
        }
    }

    @Override
    public void loadNbt(byte[] nbt) {
        if (nbt == null) {
            return;
        }
        try {
            CompoundNBT tag = CompressedStreamTools.func_74796_a(new ByteArrayInputStream(nbt));
            te.func_230337_a_(world.func_180495_p(pos), tag);
        } catch (Throwable t) {
            System.err.println("[UMB-TILE-1165] loadNbt failed for " + te.getClass().getName()
                    + ": " + t);
        }
    }

    @Override
    public boolean isValid() {
        return !poisoned && !te.func_145837_r();
    }

    @Override
    public TileFieldSnapshot snapshotFields(FieldPath[] paths) {
        // without fabricating values.
        int n = paths == null ? 0 : paths.length;
        return new TileFieldSnapshot(new String[n], new double[n], new boolean[n]);
    }

    public String poisonReason() {
        return poisonReason;
    }
}
