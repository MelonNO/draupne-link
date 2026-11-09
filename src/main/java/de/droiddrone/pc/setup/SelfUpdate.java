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
 * <p>
 * 1.0.49 (the review's L8): the installer's Authenticode signature is checked too, as the Tailscale install does -
 * after the download and again by the helper right before it runs the file (held open in between, so it can't be
 * swapped). A signature that is there but doesn't check out (the file changed after signing, an untrusted or broken
 * certificate) always stops the update. While releases aren't code-signed ({@link #SIGNER} null) an unsigned
 * installer is still taken on GitHub's checksum alone; once they are, set SIGNER and only that publisher's runs.
 */
public final class SelfUpdate {
    public interface Progress {
        void update(long done, long total);
    }

    /**
     * The installer's publisher, a regular expression on its signing certificate's subject (e.g. "O=Example AS"), or
     * null: releases aren't code-signed yet (README: Windows says "unknown publisher"), so NotSigned is accepted.
     */
    static final String SIGNER = null;

    private SelfUpdate() {
    }

    /**
     * Whether an installer with this Authenticode result may run: null yes, else why not.
     * @param status Get-AuthenticodeSignature's Status (Valid, NotSigned, HashMismatch, NotTrusted, ...)
     * @param subject the signing certificate's subject, or null
     * @param signer {@link #SIGNER}
     */
    static String signatureVerdict(String status, String subject, String signer) {
        String s = status == null ? "" : status.trim();
        if (signer == null) {
            if (s.equals("Valid") || s.equals("NotSigned")) return null;
            return "the installer's signature does not check out (" + (s.isEmpty() ? "unknown" : s) + "); nothing was installed";
        }
        if (!s.equals("Valid")) return "the installer is not validly signed (" + (s.isEmpty() ? "unknown" : s) + "); nothing was installed";
        if (subject == null || !java.util.regex.Pattern.compile(signer).matcher(subject).find()) {
            return "the installer is signed by someone else (" + subject + "); nothing was installed";
        }
        return null;
    }

    /** The PowerShell lines that stop the helper (via its Finish) unless the held installer's signature passes. */
    static String signatureCheck(Path installer, String signer) {
        String bad = signer == null ? "($st -ne 'Valid' -and $st -ne 'NotSigned')"
                : "($st -ne 'Valid' -or $subj -notmatch " + Shell.psQuote(signer) + ")";
        return String.join("\n",
                "$sig = Get-AuthenticodeSignature -LiteralPath " + quote(installer),
                "$st = [string]$sig.Status; $subj = [string]$sig.SignerCertificate.Subject",
                "if " + bad + " { $lock.Close(); Finish ('the installer''s signature does not check out (' + $st + ' ' + $subj + '); it was not run') }",
                "Note ('signature: ' + $st + ' ' + $subj)");
    }

    /** Checks the downloaded installer's signature now (PowerShell's Get-AuthenticodeSignature); throws when it may not run. */
    static void checkSignature(Path file) throws IOException {
        Shell.Result r = Shell.powershell(Duration.ofMinutes(1), "$s = Get-AuthenticodeSignature -LiteralPath " + quote(file) + "\n"
                + "[string]$s.Status + '|' + [string]$s.SignerCertificate.Subject");
        String out = r.output() == null ? "" : r.output().trim();
        String line = out.substring(out.lastIndexOf('\n') + 1).trim(); // the last line: Status|Subject
        int bar = line.indexOf('|');
        String status = bar < 0 ? "" : line.substring(0, bar).trim();
        String why = signatureVerdict(r.ok() ? status : "", bar < 0 ? null : line.substring(bar + 1).trim(), SIGNER);
        if (why != null) throw new IOException(why);
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
        try {
            checkSignature(file); // 1.0.49 (L8): and its Authenticode signature
        } catch (IOException e) {
            Files.deleteIfExists(file);
            throw e;
        }
        return file;
    }

    /** The helper's log, next to the installer: every step, and the result line the app reports at its next start. */
    public static Path helperLog() {
        return Path.of(System.getProperty("java.io.tmpdir"), "DraupneLink-update", "update.log");
    }

