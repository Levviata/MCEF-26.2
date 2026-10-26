package com.levviata.integratedntwiki.mcef;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * The two decisions {@link MCEFDownloader} has to get right when it unpacks the Chromium runtime, kept free of any library so they can
 * be tested on every operating system.
 * <ul>
 *   <li>{@link #resolveInside}: an archive entry may never be written outside the folder it is being extracted to (an entry named
 *       {@code ../x} or {@code /etc/x} would otherwise land anywhere the game's user can write).</li>
 *   <li>{@link #permissionsFor} / {@link #applyMode}: the archives carry the executable bit of the helper programs (Linux
 *       {@code jcef_helper}, the macOS {@code jcef Helper*.app} binaries), but a plain {@code FileOutputStream} drops it. It is restored here
 *       on file systems that have POSIX permissions (Linux, macOS); on Windows there is nothing to do.</li>
 * </ul>
 */
public final class ArchiveSupport {
    private ArchiveSupport() {}

    /** The file an archive entry is extracted to, or an {@link IOException} when the entry would escape {@code root}. */
    public static Path resolveInside(Path root, String entryName) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(entryName).normalize();
        if (!target.startsWith(base) || target.equals(base)) throw new IOException("Archive entry leaves the target folder: " + entryName);
        return target;
    }

    /**
     * The permissions to give an extracted file: the owner can always read and write it; the execute bits (owner, group, others) and the
     * group/other read bits follow the archive's mode, so a file that was executable in the archive is executable again. Set-uid and
     * set-gid bits are never restored.
     */
    public static Set<PosixFilePermission> permissionsFor(int mode) {
        Set<PosixFilePermission> perms = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) perms.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0010) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) perms.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE);
        return perms;
    }

    /** Applies {@link #permissionsFor} to {@code file}; does nothing where the file system has no POSIX permissions (Windows). */
    public static void applyMode(Path file, int mode) throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        Files.setPosixFilePermissions(file, permissionsFor(mode));
    }
}
