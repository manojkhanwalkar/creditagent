package com.creditagent.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportCipherTest {

    static final String OLD = "old-key-old-key-old-key-old-key-old-key!";
    static final String NEW = "new-key-new-key-new-key-new-key-new-key!";
    static final byte[] REPORT = "{\"score\":712}".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsWithAFreshNonceEachTime() {
        ReportCipher cipher = new ReportCipher(NEW, List.of());
        ReportCipher.Sealed a = cipher.encrypt(REPORT, "row-1");
        ReportCipher.Sealed b = cipher.encrypt(REPORT, "row-1");
        assertThat(a.bytes()).isNotEqualTo(b.bytes());
        assertThat(new String(a.bytes(), StandardCharsets.ISO_8859_1)).doesNotContain("score");
        assertThat(cipher.decrypt(a, "row-1")).isEqualTo(REPORT);
        assertThat(a.keyId()).isEqualTo(cipher.currentKeyId()).hasSize(16);
    }

    @Test
    void aReportMovedToAnotherRowOrTamperedWithIsRefused() {
        ReportCipher cipher = new ReportCipher(NEW, List.of());
        ReportCipher.Sealed sealed = cipher.encrypt(REPORT, "CreditReports|aaa|Equifax");
        assertThatThrownBy(() -> cipher.decrypt(sealed, "CreditReports|bbb|Equifax"))
                .isInstanceOf(ReportCipher.DecryptionException.class);
        byte[] flipped = sealed.bytes().clone();
        flipped[flipped.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(new ReportCipher.Sealed(sealed.keyId(), flipped), "CreditReports|aaa|Equifax"))
                .isInstanceOf(ReportCipher.DecryptionException.class);
        assertThatThrownBy(() -> cipher.decrypt(new ReportCipher.Sealed(sealed.keyId(), new byte[5]), "x"))
                .isInstanceOf(ReportCipher.DecryptionException.class);
    }

    @Test
    void aRotatedKeyStillReadsOldReportsAndAnUnknownKeyIsRefused() {
        ReportCipher.Sealed old = new ReportCipher(OLD, null).encrypt(REPORT, "row");
        ReportCipher rotated = new ReportCipher(NEW, List.of(" " + OLD + " ", ""));
        assertThat(rotated.decrypt(old, "row")).isEqualTo(REPORT);
        assertThat(rotated.encrypt(REPORT, "row").keyId()).isNotEqualTo(old.keyId());
        assertThatThrownBy(() -> new ReportCipher(NEW, List.of()).decrypt(old, "row"))
                .isInstanceOf(ReportCipher.DecryptionException.class).hasMessageContaining("unknown key");
    }

    @Test
    void shortOrMissingKeysAreRefused() {
        assertThatThrownBy(() -> new ReportCipher("too-short", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportCipher(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ReportCipher(NEW, List.of("short"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyWithATrailingNewlineStillDecryptsAfterRotation() {
        // Written with a key read from a secret file, newline included.
        ReportCipher.Sealed old = new ReportCipher(OLD + "\n", List.of()).encrypt(REPORT, "row");

        // After rotation the list setting hands the old key back trimmed: it must still be found.
        assertThat(new ReportCipher(NEW, List.of(OLD)).decrypt(old, "row")).isEqualTo(REPORT);
        assertThat(new ReportCipher(NEW, List.of(OLD + "\n")).decrypt(old, "row")).isEqualTo(REPORT);
        // And the other way round: written trimmed, rotated with the newline.
        ReportCipher.Sealed trimmed = new ReportCipher(OLD, List.of()).encrypt(REPORT, "row");
        assertThat(new ReportCipher(NEW, List.of(OLD + "\n")).decrypt(trimmed, "row")).isEqualTo(REPORT);
        // The current key encrypts exactly as given, so existing reports keep their key id.
        assertThat(new ReportCipher(OLD + "\n", List.of()).currentKeyId())
                .isNotEqualTo(new ReportCipher(OLD, List.of()).currentKeyId());
    }
}
