package com.creditagent.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.Ssn;
import com.creditagent.common.SsnHmac;
import com.creditagent.common.bureau.Bureau;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** The real table on DynamoDB Local: creation, the composite key, encryption at rest, replacing a set. */
@Testcontainers(disabledWithoutDocker = true)
class CreditReportsTableIntegrationTest {

    static final String HASH_KEY = "hash-key-hash-key-hash-key-hash-key!";

    @Container
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb")
            .withExposedPorts(8000);

    static DynamoDbClient dynamo;

    @BeforeAll
    static void connect() {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        dynamo = CreditReportsTable.client("http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000), "us-east-1",
                Duration.ofSeconds(10));
        CreditReportsTable.ensure(dynamo, "CreditReports");
        CreditReportsTable.ensure(dynamo, "CreditReports"); // a second start finds it there
    }

    @AfterAll
    static void close() {
        if (dynamo != null) {
            dynamo.close();
        }
    }

    static DynamoDbCreditReportStore store(ReportCipher cipher) {
        return new DynamoDbCreditReportStore(dynamo, "CreditReports", new SsnHmac(HASH_KEY, "k"), cipher, Clock.systemUTC());
    }

    @Test
    void reportsAreStoredEncryptedUnderTheSsnHashAndBureauAndReplacedAsASet() {
        DynamoDbCreditReportStore store = store(new ReportCipher(ReportCipherTest.NEW, List.of()));
        Ssn ada = Ssn.parse("123-45-6789");
        store.replaceAll(ada, Map.of(Bureau.EQUIFAX, "{\"name\":\"Ada\",\"ssnLast4\":\"6789\"}", Bureau.TRANSUNION, "{\"t\":1}"));
        assertThat(store.findAll(ada)).containsOnlyKeys(Bureau.EQUIFAX, Bureau.TRANSUNION)
                .containsEntry(Bureau.EQUIFAX, "{\"name\":\"Ada\",\"ssnLast4\":\"6789\"}");

        // At rest: the key is the SSN's keyed hash, the bureau's name, and ciphertext; no SSN, no report text.
        var items = dynamo.scan(r -> r.tableName("CreditReports")).items();
        assertThat(items).extracting(item -> item.get("bureau").s()).contains("Equifax", "TransUnion");
        String hash = new SsnHmac(HASH_KEY, "k").hex(ada);
        for (Map<String, AttributeValue> item : items) {
            String everything = item.toString() + new String(item.get("report").b().asByteArray(), StandardCharsets.ISO_8859_1);
            assertThat(everything).doesNotContain("123456789").doesNotContain("123-45-6789").doesNotContain("Ada")
                    .doesNotContain("ssnLast4");
            assertThat(item.get("ssn").s()).isEqualTo(hash);
        }

        // A new set replaces the old: TransUnion is gone, Experian is new.
        store.replaceAll(ada, Map.of(Bureau.EQUIFAX, "{\"v\":2}", Bureau.EXPERIAN, "{\"x\":1}"));
        assertThat(store.findAll(ada)).containsExactlyInAnyOrderEntriesOf(Map.of(Bureau.EQUIFAX, "{\"v\":2}", Bureau.EXPERIAN, "{\"x\":1}"));
        assertThat(store.findAll(Ssn.parse("234-56-7890"))).isEmpty();
    }

    @Test
    void aReportCopiedIntoAnotherConsumersRowIsRefusedAndRotationKeepsOldRowsReadable() {
        DynamoDbCreditReportStore oldStore = store(new ReportCipher(ReportCipherTest.OLD, List.of()));
        Ssn victim = Ssn.parse("345-67-8901");
        Ssn attacker = Ssn.parse("456-78-9012");
        oldStore.replaceAll(victim, Map.of(Bureau.EXPERIAN, "{\"victim\":true}"));

        DynamoDbCreditReportStore rotated = store(new ReportCipher(ReportCipherTest.NEW, List.of(ReportCipherTest.OLD)));
        assertThat(rotated.findAll(victim)).containsEntry(Bureau.EXPERIAN, "{\"victim\":true}");

        // Someone with write access copies the victim's ciphertext into the attacker's row.
        Map<String, AttributeValue> stolen = dynamo.getItem(r -> r.tableName("CreditReports").key(Map.of(
                "ssn", AttributeValue.fromS(new SsnHmac(HASH_KEY, "k").hex(victim)), "bureau", AttributeValue.fromS("Experian")))).item();
        dynamo.putItem(r -> r.tableName("CreditReports").item(Map.of(
                "ssn", AttributeValue.fromS(new SsnHmac(HASH_KEY, "k").hex(attacker)),
                "bureau", AttributeValue.fromS("Experian"),
                "report", AttributeValue.fromB(SdkBytes.fromByteArray(stolen.get("report").b().asByteArray())),
                "keyId", stolen.get("keyId"))));
        assertThatThrownBy(() -> rotated.findAll(attacker)).isInstanceOf(ReportCipher.DecryptionException.class);

        // A row for a bureau we do not know is ignored, not guessed at.
        dynamo.putItem(r -> r.tableName("CreditReports").item(Map.of(
                "ssn", AttributeValue.fromS(new SsnHmac(HASH_KEY, "k").hex(victim)), "bureau", AttributeValue.fromS("Innovis"),
                "report", AttributeValue.fromB(SdkBytes.fromUtf8String("x")), "keyId", AttributeValue.fromS("x"))));
        assertThat(rotated.findAll(victim)).containsOnlyKeys(Bureau.EXPERIAN);
    }
}
