package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LegacyKeyCodesTest {
    @Test
    void mapsGlfwKeysToLwjgl2Codes() {
        assertEquals(30, LegacyKeyCodes.lwjgl(65));  // A
        assertEquals(44, LegacyKeyCodes.lwjgl(90));  // Z
        assertEquals(2, LegacyKeyCodes.lwjgl(49));   // 1
        assertEquals(11, LegacyKeyCodes.lwjgl(48));  // 0
        assertEquals(14, LegacyKeyCodes.lwjgl(259)); // backspace
        assertEquals(28, LegacyKeyCodes.lwjgl(257)); // enter
        assertEquals(203, LegacyKeyCodes.lwjgl(263)); // left
        assertEquals(211, LegacyKeyCodes.lwjgl(261)); // delete
        assertEquals(0, LegacyKeyCodes.lwjgl(290));  // F1: no legacy text meaning here
    }

    @Test
    void typedCharsCarryTheirLegacyKeyCode() {
        assertEquals(18, LegacyKeyCodes.lwjglForChar('e'));
        assertEquals(18, LegacyKeyCodes.lwjglForChar('E'));
        assertEquals(6, LegacyKeyCodes.lwjglForChar('5'));
        assertEquals(52, LegacyKeyCodes.lwjglForChar('.'));
        assertEquals(0, LegacyKeyCodes.lwjglForChar('é'));
    }

    @Test
    void editingKeysAndCtrlShortcuts() {
        assertTrue(LegacyKeyCodes.isEditingKey(259));
        assertFalse(LegacyKeyCodes.isEditingKey(65));
        assertEquals('\b', LegacyKeyCodes.editingChar(259));
        assertEquals('\r', LegacyKeyCodes.editingChar(257));
        assertEquals((char) 22, LegacyKeyCodes.ctrlChar(86)); // Ctrl+V paste
        assertEquals((char) 1, LegacyKeyCodes.ctrlChar(65));  // Ctrl+A select all
        assertEquals('\0', LegacyKeyCodes.ctrlChar(66));
    }

    @Test
    void keyRequestsAreSeparateFromClicksAndConsumedOnce() {
        int key = LegacyGuiClickChannel.putKey('7', 8);
        assertTrue(LegacyGuiClickChannel.isKeyRequest(key));
        assertFalse(LegacyGuiClickChannel.isRequest(key));
        assertNull(LegacyGuiClickChannel.take(key));
        LegacyGuiClickChannel.KeyRequest request = LegacyGuiClickChannel.takeKey(key);
        assertNotNull(request);
        assertEquals('7', request.typedChar);
        assertEquals(8, request.keyCode);
        assertNull(LegacyGuiClickChannel.takeKey(key));

        int click = LegacyGuiClickChannel.put(1, 2, 0, 3, 4);
        assertFalse(LegacyGuiClickChannel.isKeyRequest(click));
        assertNull(LegacyGuiClickChannel.takeKey(click));
        assertNotNull(LegacyGuiClickChannel.take(click));
    }
}
