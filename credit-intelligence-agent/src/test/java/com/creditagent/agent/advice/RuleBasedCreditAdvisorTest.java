package com.creditagent.agent.advice;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.agent.TestReports;
import com.creditagent.common.CreditAdvice;
import com.creditagent.common.CreditScore;
import com.creditagent.common.Recommendation;
import com.creditagent.common.Recommendation.Impact;
import com.creditagent.common.ScoreBand;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleBasedCreditAdvisorTest {

    final RuleBasedCreditAdvisor advisor = new RuleBasedCreditAdvisor();

    @Test
    void troubledFileGetsTheHighImpactFixesFirst() {
        CreditAdvice advice = advisor.advise(TestReports.troubled(), CreditScore.of(540));

        List<String> titles = advice.recommendations().stream().map(Recommendation::title).toList();
        assertThat(titles.subList(0, 3)).containsExactly(
                "Make every payment on time", "Resolve collections and public records", "Lower your credit card utilization");
        assertThat(titles).contains(
                "Pay down cards that are near their limit",
                "Pause new credit applications",
                "Keep your oldest accounts open",
                "Build a mix of credit types");
        assertThat(advice.recommendations().getFirst().impact()).isEqualTo(Impact.HIGH);
    }

    @Test
    void prioritiesAreSequentialFromOne() {
        List<Recommendation> recs = advisor.advise(TestReports.troubled(), CreditScore.of(540)).recommendations();
        for (int i = 0; i < recs.size(); i++) {
            assertThat(recs.get(i).priority()).isEqualTo(i + 1);
        }
    }

    @Test
    void utilizationAdviceQuotesHowMuchToPayDown() {
        // 4,750 of 5,000 used; 30% target is 1,500, so pay down 3,250.
        Recommendation utilization = advisor.advise(TestReports.troubled(), CreditScore.of(540)).recommendations().stream()
                .filter(r -> r.title().equals("Lower your credit card utilization"))
                .findFirst()
                .orElseThrow();
        assertThat(utilization.detail()).contains("95%").contains("$3250");
    }

    @Test
    void cleanFileGetsReassurance() {
        CreditAdvice advice = advisor.advise(TestReports.clean(), CreditScore.of(812));
        assertThat(advice.recommendations()).singleElement()
                .satisfies(r -> assertThat(r.title()).isEqualTo("Keep doing what you are doing"));
        assertThat(advice.band()).isEqualTo(ScoreBand.GREEN);
    }

    @Test
    void adviceCarriesScoreMaskedSsnAndSource() {
        CreditAdvice advice = advisor.advise(TestReports.troubled(), CreditScore.of(540));
        assertThat(advice.score()).isEqualTo(540);
        assertThat(advice.band()).isEqualTo(ScoreBand.RED);
        assertThat(advice.ssnMasked()).isEqualTo("***-**-6789");
        assertThat(advice.source()).isEqualTo("rules");
        assertThat(advice.summary()).startsWith("Score 540 (RED)");
    }
}
