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

    public static class Users implements UserRepository {
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
            return byId.computeIfPresent(userId, (id, u) -> u.withReportStatus(status)) != null;
        }

        @Override
        public boolean updateLastScore(String userId, int score, Instant at) {
            return byId.computeIfPresent(userId, (id, u) -> u.withLastScore(score, at)) != null;
        }

        @Override
        public boolean recordLogin(String userId, Instant at) {
            return byId.computeIfPresent(userId, (id, u) -> u.withLastLogin(at)) != null;
        }

        /** Ids of accounts that still hold a phone number from before it stopped being collected. */
        public final java.util.Set<String> legacyPhones = ConcurrentHashMap.newKeySet();

        @Override
        public boolean delete(User user) {
            legacyPhones.remove(user.userId());
            return byId.remove(user.userId()) != null;
        }

        @Override
        public Page accounts(String after, int limit) {
            java.util.List<String> ids = byId.keySet().stream().sorted()
                    .filter(id -> after == null || id.compareTo(after) > 0).toList();
            java.util.List<AccountSummary> page = ids.stream().limit(limit)
                    .map(byId::get).filter(java.util.Objects::nonNull)
                    .map(u -> new AccountSummary(u.userId(), u.email(), u.lastActiveAt(), legacyPhones.contains(u.userId())))
                    .toList();
            String next = ids.size() > limit ? page.get(page.size() - 1).userId() : null;
            return new Page(page, next);
        }

        @Override
        public void removeLegacyPhone(String userId) {
            legacyPhones.remove(userId);
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

        @Override
        public int deleteAllForUser(String userId) {
            int before = byId.size();
            byId.values().removeIf(s -> s.userId().equals(userId) && !s.sessionId().startsWith(AuthService.USED_CODE_PREFIX));
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
