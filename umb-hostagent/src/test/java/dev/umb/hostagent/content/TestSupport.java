package dev.umb.hostagent.content;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test-only helpers. 26.2 menu/inventory plumbing is deeply tied to a live MinecraftServer /
 * ServerLevel (real construction needs a running world), which R8 explicitly rules out for this
 * lane's gates ("do NOT launch the 26.2 client"). Where a test only needs a VALID OBJECT
 * REFERENCE of the right runtime type -- never touched beyond having its identity stored, e.g.
 * AbstractContainerMenu's ctor just assigns {@code this.menuType = type} without calling
 * anything on it, and a {@code Slot}'s ctor just stores its {@code Container} reference -- this
 * allocates one via {@code Unsafe.allocateInstance}, the SAME idiom the legacy facades already
 * use (see umb-legacy) to stand in for a class whose real constructor path needs infrastructure
 * this headless lane does not have. Every call site that receives one of these documents exactly
 * why the real constructor is never invoked and never will be.
 */
final class TestSupport {

    private static final Unsafe UNSAFE = loadUnsafe();

    private TestSupport() {
    }

    private static Unsafe loadUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> type) {
        try {
            return (T) UNSAFE.allocateInstance(type);
        } catch (InstantiationException e) {
            throw new AssertionError(e);
        }
    }

    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);
    private static final AtomicBoolean UNFROZEN_BOOTSTRAPPED = new AtomicBoolean(false);

    /**
     * 26.2's own registries assert {@code Bootstrap.checkBootstrapCalled()} inside their static
     * initialisers (discovered empirically: even touching {@code Items.STICK} throws
     * {@code IllegalArgumentException: Not bootstrapped} otherwise), so this is required before
     * ANY live game class is touched. This runs the REAL
     * {@code net.minecraft.server.Bootstrap.bootStrap()} -- i.e. registries end up fully
     * populated AND FROZEN, exactly like a real client/server boots. That freeze is required for
     * {@code new ItemStack(...)} to work at all: it reads a Holder$Reference's bound default
     * components, which are only bound as part of the freeze pass (verified empirically:
     * {@code NullPointerException: Components not bound yet} at
     * {@code Holder$Reference.components} otherwise).
     *
     * Tests that need to construct a FRESH {@code Block} or {@code BlockEntityType} (both
     * unconditionally call {@code registry.createIntrusiveHolder(this)} in their constructors,
     * verified via javap -c) must NOT call this -- once frozen, createIntrusiveHolder throws
     * {@code IllegalStateException: This registry can't create intrusive holders} forever after
     * in this JVM. See {@link #ensureBootstrappedWithoutFreezing()} for that case; the two are
     * mutually exclusive within one process, which is why UmbLegacyBlockTest (the only place that
     * constructs fresh UmbLegacyBlock instances) runs in its own separate java invocation --
     * see tools/run-hostagent-tests.ps1.
     */
    static void ensureBootstrapped() {
        if (BOOTSTRAPPED.compareAndSet(false, true)) {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        }
    }

    /**
     * Alternative to {@link #ensureBootstrapped()} for tests that construct fresh {@code Block}/
     * {@code BlockEntityType} instances (see that method's javadoc for why the two cannot be
     * mixed in one process). Flips {@code Bootstrap.isBootstrapped} (private static volatile
     * boolean) directly via reflection instead of calling {@code Bootstrap.bootStrap()}. This
     * satisfies every {@code checkBootstrapCalled()} guard so {@code BuiltInRegistries} /
     * {@code Blocks} / {@code Items} initialise normally, WITHOUT ever calling
     * {@code BuiltInRegistries.freeze()} -- so BLOCK/BLOCK_ENTITY_TYPE/ITEM/MENU stay writable for
     * the whole process and custom test instances can be constructed freely and repeatedly.
     * Trade-off: {@code new ItemStack(...)} does NOT work in this mode (components never bound).
     */
    static void ensureBootstrappedWithoutFreezing() {
        if (UNFROZEN_BOOTSTRAPPED.compareAndSet(false, true)) {
            try {
                net.minecraft.SharedConstants.tryDetectVersion();
                Field flag = Class.forName("net.minecraft.server.Bootstrap").getDeclaredField("isBootstrapped");
                flag.setAccessible(true);
                flag.setBoolean(null, true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
