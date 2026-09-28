package dev.umb.hostagent;

/**
 * Legacy 1.7.10 code is single-threaded and was never written to expect a caller from any thread
 * other than the server tick thread. Every bridge call (tile ticking, container activation,
 * NBT round trips) must be on that thread or the legacy universe's static state gets corrupted
 * silently. This is the one guard every such call site runs through.
 */
public final class UmbThread {

    /** The server tick thread, captured the first time a bridge call runs. Null until then. */
    private static volatile Thread serverThread;

    private UmbThread() {
    }

    /** Call once, from the server thread, as early as convenient (e.g. the first tile tick). */
    public static void bindServerThread() {
        if (serverThread == null) {
            serverThread = Thread.currentThread();
        }
    }

    /**
     * Throws IllegalStateException if called off the bound server thread. If no thread has been
     * bound yet, binds the CURRENT thread (best-effort: the first caller wins, matching the
     * "server thread only" contract without requiring an explicit boot-time registration).
     */
    public static void assertServer() {
        Thread t = serverThread;
        if (t == null) {
            bindServerThread();
            return;
        }
        if (Thread.currentThread() != t) {
            throw new IllegalStateException("UMB-HOSTAGENT bridge call off the server thread: "
                    + Thread.currentThread().getName() + " (bound=" + t.getName() + ")");
        }
    }

    /** For tests: forget the bound thread so a fresh test can bind its own. */
    public static void resetForTests() {
        serverThread = null;
    }
}
