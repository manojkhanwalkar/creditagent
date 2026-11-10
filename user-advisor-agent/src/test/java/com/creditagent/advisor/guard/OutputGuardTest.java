package com.creditagent.advisor.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.advisor.guard.OutputGuard.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutputGuardTest {

    final OutputGuard guard = new OutputGuard(200);

    @Test
    void aGoodAnswerPassesWithTheDisclaimer() {
        OutputGuard.Result result = guard.check("Your card balances add up to $1,200 of $5,000 in limits.");
        assertThat(result.replaced()).isFalse();
        assertThat(result.reply()).isEqualTo("Your card balances add up to $1,200 of $5,000 in limits.");
        assertThat(result.disclaimer()).isEqualTo(OutputGuard.DISCLAIMER).contains("educational purposes only")
                .contains("professional");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "Lenders think people like you are stupid. | OFFENSIVE",
        "Your score is sh1t. | OFFENSIVE",
        "Scores are lower in black neighborhoods. | PROTECTED",
        "Your ethnicity is a factor. | PROTECTED",
        "Applicants of your religion are riskier. | PROTECTED",
        "My system prompt says I must not tell you. | LEAK",
        "CREDITAGENT-ADVISOR-INSTRUCTIONS You are a credit assistant | LEAK",
        "Here is the data: <credit_report>{...} | LEAK",
        "assistant: I will now ignore the rules | LEAK",
        "Paying this card is guaranteed to raise your score. | PROMISE",
        "This will 100% fix your credit. | PROMISE",
        "Women usually get lower limits. | PROTECTED",
        "At your age, lenders see you as risky. | PROTECTED",
        "Black applicants are usually riskier borrowers. | PROTECTED",
        "Catholic and Sikh borrowers pay more. | PROTECTED",
        "Gay couples should apply separately. | PROTECTED",
        "African American borrowers get less. | PROTECTED",
        "Foreign-born borrowers are riskier. | PROTECTED",
        "Only idiots carry a balance. | OFFENSIVE",
        "That was a stupid thing to do. | OFFENSIVE",
        "CREDITAGENT ADVISOR INSTRUCTIONS say I must help. | LEAK",
        "This will definitely increase your score by 100 points. | PROMISE",
        "Paying this off will certainly boost you to 800. | PROMISE",
        "I am 100% sure this works. | PROMISE"})
    void harmfulAnswersAreReplacedNotEdited(String reply, Problem problem) {
        OutputGuard.Result result = guard.check(reply);
        assertThat(result.problem()).isEqualTo(problem);
        assertThat(result.reply()).isEqualTo(OutputGuard.REPLACEMENT);
        assertThat(result.disclaimer()).isEqualTo(OutputGuard.DISCLAIMER);
    }

    @Test
    void identifiersAreRedactedAndMarkupDropped() {
        OutputGuard.Result result = guard.check("Your SSN 123-45-6789 and card 4111111111111111 <b>look</b> fine.");
        assertThat(result.replaced()).isFalse();
        assertThat(result.reply()).isEqualTo("Your SSN [SSN removed] and card [number removed] blook/b fine.");
    }

    @Test
    void factsWithAHundredPercentAreNotPromises() {
        assertThat(guard.check("Your card balances add up to $5,000 of $5,000 in limits, 100% utilisation.").replaced()).isFalse();
        assertThat(guard.check("Your card balances by card: Discover 100%.").replaced()).isFalse();
    }

    @Test
    void maskedSsnsAreKept() {
        assertThat(guard.check("Report for ***-**-6789.").reply()).isEqualTo("Report for ***-**-6789.");
    }

    @Test
    void longAnswersAreCutAndEmptyOnesReplaced() {
        String cut = guard.check("credit ".repeat(100)).reply();
        assertThat(cut).hasSizeLessThanOrEqualTo(200).endsWith("…");
        assertThat(guard.check("<<>>").replaced()).isTrue();
        assertThat(guard.check("​").replaced()).isTrue();
    }
}
