package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.util.Map;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.network.IGuiHandler;
import cpw.mods.fml.common.network.NetworkRegistry;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.world.World;

/**
 * Replacement body for the STATIC {@code cpw.mods.fml.common.network.internal.FMLNetworkHandler.openGui(EntityPlayer, Object, int, World, int, int, int)} .
 * {@link UmbShimTransformer} rewrites the call site to this method - same signature, so every existing...
 */
public final class UmbGui {

    private static volatile Field serverGuiHandlersField;
    private static volatile GuiContext lastContext;
    private static volatile OpenedGui pendingOpenedGui;

    /** The one active GUI context in the single-player bridge; host menu packets are player-scoped. */
    static final class GuiContext {
        final IGuiHandler handler;
        final int id, x, y, z;
        final EntityPlayer player;
        final World world;

        GuiContext(IGuiHandler handler, int id, EntityPlayer player, World world, int x, int y, int z) {
            this.handler = handler;
            this.id = id;
            this.player = player;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A packet/block-independent handoff from legacy {@code openGui} to the host menu bridge. */
    static final class OpenedGui {
        final ContainerHandle handle;
        final UmbPlayer player;
        final String title;
        final int x, y, z;

        OpenedGui(ContainerHandle handle, UmbPlayer player, String title, int x, int y, int z) {
            this.handle = handle;
            this.player = player;
            this.title = title;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private UmbGui() {
    }

    /** Same signature as {@code FMLNetworkHandler.openGui} - the transformer rewrites the call site to this. */
    @SuppressWarnings("unchecked")
    public static void openGui(EntityPlayer player, Object mod, int id, World world, int x, int y, int z) {
        try {
            if (LegacyInputDiag.oncePer("gui-open-enter:" + id, 5_000_000_000L)) {
                LegacyInputDiag.log("gui openGui entered id=" + id + " player="
                        + (player == null ? "null" : player.getClass().getSimpleName()));
            }
            ModContainer mc = FMLCommonHandler.instance().findContainerFor(mod);
            if (mc == null) {
                if (LegacyInputDiag.oncePer("gui-open-no-mod:" + id, 5_000_000_000L)) {
                    LegacyInputDiag.log("gui openGui no mod container id=" + id);
                }
                return;
            }
            Map<ModContainer, IGuiHandler> handlers =
                    (Map<ModContainer, IGuiHandler>) serverGuiHandlersField().get(NetworkRegistry.INSTANCE);
            IGuiHandler handler = handlers.get(mc);
            if (handler == null) {
                if (LegacyInputDiag.oncePer("gui-open-no-handler:" + id, 5_000_000_000L)) {
                    LegacyInputDiag.log("gui openGui no server handler id=" + id);
                }
                return;
            }
            lastContext = new GuiContext(handler, id, player, world, x, y, z);
            Object element = handler.getServerGuiElement(id, player, world, x, y, z);
            if (element instanceof Container && player != null) {
                Container container = (Container) element;
                player.field_71070_bA = container;
                // real FML also does this before returning control - without it Container's
                // "crafters" list stays empty and func_75142_b (detectAndSendChanges) never calls
                // ICrafting.func_71112_a, so progress-bar sync data never reaches the player facade.
                if (player instanceof ICrafting) {
                    container.func_75132_a((ICrafting) player);
                }
                // Block activation has its own already-established host menu path.  The pending
                // handoff is for packet/key-opened containers, so only publish it for the facade
                // player and let LegacyBridgeImpl.tickEvents consume it after client dispatch.
                if (player instanceof UmbPlayer) {
                    UmbPlayer umbPlayer = (UmbPlayer) player;
                    pendingOpenedGui = new OpenedGui(
                            new ContainerHandleImpl(container, umbPlayer, "Legacy GUI"),
                            umbPlayer, "Legacy GUI", x, y, z);
                    if (LegacyInputDiag.oncePer("gui-open-prepared:" + id + ":"
                            + container.getClass().getName(), 5_000_000_000L)) {
                        LegacyInputDiag.log("gui openGui prepared id=" + id + " container="
                                + container.getClass().getName());
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[UMB-GUI] openGui failed for mod=" + mod + " id=" + id + ": " + t);
        }
    }

    static GuiContext lastContext() {
        return lastContext;
    }

    static OpenedGui consumeOpenedGui(UmbPlayer expectedPlayer) {
        OpenedGui opened = pendingOpenedGui;
        if (opened == null || (expectedPlayer != null && opened.player != expectedPlayer)) {
            return null;
        }
        pendingOpenedGui = null;
        return opened;
    }

    private static Field serverGuiHandlersField() throws NoSuchFieldException {
        Field f = serverGuiHandlersField;
        if (f == null) {
            f = NetworkRegistry.class.getDeclaredField("serverGuiHandlers");
            f.setAccessible(true);
            serverGuiHandlersField = f;
        }
        return f;
    }
}
