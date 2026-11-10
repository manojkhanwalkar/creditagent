package com.creditagent.reports;

import com.creditagent.common.Ssn;
import com.creditagent.common.SsnHmac;
import com.creditagent.common.bureau.Bureau;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/**
 * The {@code CreditReports} table: partition key {@code ssn}, sort key {@code bureau} (e.g.
 * {@code Equifax}), so one query returns a consumer's three reports. Attributes: {@code report} (binary,
 * the report JSON encrypted by {@link ReportCipher}), {@code keyId} (which key) and {@code updatedAt}.
 *
 * <p>The {@code ssn} column holds the SSN's keyed hash (HMAC-SHA256, {@link SsnHmac}), never the SSN:
 * DynamoDB cannot encrypt key attributes, and a plain hash of the ~10^9 possible SSNs is brute-forced
 * in minutes. The key hash is also the encryption's associated data, with the bureau.
 */
public class DynamoDbCreditReportStore implements CreditReportStore {

    public static final String SSN = "ssn";
    public static final String BUREAU = "bureau";
    public static final String REPORT = "report";
    public static final String KEY_ID = "keyId";
    public static final String UPDATED_AT = "updatedAt";
    /** Two writers replacing the same consumer's set at once: the loser tries again, up to this many times. */
    static final int MAX_ATTEMPTS = 3;

    private final DynamoDbClient dynamo;
    private final String table;
    private final SsnHmac ssnHash;
    private final ReportCipher cipher;
    private final Clock clock;

    public DynamoDbCreditReportStore(DynamoDbClient dynamo, String table, SsnHmac ssnHash, ReportCipher cipher, Clock clock) {
        this.dynamo = Objects.requireNonNull(dynamo);
        this.table = Objects.requireNonNull(table);
        this.ssnHash = Objects.requireNonNull(ssnHash);
        this.cipher = Objects.requireNonNull(cipher);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void replaceAll(Ssn ssn, Map<Bureau, String> reports) {
        String key = ssnHash.hex(ssn);
        List<TransactWriteItem> writes = new ArrayList<>();
        String now = clock.instant().toString();
        for (Bureau bureau : Bureau.values()) {
            String json = reports.get(bureau);
            Map<String, AttributeValue> itemKey = key(key, bureau);
            if (json == null) {
                writes.add(TransactWriteItem.builder().delete(d -> d.tableName(table).key(itemKey)).build());
                continue;
            }
            byte[] plaintext = json.getBytes(StandardCharsets.UTF_8);
            if (plaintext.length > MAX_REPORT_BYTES) {
                throw new IllegalArgumentException(bureau.displayName() + " report exceeds " + MAX_REPORT_BYTES + " bytes");
            }
            ReportCipher.Sealed sealed = cipher.encrypt(plaintext, associatedData(key, bureau));
            Map<String, AttributeValue> item = new java.util.HashMap<>(itemKey);
            item.put(REPORT, AttributeValue.fromB(SdkBytes.fromByteArray(sealed.bytes())));
            item.put(KEY_ID, AttributeValue.fromS(sealed.keyId()));
            item.put(UPDATED_AT, AttributeValue.fromS(now));
            writes.add(TransactWriteItem.builder().put(p -> p.tableName(table).item(item)).build());
        }
        // One transaction: readers see the old set or the new one, never a mix. Replacing a whole set is
        // idempotent, so a write that lost a race with another writer of the same consumer is tried again.
        for (int attempt = 1; ; attempt++) {
            try {
                dynamo.transactWriteItems(r -> r.transactItems(writes));
                return;
            } catch (TransactionCanceledException e) {
                if (attempt >= MAX_ATTEMPTS || !onlyConflicts(e)) {
                    throw e;
                }
            }
        }
    }

    @Override
    public Map<Bureau, String> findAll(Ssn ssn) {
        String key = ssnHash.hex(ssn);
        QueryResponse response = dynamo.query(r -> r.tableName(table)
                .keyConditionExpression("#ssn = :ssn")
                .expressionAttributeNames(Map.of("#ssn", SSN))
                .expressionAttributeValues(Map.of(":ssn", AttributeValue.fromS(key)))
                .consistentRead(true)
                .limit(Bureau.values().length + 1));
        Map<Bureau, String> reports = new EnumMap<>(Bureau.class);
        for (Map<String, AttributeValue> item : response.items()) {
            Bureau bureau = bureau(item.get(BUREAU).s());
            if (bureau == null) {
                continue; // not one of ours: ignore rather than guess
            }
            byte[] plaintext = cipher.decrypt(new ReportCipher.Sealed(item.get(KEY_ID).s(), item.get(REPORT).b().asByteArray()),
                    associatedData(key, bureau));
            reports.put(bureau, new String(plaintext, StandardCharsets.UTF_8));
        }
        return reports;
    }

    static String associatedData(String ssnKey, Bureau bureau) {
        return "CreditReports|" + ssnKey + "|" + bureau.displayName();
    }

    private static Map<String, AttributeValue> key(String ssnKey, Bureau bureau) {
        return Map.of(SSN, AttributeValue.fromS(ssnKey), BUREAU, AttributeValue.fromS(bureau.displayName()));
    }

    private static Bureau bureau(String name) {
        for (Bureau bureau : Bureau.values()) {
            if (bureau.displayName().equals(name)) {
                return bureau;
            }
        }
        return null;
    }

    private static boolean onlyConflicts(TransactionCanceledException e) {
        if (!e.hasCancellationReasons()) {
            return false;
        }
        boolean conflict = false;
        for (CancellationReason reason : e.cancellationReasons()) {
            String code = reason.code();
            if ("TransactionConflict".equals(code)) {
                conflict = true;
            } else if (code != null && !"None".equals(code)) {
                return false;
            }
        }
        return conflict;
    }
}
