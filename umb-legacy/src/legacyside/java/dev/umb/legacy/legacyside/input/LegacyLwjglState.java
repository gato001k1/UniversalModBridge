package dev.umb.legacy.legacyside.input;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-contextual backing store for the in-universe LWJGL2 input shims.
 *
 * <p>The legacy universe has no display, so the real {@code org.lwjgl.input.Keyboard} and
 * {@code org.lwjgl.input.Mouse} can never report a key: every query is rewritten by
 * {@code UmbShimTransformer} to one of the static methods below. State is keyed by player
 * name and is visible only while that player's context is open ({@link #begin}/{@link #end}),
 * because the mirror applies input per player before the legacy tick. With no context open
 * every query returns its resting value (false / 0), which matches "no keys down".</p>
 *
 * <p>Codes are raw LWJGL2 codes, exactly as the input plans carry them: keyboard codes are
 * non-negative, mouse buttons are {@code button - 100} (negative). Each side keeps its own
 * edge-event queue so {@code next()}/{@code getEventKey()} style polling sees the same
 * press/release edges the mirror applied.</p>
 *
 * <p>Universal: no mod names, no mod classes. Pure Java 8, no legacy classes referenced, so
 * headless unit tests can drive it directly.</p>
 */
public final class LegacyLwjglState {
    private static final int QUEUE_CAP = 64;

    private static final class KeyEvent {
        final int code;
        final boolean down;
        KeyEvent(int code, boolean down) { this.code = code; this.down = down; }
    }

    private static final class PlayerState {
        final Map<Integer, Boolean> down = new HashMap<Integer, Boolean>();
        final Deque<KeyEvent> keyboardQueue = new ArrayDeque<KeyEvent>();
        final Deque<KeyEvent> mouseQueue = new ArrayDeque<KeyEvent>();
        int lastKey;
        boolean lastKeyState;
        boolean hasKey;
        int lastButton;
        boolean lastButtonState;
        boolean hasButton;
        // Host view rotation -> legacy MouseHelper deltas (see noteHostLook).
        boolean hasLook;
        float lastYaw;
        float lastPitch;
        float pendingYaw;
        float pendingPitch;
        boolean focused;
    }

    private static final Map<String, PlayerState> STATES =
            new ConcurrentHashMap<String, PlayerState>();
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<String>();
    private static volatile boolean repeatEvents;

    private LegacyLwjglState() { }

    /** Opens the input context for {@code player}; queries see that player's state until {@link #end}. */
    public static void begin(String player) {
        if (player != null) {
            CURRENT.set(player);
        }
    }

    /** Closes the current input context. */
    public static void end() {
        CURRENT.remove();
    }

    private static PlayerState stateOf(String player) {
        PlayerState s = STATES.get(player);
        if (s == null) {
            PlayerState created = new PlayerState();
            PlayerState raced = STATES.putIfAbsent(player, created);
            s = raced == null ? created : raced;
        }
        return s;
    }

    private static PlayerState current() {
        String player = CURRENT.get();
        return player == null ? null : stateOf(player);
    }

    /**
     * Applies one level sample. A change of level enqueues an edge event on the
     * keyboard queue (code &gt;= 0) or the mouse queue (code &lt; 0, button {@code code + 100}).
     */
    public static void setDown(String player, int lwjglCode, boolean down) {
        if (player == null) {
            return;
        }
        PlayerState s = stateOf(player);
        synchronized (s) {
            Boolean old = s.down.get(Integer.valueOf(lwjglCode));
            if (old != null && old.booleanValue() == down) {
                return;
            }
            s.down.put(Integer.valueOf(lwjglCode), Boolean.valueOf(down));
            Deque<KeyEvent> queue = lwjglCode < 0 ? s.mouseQueue : s.keyboardQueue;
            queue.addLast(new KeyEvent(lwjglCode, down));
            while (queue.size() > QUEUE_CAP) {
                queue.removeFirst();
            }
            String edgeKey = "legacy-input-edge:" + player + ":" + lwjglCode + ":" + down;
            if (LegacyInputDiag.oncePer(edgeKey, 100_000_000L)) {
                LegacyInputDiag.log("legacy input edge player=" + player
                        + " code=" + lwjglCode + " down=" + down);
            }
        }
    }

    /** Currently-down LWJGL2 codes for {@code player} (copy, never null). */
    public static Set<Integer> downCodes(String player) {
        if (player == null) {
            return new HashSet<Integer>();
        }
        PlayerState s = stateOf(player);
        synchronized (s) {
            return new HashSet<Integer>(s.down.keySet());
        }
    }

