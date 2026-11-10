package com.creditagent.advisor.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * Per-user conversation history, in memory only: nothing is written to disk, and a restart forgets
 * everything. Bounded on every axis:
 *
 * <ul>
 *   <li><b>Idle time:</b> a conversation unused for {@code ttl} is gone (checked on every read, and
 *       swept by {@link #sweep()}). The web server also {@link #forget forgets} it at logout.
 *   <li><b>Turns:</b> at most {@code maxTurns} per conversation; the oldest are dropped.
 *   <li><b>Conversations:</b> at most {@code maxConversations}; adding one more drops the least
 *       recently used (LRU).
 *   <li><b>Memory pressure:</b> when heap use passes {@code pressureThreshold}, the least recently
 *       used half is dropped. A forgotten conversation only costs the user their context.
 * </ul>
 *
 * <p>Thread safe: every method holds the instance lock, and each call is short.
 */
public class ConversationMemory {

    /** One question and its answer, as stored: both already through the guardrails. */
    public record Turn(String question, String answer) {}

    private static final class Conversation {
        final Deque<Turn> turns = new ArrayDeque<>();
        Instant lastUsed;

        Conversation(Instant now) {
            lastUsed = now;
        }
    }

    private final Duration ttl;
    private final int maxTurns;
    private final int maxConversations;
    private final double pressureThreshold;
    private final DoubleSupplier heapUsage;
    private final Clock clock;
    /** Access-ordered: iteration starts at the least recently used. */
    private final LinkedHashMap<String, Conversation> conversations = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * @param heapUsage share of the maximum heap in use, 0 to 1
     */
    public ConversationMemory(Duration ttl, int maxTurns, int maxConversations, double pressureThreshold,
            DoubleSupplier heapUsage, Clock clock) {
        this.ttl = Objects.requireNonNull(ttl);
        this.maxTurns = maxTurns;
        this.maxConversations = maxConversations;
        this.pressureThreshold = pressureThreshold;
        this.heapUsage = Objects.requireNonNull(heapUsage);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Share of the JVM's maximum heap in use. */
    public static double jvmHeapUsage() {
        Runtime runtime = Runtime.getRuntime();
        return (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
    }

    /** The conversation so far, oldest first; empty if there is none or it has expired. */
    public synchronized List<Turn> history(String conversationId) {
        Conversation conversation = live(conversationId);
        return conversation == null ? List.of() : List.copyOf(conversation.turns);
    }

    /** Appends a turn, starting the conversation if needed. */
    public synchronized void remember(String conversationId, Turn turn) {
        Instant now = clock.instant();
        Conversation conversation = live(conversationId);
        if (conversation == null) {
            relieveMemoryPressure();
            conversation = new Conversation(now);
            conversations.put(conversationId, conversation);
            evictBeyondCapacity();
        }
        conversation.turns.addLast(turn);
        while (conversation.turns.size() > maxTurns) {
            conversation.turns.removeFirst();
        }
        conversation.lastUsed = now;
    }

    /** Forgets a conversation (the user logged out). */
    public synchronized void forget(String conversationId) {
        conversations.remove(conversationId);
    }

    /** Drops every conversation idle for longer than the TTL. @return how many */
    public synchronized int sweep() {
        Instant now = clock.instant();
        int before = conversations.size();
        conversations.values().removeIf(c -> expired(c, now));
        return before - conversations.size();
    }

    public synchronized int size() {
        return conversations.size();
    }

    private Conversation live(String conversationId) {
        Conversation conversation = conversations.get(conversationId);
        if (conversation != null && expired(conversation, clock.instant())) {
            conversations.remove(conversationId);
            return null;
        }
        return conversation;
    }

    private boolean expired(Conversation conversation, Instant now) {
        return !now.isBefore(conversation.lastUsed.plus(ttl));
    }

    private void evictBeyondCapacity() {
        Iterator<Map.Entry<String, Conversation>> lru = conversations.entrySet().iterator();
        while (conversations.size() > maxConversations && lru.hasNext()) {
            lru.next();
            lru.remove();
        }
    }

    private void relieveMemoryPressure() {
        if (heapUsage.getAsDouble() < pressureThreshold) {
            return;
        }
        sweep();
        int drop = conversations.size() / 2;
        Iterator<Map.Entry<String, Conversation>> lru = conversations.entrySet().iterator();
        for (int i = 0; i < drop && lru.hasNext(); i++) {
            lru.next();
            lru.remove();
        }
    }
}
