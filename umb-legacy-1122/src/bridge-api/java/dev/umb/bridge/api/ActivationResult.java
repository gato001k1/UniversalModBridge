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
