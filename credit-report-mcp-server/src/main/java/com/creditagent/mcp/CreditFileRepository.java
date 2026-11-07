package com.creditagent.mcp;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.Ssn;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Read-only access to the credit files directory. */
@Repository
public class CreditFileRepository {

    static final long MAX_FILE_BYTES = 1024 * 1024;

    private final Path directory;
    private final CreditFileNames fileNames;

    public CreditFileRepository(McpServerProperties properties, CreditFileNames fileNames) {
        this.directory = properties.directory().toAbsolutePath().normalize();
        this.fileNames = fileNames;
    }

    /** The raw JSON of the credit report for this SSN, if one exists. */
    public Optional<String> findReportJson(Ssn ssn) {
        Path file = directory.resolve(fileNames.fileNameFor(ssn)).normalize();
        // File names are hex by construction; this is defence in depth.
        if (!file.getParent().equals(directory)) {
            return Optional.empty();
        }
        // Never follow a symlink planted in the directory to somewhere else.
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                throw new IllegalStateException("Credit file exceeds " + MAX_FILE_BYTES + " bytes");
            }
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read credit file", e);
        }
    }
}
