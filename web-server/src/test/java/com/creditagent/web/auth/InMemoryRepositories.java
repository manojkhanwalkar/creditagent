package com.creditagent.web.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test doubles with the same contract as the DynamoDB repositories. */
final class InMemoryRepositories {

    private InMemoryRepositories() {}

    static final class Users implements UserRepository {
        final Map<String, User> byId = new ConcurrentHashMap<>();

        @Override
        public boolean createIfAbsent(User user) {
            return byId.putIfAbsent(user.userId(), user) == null;
        }

        @Override
        public Optional<User> find(String userId) {
            return Optional.ofNullable(byId.get(userId));
        }
    }

    static final class Sessions implements SessionRepository {
        final Map<String, Session> byId = new ConcurrentHashMap<>();

        @Override
        public void save(Session session) {
            byId.put(session.sessionId(), session);
        }

        @Override
        public Optional<Session> find(String sessionId) {
            return Optional.ofNullable(byId.get(sessionId));
        }

        @Override
        public void delete(String sessionId) {
            byId.remove(sessionId);
        }

        @Override
        public int deleteExpired(Instant now) {
            int before = byId.size();
            byId.values().removeIf(s -> s.isExpired(now));
            return before - byId.size();
        }
    }

    static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

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
}
