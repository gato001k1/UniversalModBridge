package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ChatDeliveryDeduplicatorTest {
    @AfterEach
    void clear() {
        ChatDeliveryDeduplicator.clearForTests();
    }

    @Test
    void suppressesOnlySamePlayerAndTextInsideDeliveryWindow() {
        assertTrue(ChatDeliveryDeduplicator.accept("p1", "Loaded world", 1_000_000_000L));
        assertFalse(ChatDeliveryDeduplicator.accept("p1", "Loaded world", 1_100_000_000L));
        assertTrue(ChatDeliveryDeduplicator.accept("p1", "New version", 1_100_000_000L));
        assertTrue(ChatDeliveryDeduplicator.accept("p2", "Loaded world", 1_100_000_000L));
        assertTrue(ChatDeliveryDeduplicator.accept("p1", "Loaded world", 1_300_000_000L));
    }
}
