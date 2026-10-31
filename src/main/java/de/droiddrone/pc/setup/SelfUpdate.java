package de.droiddrone.pc.setup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;

/**
 * UPDATE: downloads the newer installer from the GitHub release, checks it against the SHA-256 GitHub lists for it,
 * and hands over to a small helper that waits for this app to close, runs the installer (Windows asks for permission
 * once; no wizard, just a progress bar) and starts the new version.
 */
public final class SelfUpdate {
    public interface Progress {
        void update(long done, long total);
    }

    private SelfUpdate() {
    }

    /**
     * This app as installed by the installer (Program Files), or null: a development run or the portable zip, which
     * the installer would not replace (those get the release page instead).
     */
    public static Path installedApp() {
        String app = System.getProperty("jpackage.app-path");
        String programFiles = System.getenv("ProgramFiles");
        if (app == null || programFiles == null) return null;
        Path p = Path.of(app).toAbsolutePath();
        return p.startsWith(Path.of(programFiles)) ? p : null;
    }

    /** Into %TEMP%\DraupneLink-update; progress is reported about once per percent. */
    public static Path download(UpdateCheck.Release r, Progress progress) throws IOException, InterruptedException {
        if (!r.canInstall()) throw new IOException("the release has no installer to update with");
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "DraupneLink-update");
        Files.createDirectories(dir);
        Path file = dir.resolve(r.installerUrl().substring(r.installerUrl().lastIndexOf('/') + 1));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<InputStream> resp = client.send(HttpRequest.newBuilder(URI.create(r.installerUrl()))
                .timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("GitHub answered HTTP " + resp.statusCode());
        }
        MessageDigest sha = sha256();
        long done = 0, total = r.installerSize();
        long lastPercent = -1;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                long percent = done * 100 / total;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.update(done, total);
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(file); // no half installer left behind
            throw e;
        }
        if (done != total || !HexFormat.of().formatHex(sha.digest()).equals(r.installerSha256())) {
            Files.deleteIfExists(file);
            throw new IOException("the download is damaged (size or checksum does not match); nothing was installed");
        }
        return file;
    }

    /**
     * Starts the helper. The caller must close the app right after; the helper waits for that before installing.
     * @param sha256 the installer's checksum, checked again right before it runs (it waits in a folder this user can write)
     */
    public static void installAndRestart(Path installer, Path app, String sha256) throws IOException {
        String script = script(installer, ProcessHandle.current().pid(), app, sha256);
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-EncodedCommand", encoded)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /**
     * Wait for this app to exit, install quietly, start the app again (where the installer put it, if not where it
     * was), and delete the installer. If the pilot declines the Windows prompt, the old version just starts again.
     * Nothing is installed while the app still runs (after 60 s), or if the installer changed since it was checked.
     */
    static String script(Path installer, long pid, Path app, String sha256) {
        return String.join("\n",
                "$ErrorActionPreference = 'SilentlyContinue'",
                "Wait-Process -Id " + pid + " -Timeout 60",
                "if (Get-Process -Id " + pid + ") { exit }",
                // held open (read-only, others may only read) from the check until it has run: it can't be swapped
                "try { $lock = [IO.File]::Open(" + quote(installer) + ", 'Open', 'Read', 'Read') } catch { exit }",
                "$h = (Get-FileHash -InputStream $lock -Algorithm SHA256).Hash",
                "if ($h -ne '" + sha256.replaceAll("[^0-9a-fA-F]", "") + "') { $lock.Close(); Remove-Item -LiteralPath " + quote(installer) + " -Force; exit }",
                "Start-Process -FilePath " + quote(installer) + " -ArgumentList '/passive','/norestart' -Wait",
                "$lock.Close()",
                "$app = " + quote(app),
                "if (-not (Test-Path -LiteralPath $app)) {",
                "  $k = Get-ChildItem 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall' | Get-ItemProperty |",
                "    Where-Object { $_.DisplayName -eq 'DRAUPNE Link' -and $_.InstallLocation } | Select-Object -First 1",
                "  if ($k) { $app = Join-Path $k.InstallLocation 'DRAUPNE Link.exe' }",
                "}",
                "if (Test-Path -LiteralPath $app) { Start-Process -FilePath $app }",
                "Remove-Item -LiteralPath " + quote(installer) + " -Force",
                "");
    }

    /** A PowerShell literal string: single quotes, nothing inside expanded. */
    static String quote(Path p) {
        return "'" + p.toString().replace("'", "''") + "'";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
