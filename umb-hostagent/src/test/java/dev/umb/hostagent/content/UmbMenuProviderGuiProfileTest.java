package dev.umb.hostagent.content;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end (still headless) Task B gate: {@link UmbMenuProvider#createMenu} really does
 * recover the legacy Container's class via {@link LegacyContainerClassResolver} (reflection into
 * a private {@code container} field, matching {@code ContainerHandleImpl}'s real shape), look it
 * up in {@link Registrar#GUI_PROFILE}, and thread the real size/texture all the way into the
 * built {@link UmbLegacyMenu} - including the ySize-dependent player-inventory Y offset fix that
 * came with it (a taller panel must not stack the player's own inventory grid over the machine
 * slots).
 */
class UmbMenuProviderGuiProfileTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    @AfterEach
    void resetGuiProfile() {
        Registrar.GUI_PROFILE = GuiProfile.empty();
    }

    /** Stands in for a real 1.7.10 legacy Container class - only its class NAME matters here. */
    private static final class FakeRealLegacyContainer {
    }

    /** Same private-field shape as the real {@code ContainerHandleImpl} (see
     *  {@link LegacyContainerClassResolver}'s javadoc), otherwise a minimal {@link ContainerHandle}. */
    private static final class ReflectiveContainerHandle implements ContainerHandle {
        @SuppressWarnings("unused")
        private final Object container;
        private final int[] xs, ys;

        ReflectiveContainerHandle(Object container, int[] xs, int[] ys) {
            this.container = container;
            this.xs = xs;
            this.ys = ys;
        }

        @Override public String title() { return "hbm:tile.test"; }
        @Override public int slotCount() { return xs.length; }

        @Override
        public SlotData[] slots() {
            SlotData[] out = new SlotData[xs.length];
            for (int i = 0; i < xs.length; i++) out[i] = new SlotData(i, xs[i], ys[i], StackData.EMPTY);
            return out;
        }

        @Override public void setSlot(int index, StackData s) { }
        @Override public StackData takeSlot(int index, int amount) { return StackData.EMPTY; }
        @Override public int[] syncData() { return new int[0]; }
        @Override public void close() { }
    }

    @Test
    void createMenuResolvesTheRealSizeAndTextureThroughTheReflectedContainerClass() {
        String containerClassName = FakeRealLegacyContainer.class.getName();
        String json = "{\"guis\":[{\"className\":\"G\",\"container\":{\"className\":\""
                + containerClassName.replace("\\", "\\\\") + "\",\"confidence\":\"exact\"},"
                + "\"size\":{\"xSize\":210,\"ySize\":230,\"confidence\":\"exact\"},"
                + "\"backgroundTextures\":[{\"path\":\"hbm:textures/gui/foo.png\",\"existsInJar\":true,"
                + "\"assetPath\":\"assets/hbm/textures/gui/foo.png\",\"sheetWidth\":256,\"sheetHeight\":256}]}]}";
        Registrar.GUI_PROFILE = GuiProfile.parseString(json);

        ContainerHandle handle = new ReflectiveContainerHandle(new FakeRealLegacyContainer(),
                new int[]{10}, new int[]{20});
        UmbMenuProvider provider = new UmbMenuProvider(handle, Component.literal("t"));
        Inventory inv = TestSupport.allocate(Inventory.class);

        AbstractContainerMenu built = provider.createMenu(4321, inv, null);
        UmbLegacyMenu menu = (UmbLegacyMenu) built;

        assertEquals(210, menu.xSize);
        assertEquals(230, menu.ySize);
        assertNotNull(menu.textureLocation);
        assertEquals("hbm", menu.textureLocation.getNamespace());
        assertEquals("textures/gui/foo.png", menu.textureLocation.getPath());
        assertEquals(256, menu.sheetWidth);
        assertEquals(256, menu.sheetHeight);

        // ySize=230 -> player inventory grid starts at 230-82=148, NOT the pre-Task-B constant 84 -
        // otherwise it would overlap this GUI's own (real, taller) machine-slot area.
        Slot firstMain = menu.slots.get(menu.machineSlotCount);
        assertEquals(148, firstMain.y);
    }

    @Test
    void createMenuFallsBackWhenTheContainerClassHasNoGuiProfilePairing() {
        Registrar.GUI_PROFILE = GuiProfile.parseString("{\"guis\":[]}");
        ContainerHandle handle = new ReflectiveContainerHandle(new FakeRealLegacyContainer(),
                new int[]{10}, new int[]{20});
        UmbMenuProvider provider = new UmbMenuProvider(handle, Component.literal("t"));
        Inventory inv = TestSupport.allocate(Inventory.class);

        UmbLegacyMenu menu = (UmbLegacyMenu) provider.createMenu(4322, inv, null);
        assertEquals(GuiProfile.FALLBACK_X, menu.xSize);
        assertEquals(GuiProfile.FALLBACK_Y, menu.ySize);
        assertNull(menu.textureLocation);
        Slot firstMain = menu.slots.get(menu.machineSlotCount);
        assertEquals(84, firstMain.y, "unchanged pre-Task-B default for a 166-tall panel");
    }
}
