package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Regression coverage for native custom_data becoming legacy ItemStack NBT. */
class LegacyStackConvNbtTest {

    private static final String TEST_ID = "hbm:test_custom_data_bridge";
    private static Item previousBase;
    private static Item previousVariant;

    @BeforeAll
    static void installScopedRegistrarFixture() {
        TestSupport.ensureBootstrapped();
        Items.NETHERITE_SCRAP.builtInRegistryHolder().bindComponents(
                net.minecraft.core.component.DataComponentMap.EMPTY);
        previousBase = Registrar.LEGACY_ITEMS.put(TEST_ID, Items.NETHERITE_SCRAP);
        previousVariant = Registrar.LEGACY_VARIANT_ITEMS.put(TEST_ID + "@0", Items.NETHERITE_SCRAP);
        Registrar.resetLegacyVariantKeyCacheForTest();
    }

    @AfterAll
    static void removeScopedRegistrarFixture() {
        if (previousBase == null) Registrar.LEGACY_ITEMS.remove(TEST_ID);
        else Registrar.LEGACY_ITEMS.put(TEST_ID, previousBase);
        if (previousVariant == null) Registrar.LEGACY_VARIANT_ITEMS.remove(TEST_ID + "@0");
        else Registrar.LEGACY_VARIANT_ITEMS.put(TEST_ID + "@0", previousVariant);
        // The reverse Item -> legacy key cache is process-static and is shared by every
        // content-conversion test class. Rebuild it from the restored maps before the next class.
        Registrar.resetLegacyVariantKeyCacheForTest();
    }

    @Test
    void ordinaryNativeCustomDataIsCompressedIntoLegacyNbt() throws Exception {
        ItemStack nativeStack = new ItemStack(Items.NETHERITE_SCRAP, 1);
        CustomData.update(DataComponents.CUSTOM_DATA, nativeStack,
                tag -> tag.putInt("energy", 25));

        StackData legacy = LegacyStackConv.toLegacy(nativeStack);

        assertFalse(legacy.isEmpty());
        assertNotNull(legacy.nbt, "native custom_data must not be dropped at the bridge");
        CompoundTag decoded = NbtIo.readCompressed(new ByteArrayInputStream(legacy.nbt),
                NbtAccounter.unlimitedHeap());
        assertEquals(25, decoded.getIntOr("energy", -1));
    }
}
