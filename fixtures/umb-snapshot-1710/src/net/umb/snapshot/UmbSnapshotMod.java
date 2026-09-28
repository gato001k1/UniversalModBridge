// UMB Snapshot — 1.7.10 Forge mod entrypoint.
// SPDX-License-Identifier: CC0-1.0
package net.umb.snapshot;

import java.io.File;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;

import net.minecraftforge.common.MinecraftForge;

/**
 * Dynamic-extraction probe for UniversalModBridge lane A.
 *
 * Waits for the first client tick on which {@code Minecraft.currentScreen}
 * (SRG {@code field_71462_r}) is a {@link GuiMainMenu}: at that point every mod
 * has completed all three lifecycle phases, the resource manager has reloaded,
 * and the block/item texture atlases are stitched — so every {@code IIcon}
 * resolves to its final atlas sprite name. It then writes one JSON snapshot of
 * the registries and (optionally) exits the JVM.
 *
 * System properties:
 *   umbsnap.out    absolute path of the snapshot JSON
 *                  (default: &lt;cwd&gt;/umb-snapshot.json)
 *   umbsnap.exit   "true" (default) to halt the JVM once the file is closed
 *   umbsnap.delay  extra client ticks to wait on the menu before dumping
 *                  (default 20 = ~1 s at 20 tps)
 */
@Mod(modid = UmbSnapshotMod.MODID, name = "UMB Snapshot", version = UmbSnapshotMod.VERSION)
public class UmbSnapshotMod {

    public static final String MODID = "umbsnap";
    public static final String VERSION = "0.1.0";

    private static final String TAG = "[umbsnap] ";

    private boolean done;
    private int menuTicks;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
        System.out.println(TAG + "registered; out=" + outFile().getAbsolutePath()
                + " exit=" + exitWhenDone() + " delay=" + delayTicks()
                + " icons=" + (iconRoot() == null ? "<off>" : iconRoot().getAbsolutePath()));
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (done) {
            return;
        }
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.func_71410_x();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }
        Object screen = Refl.getOrNull(Minecraft.class, mc, "field_71462_r", "currentScreen");
        if (!(screen instanceof GuiMainMenu)) {
            menuTicks = 0;
            return;
        }
        // Settle for a moment so any deferred post-load work finishes first.
        if (++menuTicks < delayTicks()) {
            return;
        }
        done = true;

        File out = outFile();
        long t0 = System.currentTimeMillis();
        boolean ok = false;
        try {
            System.out.println(TAG + "main menu reached, dumping snapshot to " + out.getAbsolutePath());
            Snapshot.dump(out);
            ok = true;
            System.out.println(TAG + "snapshot written: " + out.getAbsolutePath()
                    + " (" + out.length() + " bytes, " + (System.currentTimeMillis() - t0) + " ms)");
        } catch (Throwable t) {
            System.out.println(TAG + "SNAPSHOT FAILED: " + Refl.describe(t));
            t.printStackTrace(System.out);
        }

        // Lane A3: capture the STITCHED sprite pixels of every icon the snapshot just
        // referenced. Same tick, same (render) thread, so the GL context is current and the
        // atlas is exactly the one the snapshot's icon names came from.
        File iconRoot = iconRoot();
        if (iconRoot != null) {
            long t1 = System.currentTimeMillis();
            try {
                String summary = IconDump.run(iconRoot, Snapshot.REFERENCED_ICONS);
                System.out.println(TAG + "icon dump: " + summary
                        + " root=" + iconRoot.getAbsolutePath()
                        + " wallMs=" + (System.currentTimeMillis() - t1));
            } catch (Throwable t) {
                System.out.println(TAG + "ICON DUMP FAILED: " + Refl.describe(t));
                t.printStackTrace(System.out);
            }
        }

        System.out.flush();
        System.err.flush();

        if (exitWhenDone()) {
            System.out.println(TAG + "exiting JVM (ok=" + ok + ")");
            System.out.flush();
            try {
                // hardExit: the snapshot writer has already flushed and closed the
                // file and LaunchClassLoader's DEBUG_SAVE writes each class as it is
                // transformed, so nothing is buffered. A hard halt avoids the LWJGL
                // / sound-system shutdown hangs that System.exit() can hit here.
                FMLCommonHandler.instance().exitJava(0, true);
            } catch (Throwable t) {
                Runtime.getRuntime().halt(0);
            }
        }
    }

    private static File outFile() {
        String p = System.getProperty("umbsnap.out");
        if (p == null || p.length() == 0) {
            return new File("umb-snapshot.json").getAbsoluteFile();
        }
        return new File(p).getAbsoluteFile();
    }

    /** Output root for the runtime atlas dump, or null when the dump is off. */
    private static File iconRoot() {
        String p = System.getProperty("umbsnap.icons");
        if (p == null || p.length() == 0) {
            return null;
        }
        return new File(p).getAbsoluteFile();
    }

    private static boolean exitWhenDone() {
        return !"false".equalsIgnoreCase(System.getProperty("umbsnap.exit", "true"));
    }

    private static int delayTicks() {
        try {
            return Integer.parseInt(System.getProperty("umbsnap.delay", "20"));
        } catch (Throwable t) {
            return 20;
        }
    }
}
