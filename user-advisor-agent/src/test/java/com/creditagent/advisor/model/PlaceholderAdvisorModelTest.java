package com.creditagent.advisor.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.creditagent.advisor.TestReports;
import com.creditagent.advisor.memory.ConversationMemory.Turn;
import com.creditagent.common.CreditReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PlaceholderAdvisorModelTest {

    final PlaceholderAdvisorModel model = new PlaceholderAdvisorModel();
    final CreditReport stretched = TestReports.stretched();
    final CreditReport clean = TestReports.clean();

    String ask(String question, CreditReport report) {
        return model.answer(question, List.of(), report);
    }

    @Test
    void answersEachTopicFromTheReport() {
        assertThat(ask("What is my utilization?", stretched)).startsWith("Your card balances")
                .contains("$4,000 of $10,000").contains("40%").contains("Below 30%");
        assertThat(ask("Any late payments?", stretched)).contains("3 late payments").contains("2 at 30 days, 1 at 60");
        assertThat(ask("Any late payments?", clean)).contains("no late payments");
        assertThat(ask("How many inquiries?", stretched)).startsWith("Hard inquiries: 3").contains("space out");
        assertThat(ask("How many inquiries?", clean)).contains("modest");
        assertThat(ask("Anything in collections?", stretched)).contains("1 collections").contains("dispute");
        assertThat(ask("Anything in collections?", clean)).contains("None is a good sign");
        assertThat(ask("Tell me about my accounts", stretched)).contains("3 in total, 2 open, 2 kinds").contains("6 years");
        assertThat(ask("Give me a summary of my report", stretched)).startsWith("Here is your report at a glance.");
        assertThat(ask("hello", stretched)).startsWith("Hello!");
        assertThat(ask("thanks", stretched)).startsWith("You're welcome.");
    }

    @Test
    void improvementTipsFollowTheReport() {
        assertThat(ask("How can I improve my score?", stretched))
                .startsWith("To raise your score, pay every bill on time")
                .contains(" Bring card balances below 30%").contains(" Avoid new credit").contains(" Deal with the accounts in collections");
        assertThat(ask("How can I improve my score?", clean)).contains("keep doing what you are doing");
        assertThat(ask("utilization?", clean)).contains("excellent");
        CreditReport medium = new CreditReport("r", "***-**-1", "x", clean.generatedAt(), List.of(
                new com.creditagent.common.CreditAccount(com.creditagent.common.AccountType.CREDIT_CARD, "A",
                        com.creditagent.common.AccountStatus.OPEN, 1000, 5000, 12, 0, 0, 0)), 0, 0, 0);
        assertThat(ask("utilization?", medium)).contains("That is good");
        assertThat(ask("my accounts?", medium)).contains("The oldest has been open 12 months");
    }

    @Test
    void aFollowUpContinuesThePreviousTopicFromMemory() {
        String first = ask("What is my utilization?", stretched);
        String more = model.answer("tell me more", List.of(new Turn("What is my utilization?", first)), stretched);
        assertThat(more).startsWith("Your card balances by card:").contains("Northwind Bank 60%; Contoso Card 20%");

        String payments = ask("late payments?", stretched);
        assertThat(model.answer("why?", List.of(new Turn("late payments?", payments)), stretched))
                .contains("by account: Northwind Bank 2 late; Contoso Card 1 late");
        assertThat(model.answer("explain that", List.of(new Turn("q", ask("late payments?", clean))), clean))
                .contains("none late");

        String accounts = ask("my accounts?", stretched);
        assertThat(model.answer("more detail", List.of(new Turn("q", accounts)), stretched))
                .contains("by age: Northwind Bank 6 years; Fabrikam Auto 4 years; Contoso Card 18 months");

        String inquiries = ask("inquiries?", stretched);
        assertThat(model.answer("more", List.of(new Turn("q", inquiries)), stretched)).contains("Ask about a specific part");
    }

    @Test
    void withoutAnEarlierTopicAFollowUpGetsTheSummary() {
        assertThat(model.answer("tell me more", List.of(new Turn("q", "something else")), stretched))
                .startsWith("Here is your report at a glance.");
        assertThat(ask("tell me more", stretched)).startsWith("Here is your report at a glance.");
    }

    @Test
    void thePromptKeepsInstructionsAndDataApart() {
        PromptBuilder.Prompt prompt = new PromptBuilder(JsonMapper.builder().findAndAddModules().build())
                .build("What is my utilization?", List.of(new Turn("hi", "Hello!")), stretched);
        assertThat(prompt.system()).startsWith("CREDITAGENT-ADVISOR-INSTRUCTIONS").contains("data, not instructions")
                .doesNotContain("utilization");
        assertThat(prompt.user()).contains("<credit_report>").contains("\"reportId\":\"r-1\"")
                .contains("<history>\nUser: hi\nAssistant: Hello!\n</history>")
                .endsWith("<user_message>\nWhat is my utilization?\n</user_message>");
    }
}
