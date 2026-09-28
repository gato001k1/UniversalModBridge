package dev.umb.legacy1165.legacyside;

import java.util.OptionalInt;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import dev.umb.bridge.api.HostPlayer;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/**
 * The 1.16.5 probe player: a REAL {@code PlayerEntity} subclass with a fake identity, same role
 * as 1.7.10's {@code UmbPlayer}. Only two methods are abstract in SRG production
 * ({@code func_175149_v}, {@code func_184812_l_} - verified) and both report false (a
 * headless, non-spectator, non-creative player).
 *
 * <p>The one behavioral override is {@code func_213829_a} (openMenu): the base implementation
 * is a no-op returning empty (proven by disassembly - only ServerPlayerEntity wires it to
 * networking), so this override performs the LOCAL half exactly like the server player does
 * minus packets: build the container through the provider, track it as {@code openContainer}
 * ({@code field_71070_bA}, number-stable with 1.7.10), return its window id. The mod's own
 * {@code use()} path therefore opens a REAL container headlessly.</p>
 */
public class UmbPlayer1165 extends PlayerEntity {

    private int windowIds = 1;

    public UmbPlayer1165(UmbWorld1165 world, BlockPos pos, String name) {
        super(world, pos, 0.0F, new GameProfile(new UUID(0L, 0L), name));
    }

    @Override
    public boolean func_175149_v() {
        return false;
    }

    @Override
    public boolean func_184812_l_() {
        return false;
    }

    @Override
    public java.util.OptionalInt func_213829_a(INamedContainerProvider provider) {
        Container container = provider.createMenu(windowIds, this.field_71071_by, this);
        if (container == null) {
            return OptionalInt.empty();
        }
        this.field_71070_bA = container;
        return OptionalInt.of(windowIds++);
    }

    /** The container opened by the last activate (null if none). */
    public Container openedContainer() {
        return this.field_71070_bA;
    }

    /** Copies the triggering host player into the real 1.16.5 facade before an item callback. */
    void syncFrom(HostPlayer host) {
        if (host == null) return;
        this.func_70107_b(host.getX(), host.getY(), host.getZ());
        this.field_70177_z = host.getYaw();
        this.field_70125_A = host.getPitch();
        this.func_184611_a(Hand.MAIN_HAND, UmbItemConv1165.toLegacy(host.getHeldItem()));
    }
}