    /** True when {@code player} currently holds {@code lwjglCode} (keyboard side only). */
    public static boolean levelDown(String player, int lwjglCode) {
        if (player == null) {
            return false;
        }
        PlayerState s = stateOf(player);
        synchronized (s) {
            Boolean down = s.down.get(Integer.valueOf(lwjglCode));
            return down != null && down.booleanValue();
        }
    }

    /** Drops all state for {@code player} (disconnect hygiene; entries are otherwise tiny). */
    public static void clearPlayer(String player) {
        if (player != null) {
            STATES.remove(player);
        }
    }

    // ------------------------------------------------------------ keyboard shim

    /** Shim for {@code Keyboard.isKeyDown(int)}. Mouse codes (negative) never match here. */
    public static boolean isKeyDown(int code) {
        if (code < 0) {
            return false;
        }
        PlayerState s = current();
        if (s == null) {
            return false;
        }
        synchronized (s) {
            Boolean down = s.down.get(Integer.valueOf(code));
            boolean result = down != null && down.booleanValue();
            if (result) {
                String queryKey = "legacy-key-query:" + CURRENT.get() + ":" + code;
                if (LegacyInputDiag.oncePer(queryKey, 100_000_000L)) {
                    LegacyInputDiag.log("legacy keyboard query player=" + CURRENT.get()
                            + " code=" + code + " down=true");
                }
            }
            return result;
        }
    }

    /** Shim for {@code Keyboard.next()}: advances the keyboard edge queue. */
    public static boolean next() {
        PlayerState s = current();
        if (s == null) {
            return false;
        }
        synchronized (s) {
            KeyEvent e = s.keyboardQueue.pollFirst();
            if (e == null) {
                return false;
            }
            s.lastKey = e.code;
            s.lastKeyState = e.down;
            s.hasKey = true;
            return true;
        }
    }

