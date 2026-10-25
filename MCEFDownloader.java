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

    private final String host;
    private final String javaCefCommitHash;
    private final MCEFPlatform platform;

    public MCEFDownloader(String host, String javaCefCommitHash, MCEFPlatform platform) {
        this.host = host;
        this.javaCefCommitHash = javaCefCommitHash;
        this.platform = platform;
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
        return url
                .replace("${host}", host)
                .replace("${java-cef-commit}", javaCefCommitHash)
                .replace("${platform}", platform.getNormalizedName());
    }

    public void downloadJavaCefBuild() throws IOException {
        File mcefLibrariesPath = new File(System.getProperty("mcef.libraries.path"));
        MCEFDownloadListener.INSTANCE.setTask("Downloading Chromium Embedded Framework");
        downloadFile(getJavaCefDownloadUrl(), new File(mcefLibrariesPath, platform.getNormalizedName() + ".tar.gz"));
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

    private static void downloadFile(String urlString, File outputFile) throws IOException {
        WikiLog.info(urlString + " -> " + outputFile.getCanonicalPath());
        HttpURLConnection connection = null;
        try {
            // One request: the connection that was checked is the one that is read (the old code opened a second one for the body)
            connection = (HttpURLConnection) new URL(urlString).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            // The host's bot filter answers HTTP 403 to Java's default "Java/1.8.0_x" agent (seen on Forge 1.7.10): identify as the mod instead.
            connection.setRequestProperty("User-Agent", "IntegratedNTWiki-MCEF/1.0 (Minecraft mod; +https://github.com/Levviata/integratedntwiki)");
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

                File outputFile = new File(outputDirectory, entry.getName());
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
            }
        } catch (IOException e) {
            MCEF.getLogger().error("Failed to extract gzip file to " + outputDirectory, e);
        }

        MCEFDownloadListener.INSTANCE.setProgress(1.0f);
    }
}
