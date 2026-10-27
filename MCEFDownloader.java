/*
 *     MCEF (Minecraft Chromium Embedded Framework)
 *     Copyright (C) 2023 CinemaMod Group
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 */

package com.levviata.integratedntwiki.mcef;

import com.levviata.integratedntwiki.WikiLog;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A downloader and extraction tool for java-cef builds.
 * <p>
 * Downloads for <a href="https://github.com/CinemaMod/java-cef">CinemaMod java-cef</a> are provided by the CinemaMod Group unless changed
 * in the MCEFSettings properties file; see {@link MCEFSettings}.
 * Email ds58@mailbox.org for any questions or concerns regarding the file hosting.
 */
public class MCEFDownloader {
    private static final String JAVA_CEF_DOWNLOAD_URL = "${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz";
    private static final String JAVA_CEF_CHECKSUM_DOWNLOAD_URL = "${host}/java-cef-builds/${java-cef-commit}/${platform}.tar.gz.sha256";

    /**
     * Where the natives come from. {@code UPSTREAM}: CinemaMod's build (unmodified java-cef, Chromium capped at 30 fps, host-pumped loop).
     * {@code MCEF}: this project's build of the same java-cef commit plus the patches in {@code jcef-patches/} (frame rate setting,
     * optional threaded loop), published as GitHub release assets; its archive is verified against its published SHA-256.
     */
    public enum Source { UPSTREAM, MCEF }

    /** Release assets of the MCEF build: {@code <this>/natives-<revision>-<commit7>/<platform>.tar.gz} (+ {@code .sha256}). */
    public static final String MCEF_NATIVES_BASE = "https://github.com/Levviata/MCEF-26.2/releases/download";
    /** Bumped with every change to the patches (the number in {@code jcef-patches/VERSION}); it is part of the release tag. */
    public static final String MCEF_NATIVES_REVISION = "mcef1";

