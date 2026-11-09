package com.extantape.latchkey.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InviteCheckEventTest {
    InviteCheckEvent e(Set<String> yes) { return new InviteCheckEvent(UUID.randomUUID(), "Alex", List.of("builders"), yes); }

    @Test void theFirstRefusalWins() {
        var e = e(Set.of());
        e.refuse("restricted", "first");
        e.refuse("other", "second");
        assertEquals("restricted", e.getRefusalCode());
        assertEquals("first", e.getRefusalMessage());
    }

    @Test void answeredQuestionsAreNotAskedAgain() {
        var e = e(Set.of("bridge"));
        e.need("bridge", "Links chats.");
        e.need("confirm-builders", "Builders fly.");
        assertEquals(Map.of("confirm-builders", "Builders fly."), e.getUnanswered());
        assertEquals(2, e.getAsked().size());
    }

    @Test void questionIdsMustFitTheCommandFormat() {
        var e = e(Set.of());
        assertThrows(IllegalArgumentException.class, () -> e.need("confirm:builders", "x"));
        assertThrows(IllegalArgumentException.class, () -> e.need("", "x"));
    }

    @Test void nobodyCheckingIsVisible() {
        var e = e(Set.of());
        assertTrue(e.getCheckedBy().isEmpty());
        e.checkedBy("Usher");
        assertEquals(List.of("Usher"), e.getCheckedBy());
    }
}
