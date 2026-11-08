package com.creditagent.mcp;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Read-only access to the reports root. Each bureau's files live in
 * {@code <reports-root>/<Bureau>/<hmac(ssn)>.json}.
 */
@Repository
public class CreditFileRepository {

    static final long MAX_FILE_BYTES = 1024 * 1024;

    private final Path directory;
    private final CreditFileNames fileNames;

    public CreditFileRepository(McpServerProperties properties, CreditFileNames fileNames) {
        this.directory = properties.directory().toAbsolutePath().normalize();
        this.fileNames = fileNames;
    }

    /** The raw JSON of each bureau's report for this SSN; bureaus without a file are absent. */
    public Map<Bureau, String> findBureauReports(Ssn ssn) {
        Map<Bureau, String> reports = new EnumMap<>(Bureau.class);
        for (Bureau bureau : Bureau.values()) {
            read(bureau, ssn).ifPresent(json -> reports.put(bureau, json));
        }
        return reports;
    }

    private Optional<String> read(Bureau bureau, Ssn ssn) {
        Path bureauDirectory = directory.resolve(bureau.directoryName());
        Path file = bureauDirectory.resolve(fileNames.fileNameFor(ssn)).normalize();
        // File names are hex by construction; this is defence in depth.
        if (!file.getParent().equals(bureauDirectory)) {
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
