package com.creditagent.web.auth;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * Users table: partition key {@code userId} (string).
 *
 * <p>Each user has a second item, {@code ssn#<hash>}, that claims their SSN. Both items are written
 * in one conditional transaction, so neither a user id nor an SSN can be registered twice, even by
 * concurrent requests. User ids cannot contain {@code #}, so a claim never collides with a user.
 *
 * <p>A third item, {@code email#<sha-256 of the lower-cased email>}, claims the email in the same
 * transaction, so one email belongs to at most one account: dark-web alerts are looked up by email, and
 * a second account with someone else's address must not see that person's alerts. Accounts created
 * before the claim existed have none; the first new account to use their email takes it.
 */
public class DynamoDbUserRepository implements UserRepository {

    static final String KEY = "userId";
    static final String SSN_CLAIM_PREFIX = "ssn#";
    static final String EMAIL_CLAIM_PREFIX = "email#";
    static final String REPORT_STATUS = "reportStatus";
    static final String ZIP = "zip";
    static final String LAST_SCORE = "lastScore";
    static final String LAST_SCORE_AT = "lastScoreAt";
    static final String CONSENT_VERSION = "consentVersion";
    static final String CONSENTED_AT = "consentedAt";
    static final String LAST_LOGIN_AT = "lastLoginAt";
    /** Stored by accounts made before the phone number stopped being collected; removed by the retention job. */
    static final String LEGACY_PHONE = "phone";
    private static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";

    private final DynamoDbClient dynamo;
    private final String table;

    public DynamoDbUserRepository(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    @Override
    public CreateResult create(User user) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, s(user.userId()));
        item.put("passwordHash", s(user.passwordHash()));
        item.put("email", s(user.email()));
        item.put("ssnHash", s(user.ssnHash()));
        item.put("ssnLastFour", s(user.ssnLastFour()));
        if (user.kycVerificationId() != null) {
            item.put("kycVerificationId", s(user.kycVerificationId()));
        }
        item.put("createdAt", s(user.createdAt().toString()));
        item.put(REPORT_STATUS, s(user.reportStatus().name()));
        if (user.zip() != null) {
            item.put(ZIP, s(user.zip()));
        }
        if (user.consentVersion() != null) {
            item.put(CONSENT_VERSION, s(user.consentVersion()));
            item.put(CONSENTED_AT, s(user.consentedAt().toString()));
        }
        Map<String, AttributeValue> claim = Map.of(
                KEY, s(SSN_CLAIM_PREFIX + user.ssnHash()),
                "owner", s(user.userId()));
        Map<String, AttributeValue> emailClaim = Map.of(
                KEY, s(EMAIL_CLAIM_PREFIX + emailKey(user.email())),
                "owner", s(user.userId()));
        try {
            dynamo.transactWriteItems(r -> r.transactItems(
                    t -> t.put(p -> p.tableName(table).item(item)
                            .conditionExpression("attribute_not_exists(#id)")
                            .expressionAttributeNames(Map.of("#id", KEY))),
                    t -> t.put(p -> p.tableName(table).item(claim)
                            .conditionExpression("attribute_not_exists(#id)")
                            .expressionAttributeNames(Map.of("#id", KEY))),
                    t -> t.put(p -> p.tableName(table).item(emailClaim)
                            .conditionExpression("attribute_not_exists(#id)")
                            .expressionAttributeNames(Map.of("#id", KEY)))));
            return CreateResult.CREATED;
        } catch (TransactionCanceledException e) {
            List<CancellationReason> reasons = e.cancellationReasons();
            if (reasons.size() == 3) {
                if (CONDITIONAL_CHECK_FAILED.equals(reasons.get(0).code())) {
                    return CreateResult.USER_ID_TAKEN;
                }
                if (CONDITIONAL_CHECK_FAILED.equals(reasons.get(1).code())) {
                    return CreateResult.SSN_TAKEN;
                }
                if (CONDITIONAL_CHECK_FAILED.equals(reasons.get(2).code())) {
                    return CreateResult.EMAIL_TAKEN;
                }
            }
            throw e;
        }
    }

    @Override
    public Optional<User> find(String userId) {
        if (isClaimOrMissing(userId)) {
            return Optional.empty();
        }
        GetItemResponse response = dynamo.getItem(r -> r.tableName(table).key(Map.of(KEY, s(userId))).consistentRead(true));
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        Map<String, AttributeValue> item = response.item();
        return Optional.of(new User(
                item.get(KEY).s(),
                item.get("passwordHash").s(),
                item.get("email").s(),
                text(item, "ssnHash"),
                text(item, "ssnLastFour"),
                text(item, "kycVerificationId"),
                Instant.parse(item.get("createdAt").s()),
                ReportStatus.fromStored(text(item, REPORT_STATUS)),
                text(item, ZIP),
                item.containsKey(LAST_SCORE) ? Integer.valueOf(item.get(LAST_SCORE).n()) : null,
                instant(item, LAST_SCORE_AT),
                text(item, CONSENT_VERSION),
                instant(item, CONSENTED_AT),
                instant(item, LAST_LOGIN_AT)));
    }

    /** Reads the {@code ssn#<hash>} claim, then its owner. Two consistent reads. */
    @Override
    public Optional<User> findBySsnHash(String ssnHash) {
        if (ssnHash == null || ssnHash.isEmpty()) {
            return Optional.empty();
        }
        GetItemResponse claim = dynamo.getItem(
                r -> r.tableName(table).key(Map.of(KEY, s(SSN_CLAIM_PREFIX + ssnHash))).consistentRead(true));
        if (!claim.hasItem() || claim.item().isEmpty()) {
            return Optional.empty();
        }
        String owner = text(claim.item(), "owner");
        return owner == null ? Optional.empty() : find(owner);
    }

    /**
     * A conditional update: only an existing user item (it has a password hash; SSN-claim items do
     * not) is changed, so this can never create a user or touch a claim.
     */
    @Override
    public boolean updateReportStatus(String userId, ReportStatus status) {
        if (isClaimOrMissing(userId)) {
            return false;
        }
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(userId)))
                    .updateExpression("SET #status = :status")
                    .conditionExpression("attribute_exists(#id) AND attribute_exists(#pw)")
                    .expressionAttributeNames(Map.of("#id", KEY, "#pw", "passwordHash", "#status", REPORT_STATUS))
                    .expressionAttributeValues(Map.of(":status", s(status.name()))));
            return true;
        } catch (ConditionalCheckFailedException missing) {
            return false;
        }
    }

    /** Conditional, like {@link #updateReportStatus}: only an existing user item is changed. */
    @Override
    public boolean updateLastScore(String userId, int score, Instant at) {
        if (isClaimOrMissing(userId)) {
            return false;
        }
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(userId)))
                    .updateExpression("SET #score = :score, #at = :at")
                    .conditionExpression("attribute_exists(#id) AND attribute_exists(#pw)")
                    .expressionAttributeNames(Map.of("#id", KEY, "#pw", "passwordHash", "#score", LAST_SCORE, "#at", LAST_SCORE_AT))
                    .expressionAttributeValues(Map.of(":score", AttributeValue.fromN(Integer.toString(score)),
                            ":at", s(at.toString()))));
            return true;
        } catch (ConditionalCheckFailedException missing) {
            return false;
        }
    }

    /** Conditional, like {@link #updateReportStatus}: only an existing user item is changed. */
    @Override
    public boolean recordLogin(String userId, Instant at) {
        if (isClaimOrMissing(userId)) {
            return false;
        }
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(userId)))
                    .updateExpression("SET #login = :at")
                    .conditionExpression("attribute_exists(#id) AND attribute_exists(#pw)")
                    .expressionAttributeNames(Map.of("#id", KEY, "#pw", "passwordHash", "#login", LAST_LOGIN_AT))
                    .expressionAttributeValues(Map.of(":at", s(at.toString()))));
            return true;
        } catch (ConditionalCheckFailedException missing) {
            return false;
        }
    }

    /**
     * One transaction deletes the user item and its {@code ssn#} claim (only while the claim still
     * names this user, so a claim never goes missing from under another account). The email claim is
     * deleted after it, under the same condition; accounts from before email claims have none, and a
     * claim taken by another account stays. A retry after a partial failure finishes the job.
     */
    @Override
    public boolean delete(User user) {
        if (isClaimOrMissing(user.userId())) {
            return false;
        }
        boolean existed = find(user.userId()).isPresent();
        Map<String, String> ownerNames = Map.of("#owner", "owner");
        Map<String, AttributeValue> owner = Map.of(":owner", s(user.userId()));
        if (existed) {
            try {
                dynamo.transactWriteItems(r -> r.transactItems(
                        t -> t.delete(d -> d.tableName(table).key(Map.of(KEY, s(user.userId())))),
                        t -> t.delete(d -> d.tableName(table).key(Map.of(KEY, s(SSN_CLAIM_PREFIX + user.ssnHash())))
                                .conditionExpression("attribute_not_exists(#owner) OR #owner = :owner")
                                .expressionAttributeNames(ownerNames).expressionAttributeValues(owner))));
            } catch (TransactionCanceledException e) {
                List<CancellationReason> reasons = e.cancellationReasons();
                if (reasons.size() == 2 && CONDITIONAL_CHECK_FAILED.equals(reasons.get(1).code())) {
                    // The SSN claim names another account (should not happen): delete the user alone.
                    dynamo.deleteItem(d -> d.tableName(table).key(Map.of(KEY, s(user.userId()))));
                } else {
                    throw e;
                }
            }
        }
        try {
            dynamo.deleteItem(d -> d.tableName(table).key(Map.of(KEY, s(EMAIL_CLAIM_PREFIX + emailKey(user.email()))))
                    .conditionExpression("#owner = :owner")
                    .expressionAttributeNames(ownerNames).expressionAttributeValues(owner));
        } catch (ConditionalCheckFailedException notOurs) {
            // No claim (an older account), or another account's: leave it.
        }
        return existed;
    }

    /**
     * A scan for user items (they have a password hash; claims do not), projecting only what the
     * retention job needs. DynamoDB's {@code Limit} counts items read before the filter, so pages are
     * read until {@code limit} accounts are found or the table ends.
     */
    @Override
    public Page accounts(String after, int limit) {
        List<AccountSummary> found = new java.util.ArrayList<>();
        Map<String, AttributeValue> startKey = after == null ? null : Map.of(KEY, s(after));
        String lastSeen = after;
        do {
            Map<String, AttributeValue> start = startKey;
            var page = dynamo.scan(r -> {
                r.tableName(table)
                        .filterExpression("attribute_exists(#pw)")
                        .projectionExpression("#id, #email, #created, #login, #phone")
                        .expressionAttributeNames(Map.of("#id", KEY, "#pw", "passwordHash", "#email", "email",
                                "#created", "createdAt", "#login", LAST_LOGIN_AT, "#phone", LEGACY_PHONE))
                        .limit(limit);
                if (start != null) {
                    r.exclusiveStartKey(start);
                }
            });
            for (Map<String, AttributeValue> item : page.items()) {
                if (found.size() == limit) {
                    break;
                }
                Instant created = Instant.parse(item.get("createdAt").s());
                Instant login = instant(item, LAST_LOGIN_AT);
                found.add(new AccountSummary(item.get(KEY).s(), text(item, "email"),
                        login != null && login.isAfter(created) ? login : created, item.containsKey(LEGACY_PHONE)));
                lastSeen = item.get(KEY).s();
            }
            boolean more = page.hasLastEvaluatedKey() && !page.lastEvaluatedKey().isEmpty();
            if (found.size() == limit) {
                return new Page(found, lastSeen);
            }
            startKey = more ? page.lastEvaluatedKey() : null;
        } while (startKey != null);
        return new Page(found, null);
    }

    @Override
    public void removeLegacyPhone(String userId) {
        if (isClaimOrMissing(userId)) {
            return;
        }
        try {
            dynamo.updateItem(r -> r.tableName(table)
                    .key(Map.of(KEY, s(userId)))
                    .updateExpression("REMOVE #phone")
                    .conditionExpression("attribute_exists(#pw)")
                    .expressionAttributeNames(Map.of("#phone", LEGACY_PHONE, "#pw", "passwordHash")));
        } catch (ConditionalCheckFailedException gone) {
            // Deleted meanwhile.
        }
    }

    private static Instant instant(Map<String, AttributeValue> item, String name) {
        String value = text(item, name);
        return value == null ? null : Instant.parse(value);
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    /** A string attribute that rows written before it existed may lack. */
    static String text(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    /** Claim items ({@code ssn#…}, {@code email#…}) are never users: user ids cannot contain {@code #}. */
    private static boolean isClaimOrMissing(String userId) {
        return userId == null || userId.indexOf('#') >= 0;
    }

    /** The email's claim key: a SHA-256 of the lower-cased address, so the key itself is not the address. */
    static String emailKey(String email) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(email.strip().toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
