package com.creditagent.generator;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.CreditReport;
import com.creditagent.common.Ssn;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes a credit report to {@code <directory>/<hmac(ssn)>.json}, atomically and owner-only readable. */
@Component
public class CreditFileWriter {

    private static final Logger log = LoggerFactory.getLogger(CreditFileWriter.class);
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");

    private final Path directory;
    private final CreditFileNames fileNames;
    private final JsonMapper jsonMapper;
    private final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    public CreditFileWriter(GeneratorProperties properties, CreditFileNames fileNames, JsonMapper jsonMapper) {
        this.directory = properties.directory().toAbsolutePath().normalize();
        this.fileNames = fileNames;
        this.jsonMapper = jsonMapper;
    }

    public Path write(Ssn ssn, CreditReport report) {
        try {
            ensureDirectory();
            Path target = directory.resolve(fileNames.fileNameFor(ssn));
            Path temp = Files.createTempFile(directory, ".report-", ".tmp");
            if (posix) {
                Files.setPosixFilePermissions(temp, OWNER_READ_WRITE);
            }
            Files.writeString(temp, jsonMapper.writeValueAsString(report));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Wrote credit report {} for {}", report.reportId(), ssn.masked());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write credit file", e);
        }
    }

    private void ensureDirectory() throws IOException {
        if (Files.notExists(directory)) {
            Files.createDirectories(directory);
            if (posix) {
                Files.setPosixFilePermissions(directory, OWNER_ONLY_DIR);
            }
        }
    }
}
