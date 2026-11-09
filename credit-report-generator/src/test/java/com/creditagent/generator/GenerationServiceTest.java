package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import tools.jackson.databind.json.JsonMapper;

/** The generation pipeline as the app wires it: configuration, service, writer and seed runner. */
class GenerationServiceTest {

    static final String KEY = "generator-test-key-".repeat(2);

    @TempDir
    Path dir;

    final GeneratorConfiguration configuration = new GeneratorConfiguration();
    final JsonMapper json = JsonMapper.builder().build();

    CreditReportService service(Path reports) {
        GeneratorProperties properties = new GeneratorProperties(reports, KEY, null);
        return new CreditReportService(
                configuration.creditReportFactory(),
                configuration.bureauReportFormatter(),
                new CreditFileWriter(properties, configuration.creditFileNames(properties), json));
    }

    @Test
    void generatingWritesOneFilePerBureauThatHasAReport() {
        CreditReportService.GeneratedReports generated = service(dir).generate(Ssn.parse("123-45-6789"), "Avery Patel");

        assertThat(generated.bureaus()).isNotEmpty();
        assertThat(generated.report().ssnMasked()).isEqualTo("***-**-6789");
        assertThat(generated.reportId()).matches("[0-9a-f-]{36}");
        String fileName = new CreditFileNames(KEY).fileNameFor(Ssn.parse("123-45-6789"));
        for (Bureau bureau : generated.bureaus()) {
            assertThat(dir.resolve(bureau.directoryName()).resolve(fileName)).isRegularFile();
        }
    }

    @Test
    void aReportsDirectoryThatCannotBeCreatedFailsLoudly() throws Exception {
        Path blocker = Files.writeString(dir.resolve("not-a-directory"), "x");
        assertThatThrownBy(() -> service(blocker).generate(Ssn.parse("123-45-6789"), null))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageNotContaining("6789");
    }

    @Test
    void seedingGeneratesEachValidSsnAndSkipsInvalidOnes() {
        CreditReportService service = mock(CreditReportService.class);
        new SeedDataRunner(new GeneratorProperties(dir, KEY, List.of("123-45-6789", "000-00-0000", "234567890")), service)
                .run(new DefaultApplicationArguments());
        verify(service).generate(Ssn.parse("123-45-6789"), null);
        verify(service).generate(Ssn.parse("234-56-7890"), null);
        verify(service, times(2)).generate(any(), any());
    }

    @Test
    void noSeedsMeansNoGeneration() {
        CreditReportService service = mock(CreditReportService.class);
        new SeedDataRunner(new GeneratorProperties(dir, KEY, null), service).run(new DefaultApplicationArguments());
        verify(service, never()).generate(any(), any());
    }

    @Test
    void secretsAndSsnsAreNeverPrinted() {
        assertThat(new GeneratorProperties(dir, KEY, List.of("123-45-6789")).toString())
                .doesNotContain(KEY).doesNotContain("6789").contains("seedSsns=1");
        assertThat(new ReportController.GenerateReportRequest("123-45-6789", "Avery").toString()).doesNotContain("6789");
        assertThat(configuration.requestSizeLimitFilter().getUrlPatterns()).containsExactly("/api/*");
    }
}
