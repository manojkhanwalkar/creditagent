package com.creditagent.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CreditFileWriterTest {

    static final String KEY = "test-key-0123456789abcdef0123456789abcdef";
    static final Ssn SSN = Ssn.parse("123-45-6789");

    @TempDir
    Path tmp;

    final JsonMapper json = JsonMapper.builder().build();

    CreditFileWriter writer(Path dir) {
        return new CreditFileWriter(new GeneratorProperties(dir, KEY, List.of()), new CreditFileNames(KEY), json);
    }

    CreditReport report() {
        return new CreditReportFactory(new Random(7), Clock.systemUTC()).create(SSN, "Avery Patel");
    }

    @Test
    void writesTheReportUnderTheBureauDirectoryWithAKeyedHashName() throws IOException {
        Path dir = tmp.resolve("Reports");
        CreditReport report = report();

        Path file = writer(dir).write(SSN, Bureau.TRANSUNION, report);

        assertThat(file.getParent()).isEqualTo(dir.resolve("TransUnion"));
        assertThat(file.getFileName().toString()).isEqualTo(new CreditFileNames(KEY).fileNameFor(SSN));
        assertThat(file.getFileName().toString()).doesNotContain("6789");
        assertThat(json.readValue(Files.readString(file), CreditReport.class)).isEqualTo(report);
    }

    @Test
    void leavesNoTempFilesBehindAndOverwritesInPlace() throws IOException {
        Path dir = tmp.resolve("files");
        writer(dir).write(SSN, Bureau.EQUIFAX, report());
        writer(dir).write(SSN, Bureau.EQUIFAX, report());
        try (Stream<Path> files = Files.list(dir.resolve("Equifax"))) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void filesAreOwnerOnly() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = tmp.resolve("private");
        Path file = writer(dir).write(SSN, Bureau.EXPERIAN, report());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(dir))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent()))).isEqualTo("rwx------");
    }
}
