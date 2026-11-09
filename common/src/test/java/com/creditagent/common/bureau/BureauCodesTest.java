package com.creditagent.common.bureau;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.creditagent.common.AccountType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BureauCodesTest {

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void everyAccountTypeRoundTripsThroughMetro2AndTransUnionCodes(AccountType type) {
        assertThat(BureauCodes.fromMetro2AccountType(BureauCodes.metro2AccountType(type))).isEqualTo(type);
        assertThat(BureauCodes.fromTransUnionIndustryCode(BureauCodes.transUnionIndustryCode(type))).isEqualTo(type);
    }

    @Test
    void codesMatchTheBureausPublishedValues() {
        assertThat(BureauCodes.metro2AccountType(AccountType.CREDIT_CARD)).isEqualTo("18");
        assertThat(BureauCodes.metro2AccountType(AccountType.MORTGAGE)).isEqualTo("26");
        assertThat(BureauCodes.transUnionIndustryCode(AccountType.AUTO_LOAN)).isEqualTo("FA");
        assertThat(BureauCodes.portfolioLetter(AccountType.CREDIT_CARD)).isEqualTo("R");
        assertThat(BureauCodes.portfolioLetter(AccountType.MORTGAGE)).isEqualTo("M");
        assertThat(BureauCodes.portfolioLetter(AccountType.STUDENT_LOAN)).isEqualTo("I");
        assertThat(BureauCodes.transUnionPortfolioType(AccountType.CREDIT_CARD)).isEqualTo("revolving");
        assertThat(BureauCodes.transUnionPortfolioType(AccountType.MORTGAGE)).isEqualTo("mortgage");
        assertThat(BureauCodes.transUnionPortfolioType(AccountType.PERSONAL_LOAN)).isEqualTo("installment");
    }

    @Test
    void codesAreReadLeniently() {
        assertThat(BureauCodes.fromMetro2AccountType(" 18 ")).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(BureauCodes.fromTransUnionIndustryCode("bc")).isEqualTo(AccountType.CREDIT_CARD);
    }

    @Test
    void unknownCodesAreRefused() {
        assertThatThrownBy(() -> BureauCodes.fromMetro2AccountType("99")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BureauCodes.fromMetro2AccountType(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BureauCodes.fromTransUnionIndustryCode("ZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BureauCodes.fromTransUnionIndustryCode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lastFourTakesTheFinalFourDigits() {
        assertThat(BureauCodes.lastFour("XXXXXXXX1234")).isEqualTo("1234");
        assertThat(BureauCodes.lastFour("***-**-6789")).isEqualTo("6789");
        assertThat(BureauCodes.lastFour("1234-5678 ref")).isEqualTo("5678");
        assertThat(BureauCodes.lastFour("no digits")).isEmpty();
        assertThat(BureauCodes.lastFour(null)).isEmpty();
    }

    @Test
    void dateFormatsAreEachBureausOwn() {
        LocalDate date = LocalDate.of(2026, 11, 7);
        assertThat(date.format(BureauCodes.EQUIFAX_DATE)).isEqualTo("11/07/2026");
        assertThat(java.time.YearMonth.from(date).format(BureauCodes.EQUIFAX_MONTH)).isEqualTo("11/2026");
        assertThat(date.format(BureauCodes.EXPERIAN_DATE)).isEqualTo("20261107");
        assertThat(date.format(BureauCodes.TRANSUNION_DATE)).isEqualTo("2026-11-07");
    }

    @Test
    void bureausNameTheirDirectories() {
        assertThat(Bureau.EQUIFAX.directoryName()).isEqualTo("Equifax");
        assertThat(Bureau.EXPERIAN.directoryName()).isEqualTo("Experian");
        assertThat(Bureau.TRANSUNION.directoryName()).isEqualTo("TransUnion");
        assertThat(Bureau.valueOf("EXPERIAN")).isEqualTo(Bureau.EXPERIAN);
    }
}
