package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LegacyGuiClickChannelTest {
    @Test
    void requestRoundTripsExactlyOnce() {
        int id = LegacyGuiClickChannel.put(7, 125, 0, 123, 287);
        assertTrue(LegacyGuiClickChannel.isRequest(id));
        LegacyGuiClickChannel.Request request = LegacyGuiClickChannel.take(id);
        assertNotNull(request);
        assertEquals(7, request.guiX);
        assertEquals(125, request.guiY);
        assertEquals(0, request.button);
        assertEquals(123, request.screenX);
        assertEquals(287, request.screenY);
        assertNull(LegacyGuiClickChannel.take(id), "a server menu consumes a click key once");
    }

    @Test
    void ordinaryContainerButtonIdsAreNeverConsumedAsRawClicks() {
        assertFalse(LegacyGuiClickChannel.isRequest(0));
        assertFalse(LegacyGuiClickChannel.isRequest(127));
    }
}