    /** Shim for {@code Keyboard.getEventKey()}. */
    public static int getEventKey() {
        PlayerState s = current();
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            return s.hasKey ? s.lastKey : 0;
        }
    }

    /** Shim for {@code Keyboard.getEventKeyState()}. */
    public static boolean getEventKeyState() {
        PlayerState s = current();
        if (s == null) {
            return false;
        }
        synchronized (s) {
            return s.hasKey && s.lastKeyState;
        }
    }

    /** Shim for {@code Keyboard.isRepeatEvent()}: the mirror never synthesizes repeats. */
    public static boolean isRepeatEvent() {
        return false;
    }

    /** Shim for {@code Keyboard.enableRepeatEvents(boolean)}. */
    public static void setRepeatEvents(boolean enabled) {
        repeatEvents = enabled;
    }

    /**
     * Records the host player's view rotation and window focus for one input sample. The host
     * already turned its player by the physical mouse movement, so the rotation delta since the
     * previous sample is exactly the mouse motion a legacy MouseHelper would have reported.
     */
    public static void noteHostLook(String player, float yaw, float pitch, boolean focused) {
        if (player == null) {
            return;
        }
        PlayerState s = stateOf(player);
        synchronized (s) {
            s.focused = focused;
            if (s.hasLook && focused) {
                float dy = yaw - s.lastYaw;
                while (dy > 180f) dy -= 360f;
                while (dy < -180f) dy += 360f;
                float dp = pitch - s.lastPitch;
                // A teleport or server-side snap is not mouse motion: bound each sample.
                if (Math.abs(dy) <= 30f && Math.abs(dp) <= 30f) {
                    s.pendingYaw = Math.max(-90f, Math.min(90f, s.pendingYaw + dy));
                    s.pendingPitch = Math.max(-90f, Math.min(90f, s.pendingPitch + dp));
                }
            }
            s.lastYaw = yaw;
            s.lastPitch = pitch;
            s.hasLook = true;
            if (LegacyInputDiag.oncePer("host-look:" + player, 3_000_000_000L)) {
                LegacyInputDiag.log("host look player=" + player + " yaw=" + yaw + " pitch=" + pitch
                        + " focused=" + focused + " pendingYaw=" + s.pendingYaw);
            }
        }
    }

    /** Consumes the current player's accumulated view delta {yawDegrees, pitchDegrees}. */
    public static float[] consumeLookDelta() {
        PlayerState s = current();
        if (s == null) {
            if (LegacyInputDiag.oncePer("look-consume-null", 3_000_000_000L)) {
                LegacyInputDiag.log("look consume with no current player");
            }
            return new float[2];
        }
        synchronized (s) {
            if (LegacyInputDiag.oncePer("look-consume", 3_000_000_000L)) {
                LegacyInputDiag.log("look consume player=" + CURRENT.get() + " pendingYaw=" + s.pendingYaw);
            }
            float[] out = {s.pendingYaw, s.pendingPitch};
            s.pendingYaw = 0f;
            s.pendingPitch = 0f;
            return out;
        }
    }

    /** True when the named player's host window is focused in-world with no screen open. */
    public static boolean focused(String player) {
        if (player == null) {
            return false;
        }
        PlayerState s = STATES.get(player);
        return s != null && s.focused;
    }

    /** Shim for {@code Display.isActive()}: the host window is focused in-world. */
    public static boolean displayIsActive() {
        PlayerState s = current();
        if (LegacyInputDiag.oncePer("display-is-active", 3_000_000_000L)) {
            LegacyInputDiag.log("Display.isActive query player=" + CURRENT.get()
                    + " -> " + (s != null && s.focused));
        }
        return s != null && s.focused;
    }

    /** Shim for {@code Keyboard.areRepeatEventsEnabled()}. */
    public static boolean areRepeatEventsEnabled() {
        return repeatEvents;
    }

    /** Shim for {@code Keyboard.isCreated()}/{@code Mouse.isCreated()}: the shim is always up. */
    public static boolean isCreated() {
        return true;
    }

    // --------------------------------------------------------------- mouse shim

    /** Shim for {@code Mouse.isButtonDown(int)}: button {@code b} is LWJGL code {@code b - 100}. */
    public static boolean isButtonDown(int button) {
        final int lwjglCode = button - 100;
        final String player = CURRENT.get();
        PlayerState s = current();
        if (s == null) {
            if (LegacyInputDiag.oncePer("legacy-mouse-query:null:" + button, 1_000_000_000L)) {
                LegacyInputDiag.log("legacy mouse query player=null button=" + button
                        + " code=" + lwjglCode + " down=false");
            }
            return false;
        }
        synchronized (s) {
            Boolean down = s.down.get(Integer.valueOf(lwjglCode));
            boolean result = down != null && down.booleanValue();
            if (LegacyInputDiag.oncePer("legacy-mouse-query:" + player + ":" + button,
                    1_000_000_000L)) {
                LegacyInputDiag.log("legacy mouse query player=" + player + " button=" + button
                        + " code=" + lwjglCode + " down=" + result
                        + " present=" + (down != null));
            }
            return result;
        }
    }

    /** Shim for {@code Mouse.next()}: advances the mouse edge queue. */
    public static boolean mouseNext() {
        PlayerState s = current();
        if (s == null) {
            return false;
        }
        synchronized (s) {
            KeyEvent e = s.mouseQueue.pollFirst();
            if (e == null) {
                return false;
            }
            s.lastButton = e.code + 100;
            s.lastButtonState = e.down;
            s.hasButton = true;
            return true;
        }
    }

    /** Shim for {@code Mouse.getEventButton()}: returns the button index (code + 100). */
    public static int getEventButton() {
        PlayerState s = current();
        if (s == null) {
            return -1;
        }
        synchronized (s) {
            return s.hasButton ? s.lastButton : -1;
        }
    }

    /** Shim for {@code Mouse.getEventButtonState()}. */
    public static boolean getEventButtonState() {
        PlayerState s = current();
        if (s == null) {
            return false;
        }
        synchronized (s) {
            return s.hasButton && s.lastButtonState;
        }
    }

    /** Shim for every absolute/delta mouse motion query: no pointer exists headless. */
    public static int zero() {
        return 0;
    }

    /** Shim for every void LWJGL lifecycle/poll call the mirror replaces. */
    public static void noop() {
    }

    /** Shim for void single-boolean calls ({@code Mouse.setGrabbed}). */
    public static void ignoreBoolean(boolean ignored) {
    }

    /** Shim for void two-int calls ({@code Mouse.setCursorPosition}). */
    public static void ignoreTwoInts(int ignoredA, int ignoredB) {
    }

    // --------------------------------------------------------------- Sys/Display shims

    /** LWJGL2 Sys.getTimerResolution(): System.nanoTime() is expressed in nanoseconds. */
    public static long sysGetTimerResolution() {
        return 1000000000L;
    }

    /** LWJGL2 Sys.getTime(): monotonic, process-local time without loading lwjgl natives. */
    public static long sysGetTime() {
        return System.nanoTime();
    }

    /** Safe replacement for Minecraft.func_71386_F (the 1.7.10 Sys time helper). */
    public static long safeSystemTime() {
        return System.nanoTime() / 1000000L;
    }

    /** Pure-data fallback for Display.getDisplayMode()/getDesktopDisplayMode(). */
    public static org.lwjgl.opengl.DisplayMode displayMode() {
        return new org.lwjgl.opengl.DisplayMode(854, 480);
    }

    /** Pure-data fallback for Display.getAvailableDisplayModes(). */
    public static org.lwjgl.opengl.DisplayMode[] displayModes() {
        return new org.lwjgl.opengl.DisplayMode[] { displayMode() };
    }
}