    private final String host;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;
    private final Source source;

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform) {
        this(host, javaCefCommitHash, platform, Source.UPSTREAM);
    }

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform, Source source) {
        this.host = host;
        this.javaCefCommitHash = javaCefCommitHash;
        this.platform = platform;
        this.source = source;
    }

    public Source getSource() {
        return source;
    }

    /** The release tag of the MCEF build for this java-cef commit, e.g. {@code natives-mcef1-eaeb3d4}. */
    public static String mcefTag(String javaCefCommit) {
        return "natives-" + MCEF_NATIVES_REVISION + "-" + javaCefCommit.substring(0, Math.min(7, javaCefCommit.length()));
    }

    public String getHost() {
        return host;
    }

    public String getJavaCefDownloadUrl() {
        return formatURL(JAVA_CEF_DOWNLOAD_URL);
    }

    public String getJavaCefChecksumDownloadUrl() {
        return formatURL(JAVA_CEF_CHECKSUM_DOWNLOAD_URL);
    }

    private String formatURL(String url) {
        if (source == Source.MCEF) {
            String file = url.endsWith(".sha256") ? "${platform}.tar.gz.sha256" : "${platform}.tar.gz";
            return (MCEF_NATIVES_BASE + "/" + mcefTag(javaCefCommitHash) + "/" + file).replace("${platform}", platform.getNormalizedName());
        }
        return url
                .replace("${host}", host)
                .replace("${java-cef-commit}", javaCefCommitHash)
                .replace("${platform}", platform.getNormalizedName());
    }

    public void downloadJavaCefBuild() throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
        File archive = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz");
        downloadFile(getJavaCefDownloadUrl(), archive);
        if (source == Source.MCEF) verifyArchive(archive);
        // Which build is installed, for diagnostics and for the log line at start-up
        writeMarker(mcefLibrariesPath);
    }

    /** The file next to the archive that says which source the installed runtime came from. */
    static File markerFile(File librariesPath, MCEFPlatform platform) {
        return new File(librariesPath, platform.getNormalizedName() + ".natives-source");
    }

    private void writeMarker(File librariesPath) throws IOException {
        String text = source == Source.MCEF ? "mcef " + mcefTag(javaCefCommitHash) : "upstream " + javaCefCommitHash;
        java.nio.file.Files.writeString(markerFile(librariesPath, platform).toPath(), text, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * The MCEF build's archive must match the SHA-256 published beside it: a truncated or tampered download is deleted and refused, so
     * the game never extracts (and loads) a native library it cannot vouch for. The upstream archive keeps its old behaviour.
     */
    private void verifyArchive(File archive) throws IOException {
        File checksum = new File(archive.getParentFile(), platform.getNormalizedName() + ".tar.gz.sha256"); // downloadJavaCefChecksum left the published one here
        String expected = checksum.exists()
                ? java.nio.file.Files.readString(checksum.toPath(), java.nio.charset.StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase(java.util.Locale.ROOT)
                : "";
        if (!expected.matches("[0-9a-f]{64}")) {
            archive.delete();
            throw new IOException("The published checksum for " + archive.getName() + " is missing or malformed; refusing to use the download");
        }
        String actual;
        try (InputStream in = new FileInputStream(archive)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder hex = new StringBuilder();
            for (byte b : md.digest()) hex.append(String.format("%02x", b));
            actual = hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (!actual.equals(expected)) {
            archive.delete();
            throw new IOException("Checksum mismatch for " + archive.getName() + ": expected " + expected + " but the download is " + actual);
        }
    }

    /** Throws if the published checksum of this source cannot be fetched (the release does not exist, or the host is unreachable). Leaves no state behind. */
    public void checkPublished() throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        File probe = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz.sha256.probe");
        try {
            downloadFile(getJavaCefChecksumDownloadUrl(), probe);
        } finally {
            probe.delete();
        }
    }

    /**
     * @return true if the jcef build checksum file matches the remote checksum file (for the {@link MCEFDownloader#javaCefCommitHash}),
     * false if the jcef build checksum file did not exist or did not match; this means we should redownload JCEF
     * @throws IOException
     */
    public boolean downloadJavaCefChecksum() throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        File jcefBuildHashFileTemp = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz.sha256.temp");
        File jcefBuildHashFile = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz.sha256");

        MCEFDownloadListener.INSTANCE.setTask("Downloading Checksum");
        downloadFile(getJavaCefChecksumDownloadUrl(), jcefBuildHashFileTemp);

        if (jcefBuildHashFile.exists()) {
            boolean sameContent = FileUtils.contentEquals(jcefBuildHashFile, jcefBuildHashFileTemp);
            if (sameContent) {
                jcefBuildHashFileTemp.delete();
                return true;
            } else {
                MCEF.getLogger().warn("JCEF Hash does not match.");
            }
        } else {
            MCEF.getLogger().warn("Failed to download JCEF hash.");
        }

        jcefBuildHashFileTemp.renameTo(jcefBuildHashFile);

        return false;
    }

    public void extractJavaCefBuild(boolean delete) {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        File tarGzArchive = new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz");
        extractTarGz(tarGzArchive, mcefLibrariesPath);
        if (delete) {
            if (tarGzArchive.exists()) {
                tarGzArchive.delete();
            }
        }
    }

    /** A host that does not answer must not hold the browser's start-up: the checksum check gives up after this long and the runtime on disk is used. */
    private static final int CONNECT_TIMEOUT_MS = 5_000, READ_TIMEOUT_MS = 15_000;

    private static final String USER_AGENT = "IntegratedNTWiki-MCEF/1.0 (Minecraft mod; +https://github.com/Levviata/integratedntwiki)";

    /**
     * Java first; when that fails (the host answers HTTP 403 to Java 8's TLS stack on Forge 1.7.10 although every newer Java is fine, so
     * it is not the file or the commit) the system's own {@code curl} is tried, which Windows 10+, macOS and most Linux systems have.
     */
    private static void downloadFile(String urlString, File outputFile) throws IOException {
        try {
            downloadWithJava(urlString, outputFile);
        } catch (IOException first) {
            WikiLog.warn("Java could not download " + urlString + " (" + first.getMessage() + "); trying curl");
            try {
                downloadWithCurl(urlString, outputFile);
            } catch (IOException second) {
                first.addSuppressed(second);
                throw new IOException(first.getMessage() + " (curl: " + second.getMessage() + ")", first);
            }
        }
    }

    private static void downloadWithCurl(String urlString, File outputFile) throws IOException {
        String curl = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? System.getenv("SystemRoot") + "\\System32\\curl.exe" : "curl";
        ProcessBuilder pb = new ProcessBuilder(curl, "--fail", "--silent", "--show-error", "--location", "--max-time", "900",
                "--connect-timeout", "15", "-A", USER_AGENT, "-o", outputFile.getAbsolutePath(), urlString);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        try (InputStream in = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) != -1 && out.length() < 400) out.append(new String(buffer, 0, n));
        }
        try {
            if (process.waitFor() != 0) {
                outputFile.delete();
                throw new IOException("exit " + process.exitValue() + " " + out.toString().trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            throw new IOException("interrupted");
        }
        MCEFDownloadListener.INSTANCE.setProgress(1F);
    }

    private static void downloadWithJava(String urlString, File outputFile) throws IOException {
        WikiLog.info(urlString + " -> " + outputFile.getCanonicalPath());
        HttpURLConnection connection = null;
        try {
            // One request: the connection that was checked is the one that is read (the old code opened a second one for the body)
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            // The host's bot filter answers HTTP 403 to Java's default "Java/1.8.0_x" agent (seen on Forge 1.7.10): identify as the mod instead.
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            long fileSize = connection.getContentLengthLong();

            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1 << 16);
                 OutputStream out = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[1 << 16];
                int count;
                long readBytes = 0;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    readBytes += count;
                    if (fileSize > 0) MCEFDownloadListener.INSTANCE.setProgress((float) readBytes / fileSize);
                }
            }
        } catch (IOException e) {
            throw new IOException("Failed to download " + urlString + ": " + e.getMessage(), e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void extractTarGz(File tarGzFile, File outputDirectory) {
        MCEFDownloadListener.INSTANCE.setTask("Extracting");

        outputDirectory.mkdirs();

        long fileSize = tarGzFile.length();
        long totalBytesRead = 0;

        try (TarArchiveInputStream tarInput = new TarArchiveInputStream(new GzipCompressorInputStream(new FileInputStream(tarGzFile)))) {
            TarArchiveEntry entry;
            while ((entry = tarInput.getNextTarEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                // Never write outside the target folder, whatever the archive says
                File outputFile = ArchiveSupport.resolveInside(outputDirectory.toPath(), entry.getName()).toFile();
                outputFile.getParentFile().mkdirs();

                try (OutputStream outputStream = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = tarInput.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        float percentComplete = (((float) totalBytesRead / fileSize) / 2.6158204f); // Roughly the compression ratio
                        MCEFDownloadListener.INSTANCE.setProgress(percentComplete);
                        buffer = new byte[Math.max(4096, tarInput.available())];
                    }
                }
                // Restore the executable bit the archive carries (jcef_helper, the macOS jcef Helper*.app binaries); FileOutputStream drops it
                ArchiveSupport.applyMode(outputFile.toPath(), entry.getMode());
            }
        } catch (IOException e) {
            MCEF.getLogger().error("Failed to extract gzip file to " + outputDirectory, e);
        }

        MCEFDownloadListener.INSTANCE.setProgress(1.0f);
    }
}
