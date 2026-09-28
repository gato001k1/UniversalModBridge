package dev.umb.bridge.api;

/**
 * The outcome of one legacy onBlockActivated (func_149727_a) call. 1.7.10 returns a boolean AND
 * may have opened a GUI (a legacy {@code Container}) in the same call; both facts have to cross
 * the boundary or the host cannot tell "handled, no GUI" (e.g. a lever toggling) apart from
 * "declined" (both previously looked identical: a null {@link ContainerHandle}).
 */
public final class ActivationResult {
    public final boolean handled;
    /** Non-null only when the legacy call opened a GUI. */
    public final ContainerHandle container;

    public ActivationResult(boolean handled, ContainerHandle container) {
        this.handled = handled;
        this.container = container;
    }

    /** The legacy call declined (returned false), or could not run at all. */
    public static final ActivationResult DECLINED = new ActivationResult(false, null);
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
