package com.creditagent.generator;

import com.creditagent.common.CreditFileNames;
import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one consumer's bureau reports to {@code <reports-root>/<Bureau>/<hmac(ssn)>.json}, owner-only
 * readable.
 *
 * <p>The files for one consumer are written as a set: every file is first staged next to its target,
 * and only when all are staged are they moved into place (each move atomic where the file system
 * allows). A bureau with no report in the new set has its old file removed. Writes for the same SSN
 * are serialised, so overlapping generations cannot interleave and leave files from two histories
 * side by side.
 */
@Component
public class CreditFileWriter {

    private static final Logger log = LoggerFactory.getLogger(CreditFileWriter.class);
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");
    private static final Set<PosixFilePermission> OWNER_ONLY_DIR = PosixFilePermissions.fromString("rwx------");
    private static final int LOCK_STRIPES = 64;

    private final Path directory;
    private final CreditFileNames fileNames;
    private final JsonMapper jsonMapper;
    private final boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    private final Object[] locks = new Object[LOCK_STRIPES];

    public CreditFileWriter(GeneratorProperties properties, CreditFileNames fileNames, JsonMapper jsonMapper) {
        this.directory = properties.directory().toAbsolutePath().normalize();
        this.fileNames = fileNames;
        this.jsonMapper = jsonMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    /**
     * Replaces this consumer's whole set of bureau files.
     *
     * @param reports the report per bureau; a bureau that is absent or null ends up with no file
     * @return the files written
     */
    public Map<Bureau, Path> writeAll(Ssn ssn, Map<Bureau, ?> reports) {
        String fileName = fileNames.fileNameFor(ssn);
        synchronized (locks[Math.floorMod(fileName.hashCode(), LOCK_STRIPES)]) {
            Map<Bureau, Path> staged = new EnumMap<>(Bureau.class);
            try {
                ensureDirectory(directory, false);
                for (Bureau bureau : Bureau.values()) {
                    Object report = reports.get(bureau);
                    if (report != null) {
                        staged.put(bureau, stage(bureauDirectory(bureau), report));
                    }
                }
                Map<Bureau, Path> written = new EnumMap<>(Bureau.class);
                for (Bureau bureau : Bureau.values()) {
                    Path target = bureauDirectory(bureau).resolve(fileName);
                    Path temp = staged.remove(bureau);
                    if (temp != null) {
                        move(temp, target);
                        written.put(bureau, target);
                    } else {
                        Files.deleteIfExists(target);
                    }
                }
                log.info("Wrote {} bureau credit reports for {}", written.size(), ssn.masked());
                return written;
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write credit files", e);
            } finally {
                staged.values().forEach(CreditFileWriter::deleteQuietly);
            }
        }
    }

    private Path bureauDirectory(Bureau bureau) throws IOException {
        Path dir = directory.resolve(bureau.directoryName());
        ensureDirectory(dir, true);
        return dir;
    }

    private Path stage(Path dir, Object report) throws IOException {
        Path temp = Files.createTempFile(dir, ".report-", ".tmp");
        try {
            if (posix) {
                Files.setPosixFilePermissions(temp, OWNER_READ_WRITE);
            }
            Files.writeString(temp, jsonMapper.writeValueAsString(report));
            return temp;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Creates the directory owner-only. The bureau directories hold personal data and belong to this
     * service, so their permissions are tightened even if they already exist; the reports root may be
     * a folder the user made, so it is only set when this service creates it.
     */
    private void ensureDirectory(Path dir, boolean tightenExisting) throws IOException {
        boolean created = Files.notExists(dir);
        if (created) {
            Files.createDirectories(dir);
        }
        if (posix && (created || tightenExisting)) {
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove temporary credit file {}", path.getFileName());
        }
    }
}
