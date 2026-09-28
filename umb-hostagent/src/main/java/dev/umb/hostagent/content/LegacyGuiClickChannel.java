package dev.umb.hostagent.content;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process transport for a raw legacy GUI click.  The vanilla 26.2 button packet carries only
 * one integer, while a legacy hit test needs the panel-relative point and the host panel origin.
 * The client stores the bounded request here and sends its key through the normal container-button
 * packet; the server-side UmbLegacyMenu consumes it exactly once.  This keeps gui_click on the
 * same client-to-server path as a real menu button and avoids inventing a mod-specific packet.
 */
final class LegacyGuiClickChannel {
    private static final int PREFIX = 0x7F000000;
    private static final int MAX_PENDING = 64;
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final ConcurrentHashMap<Integer, Request> PENDING = new ConcurrentHashMap<>();

    private LegacyGuiClickChannel() {
    }

    static int put(int guiX, int guiY, int button, int screenX, int screenY) {
        for (int attempt = 0; attempt < MAX_PENDING; attempt++) {
            int key = PREFIX | (NEXT.getAndIncrement() & 0x00FFFFFF);
            if (PENDING.size() >= MAX_PENDING) {
                PENDING.remove(PENDING.keySet().iterator().next());
            }
            if (PENDING.putIfAbsent(key, new Request(guiX, guiY, button, screenX, screenY)) == null) {
                return key;
            }
        }
        throw new IllegalStateException("legacy GUI click channel exhausted");
    }

    static boolean isRequest(int id) {
        return (id & 0xFF000000) == PREFIX;
    }

    static Request take(int id) {
        return isRequest(id) ? PENDING.remove(id) : null;
    }

    static final class Request {
        final int guiX, guiY, button, screenX, screenY;

        Request(int guiX, int guiY, int button, int screenX, int screenY) {
            this.guiX = guiX;
            this.guiY = guiY;
            this.button = button;
            this.screenX = screenX;
            this.screenY = screenY;
        }
    }
}
