package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live bug (HBM Uzi playtest): the Uzi stayed invisible in first-person even after its
 * {@code .obj} model existed on disk. {@code stdout.log} showed the real cause -
 * {@code Minecraft.func_71410_x().thePlayer...} directly - the STATIC singleton's player, not the
 * "holder" argument {@code LegacyRenderCapture.captureItem} actually passes it.
 *
 * {@code LegacyClientFacade.install()} only seeded the player's inventory/capabilities AFTER
 * already publishing the facade to the static singleton (the same class of race the file's own
 * two fields before the publish.</p>
 *
 * {@code field_71439_g}/{@code field_71451_h} (thePlayer itself) were STILL only set ~15 lines
 * after the publish, so round 1 only fixed what hung off the player, not the player reference
 * itself. Fixed the same way: seed {@code field_71439_g}/{@code field_71451_h} before the
 * publish too. On top of that, {@code captureItem} now also re-pins the singleton to its own
 * already-built facade immediately before invoking legacy rendering code, and restores whatever
 * was there immediately after ({@link LegacyClientFacade#pinSingleton}/
 * {@link LegacyClientFacade#restoreSingleton}) - closing the SEPARATE, smaller window between
 * {@code install()} returning and the actual render call, where an unrelated concurrent
 * {@code install()} on another thread could still swap the singleton out from under it.</p>
 *
 * <p>These tests exercise the new helpers directly (deterministic - the exact units of logic the
 * fixes add, exercised without needing to win an actual race against {@link LegacyClientFacade#install}),
 * plus end-to-end sanity checks that {@code install()}'s published facade is always fully wired.</p>
 */
final class LegacyClientFacadePlayerStateTest {

    private static EntityClientPlayerMP freshFacadePlayer() {
        return UmbUnsafe.allocate(EntityClientPlayerMP.class);
    }

    private static Object read(Object target, String fieldName) throws Exception {
        Field f = EntityPlayer.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    void aFreshlyAllocatedPlayerStartsWithBothFieldsNull() throws Exception {
        // Sanity check on the premise: Unsafe.allocateInstance skips EntityPlayer's constructor,
        // so nothing has set these yet - this is exactly the state a concurrent reader could see
        // without the fix.
        EntityClientPlayerMP player = freshFacadePlayer();
        assertNull(read(player, "field_71071_by"), "a bare Unsafe-allocated player has no inventory yet");
        assertNull(read(player, "field_71075_bZ"), "a bare Unsafe-allocated player has no capabilities yet");
    }

    @Test
    void seedPlaceholderPlayerStateGivesANonNullInventoryAndCapabilities() throws Exception {
        EntityClientPlayerMP player = freshFacadePlayer();

        LegacyClientFacade.seedPlaceholderPlayerState(player);

        Object inventory = read(player, "field_71071_by");
        Object capabilities = read(player, "field_71075_bZ");
        assertNotNull(inventory, "field_71071_by must never be observably null after seeding");
        assertNotNull(capabilities, "field_71075_bZ must never be observably null after seeding");
        assertTrue(inventory instanceof InventoryPlayer, "must be a real InventoryPlayer, not a dummy Object");
        assertTrue(capabilities instanceof PlayerCapabilities, "must be a real PlayerCapabilities, not a dummy Object");
    }

    @Test
    void seedPlaceholderPlayerStateDoesNotOverwriteAnAlreadySeededInventory() throws Exception {
        // The real install() flow re-seeds with server-mirrored state further down; this proves
        // the placeholder seed is a fill-the-gap-only guard (null check), not a destructive
        // unconditional overwrite that could stomp genuine data seeded earlier in some future
        // reordering of install().
        EntityClientPlayerMP player = freshFacadePlayer();
        InventoryPlayer sentinel = new InventoryPlayer(player);
        Field f = EntityPlayer.class.getDeclaredField("field_71071_by");
        f.setAccessible(true);
        f.set(player, sentinel);

        LegacyClientFacade.seedPlaceholderPlayerState(player);

        assertSame(sentinel, read(player, "field_71071_by"),
                "an already-seeded inventory must not be replaced");
    }

    @Test
    void seedPlaceholderPlayerStateToleratesANullPlayer() {
        // install() itself never passes null here, but a defensive public helper should not NPE
        // if some future caller does.
        LegacyClientFacade.seedPlaceholderPlayerState(null);
    }

    @Test
    void installPublishesAFacadeWhosePlayerAlwaysHasAnInventoryAndCapabilities() throws Exception {
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);

        assertNotNull(read(binding.player, "field_71071_by"),
                "the published facade's player must never be observed with a null inventory");
        assertNotNull(read(binding.player, "field_71075_bZ"),
                "the published facade's player must never be observed with null capabilities");
    }

    @Test
    void installPublishesAFacadeWhoseMinecraftAlreadyHasAPlayer() throws Exception {
        // Round 2's own regression: field_71439_g (thePlayer) used to be set AFTER the publish,
        // so a facade could be observed published with a null player - not just a player with a
        // null inventory (round 1's own bug). This checks the field ItemRenderWeaponBase actually
        // reads: Minecraft.func_71410_x().thePlayer.
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);

        Field playerField = Minecraft.class.getDeclaredField("field_71439_g");
        playerField.setAccessible(true);
        assertSame(binding.player, playerField.get(binding.minecraft),
                "the published facade's Minecraft.thePlayer must already be this call's own player");
    }

    @Test
    void pinSingletonPublishesAndReturnsThePreviousValue() throws Exception {
        Minecraft before = UmbUnsafe.allocate(Minecraft.class);
        Minecraft after = UmbUnsafe.allocate(Minecraft.class);
        LegacyClientFacade.pinSingleton(before);
        assertSame(before, Minecraft.func_71410_x(), "test setup: 'before' must be the current singleton");

        Minecraft previous = LegacyClientFacade.pinSingleton(after);

        assertSame(before, previous, "pinSingleton must return whatever was published before it");
        assertSame(after, Minecraft.func_71410_x(), "pinSingleton must publish the new value");

        LegacyClientFacade.restoreSingleton(after, previous);

        assertSame(before, Minecraft.func_71410_x(), "restoreSingleton must put the previous value back");
    }

    @Test
    void restoreSingletonDoesNotOverwriteIfFacadeChanged() throws Exception {
        Minecraft before = UmbUnsafe.allocate(Minecraft.class);
        Minecraft pinned = UmbUnsafe.allocate(Minecraft.class);
        Minecraft changed = UmbUnsafe.allocate(Minecraft.class);
        
        LegacyClientFacade.pinSingleton(before);
        Minecraft previous = LegacyClientFacade.pinSingleton(pinned);
        
        // Simulate concurrent install
        LegacyClientFacade.pinSingleton(changed);
        
        LegacyClientFacade.restoreSingleton(pinned, previous);
        
        assertSame(changed, Minecraft.func_71410_x(), "restoreSingleton must not clobber a newer facade");
    }

    @Test
    void pinSingletonTakesANullMinecraftAsANoOp() throws Exception {
        Minecraft before = UmbUnsafe.allocate(Minecraft.class);
        LegacyClientFacade.pinSingleton(before);

        Minecraft returned = LegacyClientFacade.pinSingleton(null);

        assertNull(returned, "a null facade has no 'previous' to report");
        assertSame(before, Minecraft.func_71410_x(),
                "pinning null must not disturb whatever was already published");
    }
}
