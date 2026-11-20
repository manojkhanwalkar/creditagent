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

    /** Same contract as {@link DynamoDbOtpChallengeRepository}, every conditional write atomic. */
    public static class OtpChallenges implements OtpChallengeRepository {
        public final Map<String, OtpChallenge> byId = new ConcurrentHashMap<>();
        public final Map<String, Integer> userSends = new ConcurrentHashMap<>();

        @Override
        public void create(OtpChallenge challenge) {
            byId.put(challenge.challengeId(), challenge);
        }

        @Override
        public Optional<OtpChallenge> find(String challengeId) {
            return Optional.ofNullable(byId.get(challengeId));
        }

        @Override
        public synchronized boolean recordSend(String challengeId, com.creditagent.web.auth.otp.OtpChannel channel,
                String codeHash, Instant now, Instant codeExpiresAt, Instant notSentSince, int maxSends) {
            OtpChallenge c = byId.get(challengeId);
            if (c == null || c.isExpired(now) || c.sends() >= maxSends
                    || c.lastSentAt() != null && c.lastSentAt().isAfter(notSentSince)) {
                return false;
            }
            byId.put(challengeId, new OtpChallenge(c.challengeId(), c.userId(), c.createdAt(), c.expiresAt(), channel,
                    codeHash, codeExpiresAt, now, c.sends() + 1, c.attempts()));
            return true;
        }

        @Override
        public synchronized boolean recordAttempt(String challengeId, int maxAttempts) {
            OtpChallenge c = byId.get(challengeId);
            if (c == null || c.attempts() >= maxAttempts) {
                return false;
            }
            byId.put(challengeId, new OtpChallenge(c.challengeId(), c.userId(), c.createdAt(), c.expiresAt(), c.channel(),
                    c.codeHash(), c.codeExpiresAt(), c.lastSentAt(), c.sends(), c.attempts() + 1));
            return true;
        }

        @Override
        public synchronized boolean consume(String challengeId, String codeHash) {
            OtpChallenge c = byId.get(challengeId);
            if (c == null || !codeHash.equals(c.codeHash())) {
                return false;
            }
            byId.remove(challengeId);
            return true;
        }

        @Override
        public void delete(String challengeId) {
            byId.remove(challengeId);
        }

        @Override
        public synchronized void releaseSend(String challengeId, String codeHash, OtpChallenge previous) {
            OtpChallenge c = byId.get(challengeId);
            if (c != null && codeHash.equals(c.codeHash()) && c.sends() > 0) {
                boolean had = previous != null && previous.codeHash() != null;
                byId.put(challengeId, new OtpChallenge(c.challengeId(), c.userId(), c.createdAt(), c.expiresAt(),
                        had ? previous.channel() : null, had ? previous.codeHash() : null,
                        had ? previous.codeExpiresAt() : null, had ? previous.lastSentAt() : null, c.sends() - 1, c.attempts()));
            }
        }

        @Override
        public synchronized void releaseUserSend(String userId, Instant now) {
            userSends.computeIfPresent(userId + "#" + now.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                    (k, n) -> n > 0 ? n - 1 : 0);
        }

        @Override
        public synchronized boolean reserveUserSend(String userId, Instant now, int maxPerHour) {
            String key = userId + "#" + now.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            int sent = userSends.getOrDefault(key, 0);
            if (sent >= maxPerHour) {
                return false;
            }
            userSends.put(key, sent + 1);
            return true;
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
