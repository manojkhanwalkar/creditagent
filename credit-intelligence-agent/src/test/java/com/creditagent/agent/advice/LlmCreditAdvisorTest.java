package com.creditagent.agent.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.agent.TestReports;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Recommendation.Impact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LlmCreditAdvisorTest {

    final JsonMapper json = JsonMapper.builder().build();
    final RuleBasedCreditAdvisor rules = new RuleBasedCreditAdvisor();

    @Test
    void sendsOnlyDeidentifiedDataToTheModel() {
        AtomicReference<String> seen = new AtomicReference<>();
        LlmCreditAdvisor advisor = new LlmCreditAdvisor(
                (score, band, report) -> {
                    seen.set(report);
                    return new LlmCreditAdvisor.AdviceDraft(
                            "Summary", List.of(new LlmCreditAdvisor.Item("Pay down cards", "Detail", Impact.HIGH)));
                },
                json,
                rules);

        CreditAdvice advice = advisor.advise(TestReports.troubled(), CreditScore.of(540));

        assertThat(seen.get())
                .doesNotContain("Avery")
                .doesNotContain("Patel")
                .doesNotContain("6789")
                .doesNotContain("r-test")
                .contains("Contoso Card");
        assertThat(advice.source()).isEqualTo("llm");
        assertThat(advice.recommendations()).singleElement().satisfies(r -> {
            assertThat(r.priority()).isEqualTo(1);
            assertThat(r.impact()).isEqualTo(Impact.HIGH);
        });
    }

    @Test
    void modelFailureFallsBackToRules() {
        LlmCreditAdvisor advisor = new LlmCreditAdvisor(
                (score, band, report) -> {
                    throw new RuntimeException("rate limited");
                },
                json,
                rules);
        assertThat(advisor.advise(TestReports.troubled(), CreditScore.of(540)).source()).isEqualTo("rules");
    }

    @Test
    void emptyAnswerFallsBackToRules() {
        LlmCreditAdvisor advisor = new LlmCreditAdvisor((score, band, report) -> new LlmCreditAdvisor.AdviceDraft("x", List.of()), json, rules);
        assertThat(advisor.advise(TestReports.troubled(), CreditScore.of(540)).source()).isEqualTo("rules");
    }

    @Test
    void outputIsCappedAndSanitised() {
        List<LlmCreditAdvisor.Item> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new LlmCreditAdvisor.Item("Title " + i, "y".repeat(5_000), null));
        }
        many.add(1, new LlmCreditAdvisor.Item(" ", "blank title is dropped", Impact.LOW));
        many.add(2, null);
        LlmCreditAdvisor advisor = new LlmCreditAdvisor(
                (score, band, report) -> new LlmCreditAdvisor.AdviceDraft("s", Collections.unmodifiableList(many)), json, rules);

        CreditAdvice advice = advisor.advise(TestReports.troubled(), CreditScore.of(540));

        assertThat(advice.recommendations()).hasSize(LlmCreditAdvisor.MAX_RECOMMENDATIONS);
        assertThat(advice.recommendations()).allSatisfy(r -> {
            assertThat(r.detail().length()).isLessThanOrEqualTo(LlmCreditAdvisor.MAX_TEXT + 1);
            assertThat(r.impact()).isEqualTo(Impact.MEDIUM);
        });
    }
}