    /**
     * The helper's result from the last UPDATE ("OK ..." or why nothing was installed), once: the log is renamed so the
     * next start says nothing. null when there was no update, or the helper is still at it (no RESULT line yet).
     */
    public static String takeHelperResult() {
        Path log = helperLog();
        try {
            if (!Files.exists(log)) return null;
            String result = null;
            for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
                if (line.startsWith("RESULT ")) result = line.substring(7).trim();
            }
            if (result == null) return null;
            Files.move(log, log.resolveSibling("update-last.log"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return result;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Starts the helper. The caller must close the app right after; the helper waits for that before installing.
     * @param sha256 the installer's checksum, checked again right before it runs (it waits in a folder this user can write)
     */
    public static void installAndRestart(Path installer, Path app, String sha256) throws IOException {
        Files.deleteIfExists(helperLog());
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
     * Every step goes to update.log next to the installer, ending in a RESULT line the app reports at its next start.
     * Whatever goes wrong (the app still running after 2 minutes, the installer locked by a virus scan - it waits up
     * to 30 s for that - or changed since it was checked, the installer refused), the app is started again, so the
     * pilot is never left with nothing on screen.
     */
    static String script(Path installer, long pid, Path app, String sha256) {
        Path log = installer.resolveSibling("update.log");
        return String.join("\n",
                "$ErrorActionPreference = 'SilentlyContinue'",
                "$log = " + quote(log),
                "function Note($t) { Add-Content -LiteralPath $log -Value ((Get-Date -Format 'HH:mm:ss') + '  ' + $t) }",
                "$app = " + quote(app),
                "function StartApp {",
                "  if (-not (Test-Path -LiteralPath $app)) {",
                "    $k = Get-ChildItem 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall' | Get-ItemProperty |",
                "      Where-Object { $_.DisplayName -eq 'DRAUPNE Link' -and $_.InstallLocation } | Select-Object -First 1",
                "    if ($k) { $app = Join-Path $k.InstallLocation 'DRAUPNE Link.exe' }",
                "  }",
                "  if (Test-Path -LiteralPath $app) { Note ('starting ' + $app); Start-Process -FilePath $app } else { Note 'the app was not found to start again' }",
                "}",
                "function Finish($result) { Note ('RESULT ' + $result); Add-Content -LiteralPath $log -Value ('RESULT ' + $result); StartApp; Remove-Item -LiteralPath " + quote(installer) + " -Force; exit }",
                "Note 'helper started; waiting for DRAUPNE Link (pid " + pid + ") to close'",
                "Wait-Process -Id " + pid + " -Timeout 120",
                "if (Get-Process -Id " + pid + ") { Note 'RESULT DRAUPNE Link was still running after 2 minutes: nothing installed'; Add-Content -LiteralPath $log -Value 'RESULT DRAUPNE Link was still running after 2 minutes: nothing installed'; exit }",
                "Note 'closed'",
                // held open (read-only, others may only read) from the check until it has run: it can't be swapped
                "$lock = $null",
                "for ($i = 0; $i -lt 30 -and -not $lock; $i++) {",
                "  try { $lock = [IO.File]::Open(" + quote(installer) + ", 'Open', 'Read', 'Read') } catch { Start-Sleep -Seconds 1 }",
                "}",
                "if (-not $lock) { Finish 'the downloaded installer could not be opened (locked by a virus scan?)' }",
                "$h = (Get-FileHash -InputStream $lock -Algorithm SHA256).Hash",
                "if ($h -ne '" + sha256.replaceAll("[^0-9a-fA-F]", "") + "') { $lock.Close(); Finish 'the downloaded installer changed since it was checked; it was not run' }",
                "Note 'checksum ok'",
                signatureCheck(installer, SIGNER),
                "Note 'running the installer'",
                "$p = Start-Process -FilePath " + quote(installer) + " -ArgumentList '/passive','/norestart' -Wait -PassThru",
                "$lock.Close()",
                "if ($p.ExitCode -eq 0 -or $p.ExitCode -eq 3010) { Finish ('OK installed (installer exit code ' + $p.ExitCode + ')') }",
                "if ($p.ExitCode -eq 1602 -or $p.ExitCode -eq 1223) { Finish 'the Windows permission prompt was declined, so nothing was installed' }",
                "Finish ('the installer stopped with code ' + $p.ExitCode)",
                "");
    }

    /** A PowerShell literal string: single quotes, nothing inside expanded. */
    static String quote(Path p) {
        return Shell.psQuote(p.toString());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
