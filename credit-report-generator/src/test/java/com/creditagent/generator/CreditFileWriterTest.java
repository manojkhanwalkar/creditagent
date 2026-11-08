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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    static Map<Bureau, Object> all(Object report) {
        Map<Bureau, Object> files = new EnumMap<>(Bureau.class);
        for (Bureau bureau : Bureau.values()) {
            files.put(bureau, report);
        }
        return files;
    }

    @Test
    void writesEachBureauUnderItsDirectoryWithAKeyedHashName() throws IOException {
        Path dir = tmp.resolve("Reports");
        CreditReport report = report();

        Map<Bureau, Path> written = writer(dir).writeAll(SSN, all(report));

        assertThat(written).containsOnlyKeys(Bureau.values());
        Path file = written.get(Bureau.TRANSUNION);
        assertThat(file.getParent()).isEqualTo(dir.resolve("TransUnion"));
        assertThat(file.getFileName().toString()).isEqualTo(new CreditFileNames(KEY).fileNameFor(SSN));
        assertThat(file.getFileName().toString()).doesNotContain("6789");
        assertThat(json.readValue(Files.readString(file), CreditReport.class)).isEqualTo(report);
    }

    @Test
    void aBureauMissingFromTheNewSetLosesItsOldFile() {
        Path dir = tmp.resolve("Reports");
        writer(dir).writeAll(SSN, all(report()));

        Map<Bureau, Object> partial = all(report());
        partial.put(Bureau.EXPERIAN, null);
        Map<Bureau, Path> written = writer(dir).writeAll(SSN, partial);

        assertThat(written).containsOnlyKeys(Bureau.EQUIFAX, Bureau.TRANSUNION);
        assertThat(dir.resolve("Experian").resolve(new CreditFileNames(KEY).fileNameFor(SSN))).doesNotExist();
    }

    @Test
    void leavesNoTempFilesBehindAndOverwritesInPlace() throws IOException {
        Path dir = tmp.resolve("files");
        writer(dir).writeAll(SSN, all(report()));
        writer(dir).writeAll(SSN, all(report()));
        try (Stream<Path> files = Files.list(dir.resolve("Equifax"))) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void aFailedWriteLeavesTheOldSetIntact() throws IOException {
        Path dir = tmp.resolve("Reports");
        CreditReport first = report();
        writer(dir).writeAll(SSN, all(first));

        Map<Bureau, Object> broken = all(report());
        broken.put(Bureau.TRANSUNION, new Object() {
            @SuppressWarnings("unused")
            public String getBoom() {
                throw new IllegalStateException("boom");
            }
        });
        try {
            writer(dir).writeAll(SSN, broken);
        } catch (RuntimeException expected) {
            // serialisation of the last bureau fails before anything is moved into place
        }

        String name = new CreditFileNames(KEY).fileNameFor(SSN);
        for (Bureau bureau : Bureau.values()) {
            Path file = dir.resolve(bureau.directoryName()).resolve(name);
            assertThat(json.readValue(Files.readString(file), CreditReport.class)).isEqualTo(first);
            try (Stream<Path> files = Files.list(file.getParent())) {
                assertThat(files).hasSize(1);
            }
        }
    }

    @Test
    void overlappingGenerationsNeverMixFiles() throws Exception {
        Path dir = tmp.resolve("Reports");
        CreditFileWriter writer = writer(dir);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String generation = "gen-" + i;
                futures.add(pool.submit(() -> writer.writeAll(SSN, all(Map.of("generation", generation)))));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        String name = new CreditFileNames(KEY).fileNameFor(SSN);
        List<String> generations = new ArrayList<>();
        for (Bureau bureau : Bureau.values()) {
            generations.add(Files.readString(dir.resolve(bureau.directoryName()).resolve(name)));
        }
        assertThat(generations).containsOnly(generations.getFirst());
    }

    @Test
    void filesAndBureauDirectoriesAreOwnerOnlyEvenIfTheyAlreadyExisted() throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path dir = tmp.resolve("private");
        Path existing = Files.createDirectories(dir.resolve("Equifax"));
        Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-xr-x"));

        Path file = writer(dir).writeAll(SSN, all(report())).get(Bureau.EQUIFAX);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(existing))).isEqualTo("rwx------");
    }
}
