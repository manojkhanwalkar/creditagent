package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

/** The generation pipeline as the app wires it: configuration, service, store and seed runner. */
class GenerationServiceTest {

    final GeneratorConfiguration configuration = new GeneratorConfiguration();
    final JsonMapper json = JsonMapper.builder().build();
    final InMemoryCreditReportStore store = new InMemoryCreditReportStore();

    CreditReportService service(CreditReportStore reports) {
        return new CreditReportService(configuration.creditReportFactory(), configuration.bureauReportFormatter(), reports, json);
    }

    @Test
    void generatingStoresOneReportPerBureauThatHasOneAsJson() {
        Ssn ssn = Ssn.parse("123-45-6789");
        CreditReportService.GeneratedReports generated = service(store).generate(ssn, "Avery Patel");

        assertThat(generated.bureaus()).isNotEmpty();
        assertThat(generated.report().ssnMasked()).isEqualTo("***-**-6789");
        assertThat(generated.reportId()).matches("[0-9a-f-]{36}");
        Map<Bureau, String> stored = store.findAll(ssn);
        assertThat(stored.keySet()).containsExactlyInAnyOrderElementsOf(generated.bureaus());
        stored.values().forEach(report -> assertThat(json.readTree(report).isObject()).isTrue());
    }

    @Test
    void aStoreFailureReachesTheCaller() {
        CreditReportStore broken = mock(CreditReportStore.class);
        org.mockito.Mockito.doThrow(software.amazon.awssdk.core.exception.SdkClientException.create("down"))
                .when(broken).replaceAll(any(), any());
        assertThatThrownBy(() -> service(broken).generate(Ssn.parse("123-45-6789"), null))
                .isInstanceOf(software.amazon.awssdk.core.exception.SdkException.class);
    }

    @Test
    void seedingGeneratesEachValidSsnAndSkipsInvalidOnes() {
        CreditReportService service = mock(CreditReportService.class);
        new SeedDataRunner(new GeneratorProperties(List.of("123-45-6789", "000-00-0000", "234567890")), service)
                .run(new DefaultApplicationArguments());
        verify(service).generate(Ssn.parse("123-45-6789"), null);
        verify(service).generate(Ssn.parse("234-56-7890"), null);
        verify(service, times(2)).generate(any(), any());
    }

    @Test
    void noSeedsMeansNoGeneration() {
        CreditReportService service = mock(CreditReportService.class);
        new SeedDataRunner(new GeneratorProperties(null), service).run(new DefaultApplicationArguments());
        verify(service, never()).generate(any(), any());
    }

    @Test
    void ssnsAreNeverPrinted() {
        assertThat(new GeneratorProperties(List.of("123-45-6789")).toString()).doesNotContain("6789").contains("seedSsns=1");
        assertThat(new ReportController.GenerateReportRequest("123-45-6789", "Avery").toString()).doesNotContain("6789");
        assertThat(configuration.requestSizeLimitFilter().getUrlPatterns()).containsExactly("/api/*");
    }
}
