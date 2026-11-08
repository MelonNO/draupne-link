"""
The virtual fleet's real sounds (1.0.52, the pilot: "use the sound of a real armoured vehicle", "use a real sound" for
the drone). Cuts the parts used from freesound.org recordings (all CC0, see THIRD-PARTY.md), levels them, makes the
loops seamless (the end cross-faded into the start) and writes 16 kHz mono mu-law WAV files to
src/main/resources/sound/ - small, and read by javax.sound without any native code.

Usage: python -I prepare_sounds.py <ffmpeg.exe> <dir with the downloaded previews, one subfolder dl-<id> each>

The sources (the -hq.mp3 previews of):
  182793 qubodup   "Military Vehicle Humming.flac"                      -> btr-idle.wav   (engine, standing)
  405086 kyles     "truck large diesel or military tank APC ... pull away" -> btr-drive.wav  (engine, driving)
  677833 Sadiquecat "Tiny Hawk 2 short flight.wav"                      -> fpv-spinup.wav (arming: the motors start)
  854466 qubodup   "FPV Drone Flight 3"                                 -> fpv-flight.wav (in the air)
"""
import array, math, os, subprocess, sys, tempfile, wave

RATE = 16000
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "sound")

# name: (source id, from s, to s, loop cross-fade s or 0 for a one-shot, fade in s, fade out s)
PARTS = {
    "btr-idle": ("182793_71257", 2.0, 10.0, 0.6, 0, 0),
    "btr-drive": ("405086_612689", 13.8, 16.2, 0.4, 0, 0),
    "fpv-spinup": ("677833_5287430", 4.3, 6.2, 0, 0.05, 0.25),
    "fpv-flight": ("854466_71257", 1.0, 9.0, 0.6, 0, 0),
}
TARGET_RMS = 0.18


def decode(ffmpeg, src):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "x.wav")
        subprocess.run([ffmpeg, "-v", "error", "-y", "-i", src, "-ac", "1", "-ar", str(RATE), "-c:a", "pcm_s16le", wav], check=True)
        with wave.open(wav) as w:
            return [x / 32768.0 for x in array.array("h", w.readframes(w.getnframes()))]


def level(x):
    rms = math.sqrt(sum(v * v for v in x) / len(x))
    g = TARGET_RMS / max(1e-6, rms)
    peak = max(abs(v) for v in x) * g
    if peak > 0.98:
        g *= 0.98 / peak
    return [v * g for v in x]


def loop(x, fade):
    """The last fade seconds mixed into the first, equal power: played over and over, no seam."""
    n = int(fade * RATE)
    body = x[:len(x) - n]
    for i in range(n):
        k = (i + 0.5) / n
        body[i] = body[i] * math.sin(k * math.pi / 2) + x[len(x) - n + i] * math.cos(k * math.pi / 2)
    return body


def fades(x, fin, fout):
    a, b = int(fin * RATE), int(fout * RATE)
    for i in range(a):
        x[i] *= i / a
    for i in range(b):
        x[len(x) - 1 - i] *= i / b
    return x


def write_ulaw(path, x, ffmpeg):
    with tempfile.TemporaryDirectory() as tmp:
        pcm = os.path.join(tmp, "x.wav")
        with wave.open(pcm, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(array.array("h", [max(-32768, min(32767, int(round(v * 32767)))) for v in x]).tobytes())
        subprocess.run([ffmpeg, "-v", "error", "-y", "-i", pcm, "-c:a", "pcm_mulaw", "-map_metadata", "-1", path], check=True)


def main():
    ffmpeg, downloads = sys.argv[1], sys.argv[2]
    os.makedirs(OUT, exist_ok=True)
    for name, (sid, t0, t1, xfade, fin, fout) in PARTS.items():
        x = decode(ffmpeg, os.path.join(downloads, "dl-" + sid, sid + ".mp3"))[int(t0 * RATE):int(t1 * RATE)]
        x = level(x)
        x = loop(x, xfade) if xfade > 0 else fades(x, fin, fout)
        path = os.path.join(OUT, name + ".wav")
        write_ulaw(path, x, ffmpeg)
        print(name, "%.2f s" % (len(x) / RATE), os.path.getsize(path), "bytes")


if __name__ == "__main__":
    main()
