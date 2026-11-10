package com.creditagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.web.auth.AuthExceptions.InvalidRegistrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RegistrationPolicyTest {

    @Test
    void userIdsAreLowerCasedAndChecked() {
        assertThat(RegistrationPolicy.normaliseUserId("  Avery.Patel ")).isEqualTo("avery.patel");
        for (String bad : new String[] {"ab", "-avery", "avery patel", "a".repeat(33), "avery@x", ""}) {
            assertThatThrownBy(() -> RegistrationPolicy.normaliseUserId(bad)).as(bad)
                    .isInstanceOf(InvalidRegistrationException.class);
        }
    }

    @Test
    void acceptsALongPassphrase() {
        RegistrationPolicy.checkPassword("correct horse battery staple", "avery");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "short1!",              // under 12
        "Password1234",         // block list (case-insensitive)
        "aaaaaaaaaaaaaaab",     // fewer than 4 distinct characters
        "my-avery-password",    // contains the user id
    })
    void refusesWeakPasswords(String password) {
        assertThatThrownBy(() -> RegistrationPolicy.checkPassword(password, "avery"))
                .isInstanceOf(InvalidRegistrationException.class)
                .hasMessageNotContaining(password);
    }

    @Test
    void refusesPasswordsBcryptWouldTruncate() {
        assertThatThrownBy(() -> RegistrationPolicy.checkPassword("x".repeat(65), "avery"))
                .isInstanceOf(InvalidRegistrationException.class);
        // 17 emoji (4 bytes each) plus 8 letters: 42 Java chars, but 76 UTF-8 bytes.
        assertThatThrownBy(() -> RegistrationPolicy.checkPassword("😀".repeat(17) + "abcdefgh", "avery"))
                .isInstanceOf(InvalidRegistrationException.class);
    }

    @Test
    void contactDetails() {
        assertThat(RegistrationPolicy.normaliseEmail(" avery@example.com ")).isEqualTo("avery@example.com");
        assertThat(RegistrationPolicy.normalisePhone("+1 (415) 555-0100")).isEqualTo("+14155550100");
        assertThatThrownBy(() -> RegistrationPolicy.normaliseEmail("not-an-email"))
                .isInstanceOf(InvalidRegistrationException.class);
        assertThatThrownBy(() -> RegistrationPolicy.normalisePhone("415 555 0100"))
                .isInstanceOf(InvalidRegistrationException.class);
    }

    @org.junit.jupiter.api.Test
    void zipCodesAreFiveDigitsOrZipPlusFour() {
        org.assertj.core.api.Assertions.assertThat(RegistrationPolicy.normaliseZip(" 98101 ")).isEqualTo("98101");
        org.assertj.core.api.Assertions.assertThat(RegistrationPolicy.normaliseZip("98101-1234")).isEqualTo("98101");
        for (String bad : new String[] {null, "", "9810", "981011", "ABCDE", "98101-12"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> RegistrationPolicy.normaliseZip(bad))
                    .isInstanceOf(AuthExceptions.InvalidRegistrationException.class);
        }
    }
}
