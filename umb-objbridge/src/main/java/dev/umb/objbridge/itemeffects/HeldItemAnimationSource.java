package dev.umb.objbridge.itemeffects;

/** Host input for data-driven held-item animation. */
public interface HeldItemAnimationSource {
    /** Current track name, or {@code null} when no track is active. */
    String currentTrack(String itemId);

    /** Host tick at which the current track began. */
    long startTick(String itemId);

    HeldItemAnimationSource NONE = new HeldItemAnimationSource() {
        public String currentTrack(String itemId) { return null; }
        public long startTick(String itemId) { return 0L; }
    };
}
