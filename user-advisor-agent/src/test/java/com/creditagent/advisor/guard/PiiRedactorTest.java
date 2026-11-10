package com.creditagent.advisor.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PiiRedactorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "ssn 123-45-6789 | ssn [SSN removed]",
        "ssn 123456789 | ssn [SSN removed]",
        "ssn 123–45–6789 | ssn [SSN removed]",
        "ssn 123/45/6789 | ssn [SSN removed]",
        "ssn 123 - 45 - 6789 | ssn [SSN removed]",
        "ssn ١٢٣-٤٥-٦٧٨٩ | ssn [SSN removed]",
        "call (555)123-4567 | call [phone removed]",
        "call 5551234567 | call [phone removed]",
        "call +44 20 7946 0958 | call [phone removed]",
        "account 01234567 | account [number removed]",
        "card 4111.1111.1111.1111 | card [number removed]",
        "card 4111-1111-1111-1111 | card [number removed]",
        "mail a.b@example.co.uk | mail [email removed]",
        "masked ***-**-6789 stays | masked ***-**-6789 stays",
        "pay 2/3 of $4,000 by 2026-12-01 | pay 2/3 of $4,000 by 2026-12-01"})
    void redactsIdentifiersInTheirCommonForms(String text, String expected) {
        assertThat(PiiRedactor.redact(text)).isEqualTo(expected);
    }
}
