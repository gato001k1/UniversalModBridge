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

    // Key events ride the same button packet under their own prefix; like clicks, the packet
    // carries only the request key and the (char, keyCode) pair waits here.
    private static final int KEY_PREFIX = 0x7E000000;
    private static final ConcurrentHashMap<Integer, KeyRequest> PENDING_KEYS = new ConcurrentHashMap<>();

    static int putKey(char typedChar, int keyCode) {
        for (int attempt = 0; attempt < MAX_PENDING; attempt++) {
            int key = KEY_PREFIX | (NEXT.getAndIncrement() & 0x00FFFFFF);
            if (PENDING_KEYS.size() >= MAX_PENDING) {
                PENDING_KEYS.remove(PENDING_KEYS.keySet().iterator().next());
            }
            if (PENDING_KEYS.putIfAbsent(key, new KeyRequest(typedChar, keyCode)) == null) {
                return key;
            }
        }
        throw new IllegalStateException("legacy GUI key channel exhausted");
    }

    static boolean isKeyRequest(int id) {
        return (id & 0xFF000000) == KEY_PREFIX;
    }

    static KeyRequest takeKey(int id) {
        return isKeyRequest(id) ? PENDING_KEYS.remove(id) : null;
    }

    static final class KeyRequest {
        final char typedChar;
        final int keyCode;

        KeyRequest(char typedChar, int keyCode) {
            this.typedChar = typedChar;
            this.keyCode = keyCode;
        }
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
