package com.creditagent.web.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test doubles with the same contract as the DynamoDB repositories. */
public final class InMemoryRepositories {

    private InMemoryRepositories() {}

    public static final class Users implements UserRepository {
        public final Map<String, User> byId = new ConcurrentHashMap<>();

        @Override
        public synchronized CreateResult create(User user) {
            if (byId.containsKey(user.userId())) {
                return CreateResult.USER_ID_TAKEN;
            }
            if (byId.values().stream().anyMatch(u -> user.ssnHash().equals(u.ssnHash()))) {
                return CreateResult.SSN_TAKEN;
            }
            if (byId.values().stream().anyMatch(u -> user.email().equalsIgnoreCase(u.email()))) {
                return CreateResult.EMAIL_TAKEN;
            }
            byId.put(user.userId(), user);
            return CreateResult.CREATED;
        }

        @Override
        public Optional<User> find(String userId) {
            return Optional.ofNullable(byId.get(userId));
        }

        @Override
        public Optional<User> findBySsnHash(String ssnHash) {
            return byId.values().stream().filter(u -> u.ssnHash().equals(ssnHash)).findFirst();
        }

        @Override
        public boolean updateReportStatus(String userId, ReportStatus status) {
            return byId.computeIfPresent(userId, (id, u) -> new User(
                    u.userId(), u.passwordHash(), u.email(), u.phone(), u.ssnHash(), u.ssnLastFour(),
                    u.kycVerificationId(), u.createdAt(), status, u.zip(), u.lastScore(), u.lastScoreAt())) != null;
        }

        @Override
        public boolean updateLastScore(String userId, int score, Instant at) {
            return byId.computeIfPresent(userId, (id, u) -> new User(
                    u.userId(), u.passwordHash(), u.email(), u.phone(), u.ssnHash(), u.ssnLastFour(),
                    u.kycVerificationId(), u.createdAt(), u.reportStatus(), u.zip(), score, at)) != null;
        }
    }

    public static class Sessions implements SessionRepository {
        public final Map<String, Session> byId = new ConcurrentHashMap<>();

        @Override
        public void save(Session session) {
            byId.put(session.sessionId(), session);
        }

        @Override
        public boolean create(Session session) {
            return byId.putIfAbsent(session.sessionId(), session) == null;
        }

        @Override
        public Optional<Session> find(String sessionId) {
            return Optional.ofNullable(byId.get(sessionId));
        }

        @Override
        public boolean touch(String sessionId, Instant lastActivityAt, Instant expiresAt) {
            return byId.computeIfPresent(sessionId, (id, s) -> new Session(
                    id, s.userId(), s.ssnHash(), s.ssnLastFour(), s.createdAt(), lastActivityAt, expiresAt)) != null;
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

    public static final class MutableClock extends Clock {
        Instant now;

        public MutableClock(Instant start) {
            now = start;
        }

        public void advance(java.time.Duration by) {
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
