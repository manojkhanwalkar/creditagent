package com.creditagent.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.creditagent.common.Ssn;
import com.creditagent.common.SsnHmac;
import com.creditagent.common.bureau.Bureau;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

/** The transaction's retry rules and the size limit, without a database. */
class DynamoDbCreditReportStoreTest {

    static final String KEY = "hash-key-hash-key-hash-key-hash-key!";
    final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    final java.util.List<java.time.Duration> pauses = new java.util.ArrayList<>();
    final DynamoDbCreditReportStore store = new DynamoDbCreditReportStore(dynamo, "CreditReports", new SsnHmac(KEY, "k"),
            new ReportCipher(ReportCipherTest.NEW, null), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), pauses::add);
    final Ssn ssn = Ssn.parse("123-45-6789");

    static TransactionCanceledException cancelled(String... codes) {
        return TransactionCanceledException.builder().cancellationReasons(java.util.Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build()).toList()).build();
    }

    @SuppressWarnings("unchecked")
    void transactionsFail(RuntimeException... failures) {
        var stub = when(dynamo.transactWriteItems(any(Consumer.class)));
        for (RuntimeException failure : failures) {
            stub = stub.thenThrow(failure);
        }
        stub.thenReturn(TransactWriteItemsResponse.builder().build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWriterThatLostARaceTriesAgain() {
        transactionsFail(cancelled("TransactionConflict", "None", "None"));
        store.replaceAll(ssn, Map.of(Bureau.EQUIFAX, "{}"));
        verify(dynamo, times(2)).transactWriteItems(any(Consumer.class));
        // One pause, with full jitter: anywhere from 0 to the first ceiling.
        assertThat(pauses).hasSize(1);
        assertThat(pauses.get(0)).isBetween(java.time.Duration.ZERO, DynamoDbCreditReportStore.BASE_BACKOFF);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReadThatConflictsWithAWriteTriesAgainAndReadsTheWholeSetInOneTransaction() {
        when(dynamo.transactGetItems(any(Consumer.class)))
                .thenThrow(cancelled("None", "TransactionConflict", "None"))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse.builder()
                        .responses(software.amazon.awssdk.services.dynamodb.model.ItemResponse.builder().build()).build());
        assertThat(store.findAll(ssn)).isEmpty();
        verify(dynamo, times(2)).transactGetItems(any(Consumer.class));
        assertThat(pauses).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anInterruptedBackoffGivesUp() {
        DynamoDbCreditReportStore interrupted = new DynamoDbCreditReportStore(dynamo, "CreditReports", new SsnHmac(KEY, "k"),
                new ReportCipher(ReportCipherTest.NEW, null), Clock.systemUTC(), duration -> {
                    throw new InterruptedException();
                });
        transactionsFail(cancelled("TransactionConflict"));
        try {
            assertThatThrownBy(() -> interrupted.replaceAll(ssn, Map.of())).isInstanceOf(TransactionCanceledException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(dynamo, times(1)).transactWriteItems(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void otherFailuresAndEndlessConflictsAreNotRetriedForever() {
        transactionsFail(cancelled("ValidationError", "None", "None"));
        assertThatThrownBy(() -> store.replaceAll(ssn, Map.of())).isInstanceOf(TransactionCanceledException.class);

        org.mockito.Mockito.reset(dynamo);
        TransactionCanceledException conflict = cancelled("TransactionConflict", "None", "None");
        transactionsFail(conflict, conflict, conflict, conflict);
        assertThatThrownBy(() -> store.replaceAll(ssn, Map.of())).isSameAs(conflict);
        verify(dynamo, times(DynamoDbCreditReportStore.MAX_ATTEMPTS)).transactWriteItems(any(Consumer.class));

        org.mockito.Mockito.reset(dynamo);
        transactionsFail(TransactionCanceledException.builder().message("no reasons").build());
        assertThatThrownBy(() -> store.replaceAll(ssn, Map.of())).isInstanceOf(TransactionCanceledException.class);
        verify(dynamo, times(1)).transactWriteItems(any(Consumer.class));
    }

    @Test
    void anOversizedReportIsRefusedBeforeAnythingIsWritten() {
        String huge = "x".repeat(CreditReportStore.MAX_REPORT_BYTES + 1);
        assertThatThrownBy(() -> store.replaceAll(ssn, Map.of(Bureau.EXPERIAN, huge)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Experian");
        org.mockito.Mockito.verifyNoInteractions(dynamo);
    }

    @Test
    void propertiesHaveDefaultsAndNeverPrintTheKeys() {
        CreditReportsProperties defaults = new CreditReportsProperties("secret-1", "secret-2", java.util.Arrays.asList("x", " ", null),
                null, null, null, null, null);
        assertThat(defaults.table()).isEqualTo("CreditReports");
        assertThat(defaults.previousEncryptionKeys()).containsExactly("x");
        assertThat(defaults.createTables()).isFalse();
        assertThat(defaults.toString()).doesNotContain("secret-1").doesNotContain("secret-2");
        assertThat(new CreditReportsProperties(null, null, null, null, null, null, null, null).previousEncryptionKeys()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgeFollowsPagesStopsAtMaxAndHandsUnprocessedDeletesBack() {
        java.util.function.Function<String, Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue>> row =
                id -> Map.of("ssn", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(id),
                        "bureau", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("Equifax"));
        when(dynamo.scan(any(software.amazon.awssdk.services.dynamodb.model.ScanRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.ScanResponse.builder()
                        .items(row.apply("a"), row.apply("b")).lastEvaluatedKey(row.apply("b")).build())
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.ScanResponse.builder()
                        .items(row.apply("c"), row.apply("d")).build());
        var unprocessed = Map.of("CreditReports", java.util.List.of(
                software.amazon.awssdk.services.dynamodb.model.WriteRequest.builder().deleteRequest(d -> d.key(row.apply("a"))).build()));
        when(dynamo.batchWriteItem(any(Consumer.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse.builder().unprocessedItems(unprocessed).build())
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse.builder().build());

        assertThat(store.purgeOlderThan(Instant.parse("2025-01-01T00:00:00Z"), 3)).isEqualTo(3);
        verify(dynamo, times(2)).scan(any(software.amazon.awssdk.services.dynamodb.model.ScanRequest.class));
        verify(dynamo, times(3)).batchWriteItem(any(Consumer.class)); // page 1, its retry, page 2 (one row)
        assertThat(pauses).hasSize(1); // the retry waited (full jitter)
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowsStillUnprocessedAfterRetriesAreNotCountedAsDeleted() {
        var row = Map.of("ssn", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("a"),
                "bureau", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("Equifax"));
        when(dynamo.scan(any(software.amazon.awssdk.services.dynamodb.model.ScanRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.ScanResponse.builder().items(row).build());
        var stuck = Map.of("CreditReports", java.util.List.of(
                software.amazon.awssdk.services.dynamodb.model.WriteRequest.builder().deleteRequest(d -> d.key(row)).build()));
        when(dynamo.batchWriteItem(any(Consumer.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse.builder().unprocessedItems(stuck).build());
        assertThat(store.purgeOlderThan(Instant.parse("2025-01-01T00:00:00Z"), 10)).isZero();
        verify(dynamo, times(3)).batchWriteItem(any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void eraseDeletesEveryBureauInOneTransaction() {
        when(dynamo.transactWriteItems(any(Consumer.class))).thenReturn(TransactWriteItemsResponse.builder().build());
        store.eraseAll(ssn);
        verify(dynamo).transactWriteItems(any(Consumer.class));
        assertThat(store.reference(ssn)).hasSize(16).isEqualTo(new SsnHmac(KEY, "k").hex(ssn).substring(0, 16));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReadIsMarkedAtMostDailyAndAFailedMarkIsHarmless() {
        String hex = new SsnHmac(KEY, "k").hex(ssn);
        ReportCipher.Sealed sealed = new ReportCipher(ReportCipherTest.NEW, null)
                .encrypt("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), DynamoDbCreditReportStore.associatedData(hex, Bureau.EQUIFAX));
        java.util.function.Function<String, software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse> answer = lastRead -> {
            Map<String, software.amazon.awssdk.services.dynamodb.model.AttributeValue> item = new java.util.HashMap<>(Map.of(
                    "ssn", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(hex),
                    "bureau", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS("Equifax"),
                    "keyId", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(sealed.keyId()),
                    "report", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromB(software.amazon.awssdk.core.SdkBytes.fromByteArray(sealed.bytes()))));
            if (lastRead != null) {
                item.put("lastReadAt", software.amazon.awssdk.services.dynamodb.model.AttributeValue.fromS(lastRead));
            }
            return software.amazon.awssdk.services.dynamodb.model.TransactGetItemsResponse.builder()
                    .responses(software.amazon.awssdk.services.dynamodb.model.ItemResponse.builder().item(item).build()).build();
        };
        when(dynamo.transactGetItems(any(Consumer.class)))
                .thenReturn(answer.apply(null))                         // never marked: mark it
                .thenReturn(answer.apply(Instant.EPOCH.toString()))     // marked today (the clock is the epoch): leave it
                .thenReturn(answer.apply("1969-12-30T00:00:00Z"));      // marked two days ago: mark again
        when(dynamo.updateItem(any(Consumer.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse.builder().build())
                .thenThrow(software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException.builder().build());

        assertThat(store.findAll(ssn)).containsEntry(Bureau.EQUIFAX, "{}");
        assertThat(store.findAll(ssn)).containsEntry(Bureau.EQUIFAX, "{}");
        assertThat(store.findAll(ssn)).containsEntry(Bureau.EQUIFAX, "{}"); // the mark fails (erased meanwhile): still read
        verify(dynamo, times(2)).updateItem(any(Consumer.class));
    }
}
