package com.creditagent.advisor.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.advisor.memory.ConversationMemory.Turn;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConversationMemoryTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-09T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final AtomicReference<Double> heap = new AtomicReference<>(0.1);

    ConversationMemory memory(int maxTurns, int maxConversations) {
        return new ConversationMemory(Duration.ofMinutes(5), maxTurns, maxConversations, 0.85, heap::get, clock);
    }

    static Turn turn(int i) {
        return new Turn("q" + i, "a" + i);
    }

    @Test
    void remembersTurnsInOrderPerConversation() {
        ConversationMemory memory = memory(10, 10);
        memory.remember("alice-conversation-1", turn(1));
        memory.remember("alice-conversation-1", turn(2));
        memory.remember("bob-conversation-01", turn(9));
        assertThat(memory.history("alice-conversation-1")).containsExactly(turn(1), turn(2));
        assertThat(memory.history("bob-conversation-01")).containsExactly(turn(9));
        assertThat(memory.history("nobody")).isEmpty();
    }

    @Test
    void keepsOnlyTheLatestTurns() {
        ConversationMemory memory = memory(2, 10);
        for (int i = 1; i <= 4; i++) {
            memory.remember("c", turn(i));
        }
        assertThat(memory.history("c")).containsExactly(turn(3), turn(4));
    }

    @Test
    void anIdleConversationExpiresButAnActiveOneDoesNot() {
        ConversationMemory memory = memory(10, 10);
        memory.remember("active", turn(1));
        memory.remember("idle", turn(1));
        clock.now = clock.now.plus(Duration.ofMinutes(4));
        memory.remember("active", turn(2)); // use resets the idle clock
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        assertThat(memory.history("idle")).isEmpty();
        assertThat(memory.history("active")).hasSize(2);
        clock.now = clock.now.plus(Duration.ofMinutes(5));
        assertThat(memory.sweep()).isEqualTo(1);
        assertThat(memory.size()).isZero();
    }

    @Test
    void forgetEndsAConversationAtOnce() {
        ConversationMemory memory = memory(10, 10);
        memory.remember("c", turn(1));
        memory.forget("c");
        assertThat(memory.history("c")).isEmpty();
    }

    @Test
    void pastCapacityTheLeastRecentlyUsedGoes() {
        ConversationMemory memory = memory(10, 2);
        memory.remember("a", turn(1));
        memory.remember("b", turn(1));
        memory.history("a"); // a is now more recent than b
        memory.remember("c", turn(1));
        assertThat(memory.size()).isEqualTo(2);
        assertThat(memory.history("b")).isEmpty();
        assertThat(memory.history("a")).isNotEmpty();
        assertThat(memory.history("c")).isNotEmpty();
    }

    @Test
    void underMemoryPressureTheLeastRecentlyUsedHalfGoes() {
        ConversationMemory memory = memory(10, 100);
        for (String id : new String[] {"a", "b", "c", "d"}) {
            memory.remember(id, turn(1));
        }
        memory.history("a");
        memory.history("b");
        heap.set(0.9);
        memory.remember("e", turn(1)); // a new conversation under pressure: c and d (least recent) go
        assertThat(memory.history("c")).isEmpty();
        assertThat(memory.history("d")).isEmpty();
        assertThat(memory.history("a")).isNotEmpty();
        assertThat(memory.history("e")).isNotEmpty();
        // Adding to an existing conversation does not trigger eviction.
        memory.remember("a", turn(2));
        assertThat(memory.size()).isEqualTo(3);
    }

    @Test
    void jvmHeapUsageIsAFraction() {
        assertThat(ConversationMemory.jvmHeapUsage()).isBetween(0.0, 1.0);
    }
}
