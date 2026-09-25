#!/usr/bin/env python3
"""
Renders the app's own interface sounds: a startup chime and the focus, select, back and
error sounds, in the spirit of Windows 7 Media Center's soft glassy clicks and airy chime,
but original (their own key, notes and melody). Everything is synthesized here from sine
waves and noise, so the results can be shared under the project's license.

    python3 tools/make_sounds.py app/src/main/res/raw

needs numpy and ffmpeg (for the Ogg Vorbis files the app plays).
"""
import os
import subprocess
import sys

import numpy as np

SR = 44100
rng = np.random.default_rng(7)


def hz(note):
    """Frequency of a note name like 'D4', 'F#5' or 'Bb3'."""
    names = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
    n = names[note[0]]
    rest = note[1:]
    if rest.startswith("#"):
        n += 1
        rest = rest[1:]
    elif rest.startswith("b"):
        n -= 1
        rest = rest[1:]
    midi = 12 * (int(rest) + 1) + n
    return 440.0 * 2 ** ((midi - 69) / 12)


def t_axis(seconds):
    return np.arange(int(SR * seconds)) / SR


def env_ad(n, attack, decay):
    """Attack then exponential decay, over n samples."""
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    return a * np.exp(-np.maximum(t - attack, 0) / decay)


def bell(freq, seconds, decay, ratio=3.5, index=2.2, index_decay=0.25, attack=0.002):
    """A glassy FM bell: a sine whose brightness fades faster than its body."""
    t = t_axis(seconds)
    idx = index * np.exp(-t / index_decay)
    y = np.sin(2 * np.pi * freq * t + idx * np.sin(2 * np.pi * freq * ratio * t))
    # A soft octave shimmer on top.
    y += 0.18 * np.sin(2 * np.pi * freq * 2.003 * t) * np.exp(-t / (decay * 0.5))
    return y * env_ad(len(t), attack, decay)


def pad(freqs, seconds, attack, release, bright=6, detune_cents=5):
    """A warm, slowly breathing chord: a few detuned voices of soft harmonics each."""
    t = t_axis(seconds)
    y = np.zeros_like(t)
    for f in freqs:
        for d in (-detune_cents, 0, detune_cents):
            fd = f * 2 ** (d / 1200)
            phase = rng.uniform(0, 2 * np.pi)
            for h in range(1, bright + 1):
                y += (1 / h ** 1.6) * np.sin(2 * np.pi * fd * h * t + phase * h)
    n = len(t)
    e = np.ones(n)
    a = int(SR * attack)
    r = int(SR * release)
    e[:a] = np.linspace(0, 1, a) ** 2
    e[n - r:] *= np.linspace(1, 0, r) ** 1.5
    return y * e / (len(freqs) * 3)


def lowpass(x, cutoff):
    """One-pole low-pass; cutoff may vary per sample."""
    c = np.broadcast_to(np.asarray(cutoff, dtype=float), x.shape)
    a = np.exp(-2 * np.pi * c / SR)
    y = np.empty_like(x)
    s = 0.0
    for i in range(len(x)):
        s = (1 - a[i]) * x[i] + a[i] * s
        y[i] = s
    return y


def whoosh(seconds, start_hz, end_hz, curve=2.0):
    """Airy noise rising in pitch: band-limited between two sweeping low-passes."""
    n = int(SR * seconds)
    noise = rng.standard_normal(n)
    sweep = start_hz * (end_hz / start_hz) ** (np.linspace(0, 1, n) ** curve)
    hi = lowpass(noise, sweep)
    lo = lowpass(noise, sweep * 0.35)
    return (hi - lo)


def reverb(x, seconds, wet, predelay=0.018, damp=4500, seed=3):
    """A soft stereo hall from decaying filtered noise, convolved in; returns (left, right)."""
    r = np.random.default_rng(seed)
    n = int(SR * seconds)
    t = np.arange(n) / SR
    decay = np.exp(-6.9 * t / seconds)  # -60 dB over `seconds`
    out = []
    for ch in range(2):
        ir = r.standard_normal(n) * decay
        ir = lowpass(ir, damp * np.exp(-t * 1.2) + 800)
        ir = np.concatenate([np.zeros(int(SR * predelay) + ch * 37), ir])
        ir /= np.sqrt(np.sum(ir ** 2))
        m = len(x) + len(ir) - 1
        size = 1 << (m - 1).bit_length()
        y = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)[:m]
        out.append(y)
    m = min(len(o) for o in out)
    dry = np.concatenate([x, np.zeros(m - len(x))])
    return (1 - wet) * dry + wet * out[0][:m], (1 - wet) * dry + wet * out[1][:m]


def place(buf, sound, at, gain=1.0, pan=0.0):
    """Mixes a mono sound into a stereo buffer at `at` seconds, panned -1 (left) to 1 (right)."""
    i = int(SR * at)
    j = min(len(buf[0]), i + len(sound))
    left = np.cos((pan + 1) * np.pi / 4) * gain
    right = np.sin((pan + 1) * np.pi / 4) * gain
    buf[0][i:j] += sound[: j - i] * left
    buf[1][i:j] += sound[: j - i] * right


def fade_out(x, seconds):
    n = int(SR * seconds)
    x[-n:] *= np.linspace(1, 0, n) ** 2
    return x


def normalize(x, peak):
    return x * (peak / np.max(np.abs(x)))


def write(path, channels, peak):
    data = np.stack(channels, axis=1) if isinstance(channels, (list, tuple)) else channels[:, None]
    data = normalize(data, peak).astype(np.float32)
    subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "f32le", "-ar", str(SR), "-ac", str(data.shape[1]),
         "-i", "-", "-c:a", "libvorbis", "-q:a", "6", path],
        input=data.tobytes(), check=True,
    )


# ---------------------------------------------------------------------------------------------
# The startup chime. Timed to the intro animation (WmcIntro.kt): the logo cuts in at 0.30 s and
# zooms back into focus by 0.85 s; the glint crosses the orb from 0.95 s to 2.2 s; the logo
# dissolves from 2.12 s and the menu settles in between 2.48 s and 2.95 s.
# ---------------------------------------------------------------------------------------------
def shimmer(seconds, low=4000, high=11000, rate=5.5):
    """A high, airy wash: band-limited noise with a slow flutter, like light on glass."""
    n = int(SR * seconds)
    noise = rng.standard_normal(n)
    band = lowpass(noise, high) - lowpass(noise, low)
    t = np.arange(n) / SR
    flutter = 0.6 + 0.4 * np.sin(2 * np.pi * rate * t + rng.uniform(0, 6)) * np.sin(2 * np.pi * rate * 0.37 * t)
    return band * flutter


def startup_chime():
    length = 5.4
    buf = [np.zeros(int(SR * length)), np.zeros(int(SR * length))]

    # Air rising out of the black, cresting as the logo appears.
    w = whoosh(0.45, 250, 6000, curve=1.7) * np.linspace(0, 1, int(SR * 0.45)) ** 2.4
    place(buf, fade_out(w, 0.1), 0.0, 0.2, -0.15)

    # The bloom as the logo cuts in: a glassy D major ninth, struck softly, over a warm low swell
    # that keeps growing while the logo zooms back into focus.
    for i, (n, pan) in enumerate([("D4", -0.3), ("F#4", 0.25), ("A4", -0.1), ("C#5", 0.35), ("E5", -0.35)]):
        place(buf, bell(hz(n), 3.4, 1.5, ratio=2.0, index=1.1, index_decay=0.2, attack=0.012), 0.30 + i * 0.014, 0.1, pan)
    swell = pad([hz("D2"), hz("A2"), hz("D3"), hz("F#3"), hz("A3")], 2.6, 0.9, 0.9, bright=7, detune_cents=7)
    place(buf, lowpass(swell, np.linspace(500, 2600, len(swell))), 0.30, 0.55)

    # Sparkles, scattered and quiet, drifting up while the logo settles and holds.
    notes = ["A5", "C#6", "D6", "E6", "F#6", "A6", "C#7", "E7"]
    for k in range(18):
        at = 0.42 + k * 0.105 + rng.uniform(-0.02, 0.02)
        n = notes[min(len(notes) - 1, k // 3 + int(rng.integers(0, 3)))]
        place(buf, bell(hz(n), 1.6, 0.55, ratio=3.5, index=1.4), at, 0.035 + 0.02 * rng.random(), rng.uniform(-0.8, 0.8))

    # A high shimmer over the whole hold, as the glint crosses the orb.
    sh = shimmer(3.2) * np.sin(np.linspace(0, np.pi, int(SR * 3.2))) ** 1.5
    place(buf, sh, 0.5, 0.05, 0.2)

    # The hold: the harmony opens up (a G major seventh over D) as the logo dissolves ...
    hold = pad([hz("D3"), hz("G3"), hz("B3"), hz("F#4"), hz("A4")], 1.7, 0.6, 0.7, bright=8, detune_cents=8)
    place(buf, lowpass(hold, np.linspace(1400, 4200, len(hold))), 1.2, 0.5)

    # ... and resolves home, fullest as the menu settles in: D with an added ninth, and three bells.
    home = pad([hz("D2"), hz("A2"), hz("D3"), hz("F#3"), hz("A3"), hz("E4"), hz("F#4")], 2.9, 0.35, 2.1, bright=8, detune_cents=8)
    place(buf, lowpass(home, np.linspace(4800, 1800, len(home))), 2.40, 0.72)
    place(buf, bell(hz("F#5"), 2.8, 1.3, ratio=2.0, index=1.0, attack=0.006), 2.50, 0.1, -0.35)
    place(buf, bell(hz("A5"), 2.8, 1.3, ratio=2.0, index=1.0, attack=0.006), 2.56, 0.09, 0.35)
    place(buf, bell(hz("D6"), 2.6, 1.2, ratio=2.0, index=0.9, attack=0.006), 2.64, 0.08, 0.0)

    # One hall around it all.
    mono = (buf[0] + buf[1]) / 2
    side = (buf[0] - buf[1]) / 2
    l1, r1 = reverb(mono, 3.4, 0.55, damp=5500)
    n = int(SR * length)
    left = l1[:n] + side * 0.8
    right = r1[:n] - side * 0.8
    return [fade_out(left, 0.7), fade_out(right, 0.7)]


def focus_tick():
    """Moving the cursor: a soft, short glassy tick."""
    n = int(SR * 0.11)
    noise = rng.standard_normal(n)
    click = (lowpass(noise, 6500) - lowpass(noise, 2200)) * env_ad(n, 0.0008, 0.006)
    ping = bell(hz("E7"), 0.11, 0.018, ratio=2.0, index=0.8, index_decay=0.01, attack=0.0008)
    body = bell(hz("B6"), 0.11, 0.03, ratio=1.0, index=0.3, attack=0.001)
    y = 0.8 * click + 0.55 * ping + 0.35 * body
    l, _ = reverb(y, 0.35, 0.12, predelay=0.008)
    return fade_out(l[: int(SR * 0.16)], 0.04)


def two_notes(first, second, gap, decay, tap_hz):
    n = int(SR * 0.75)
    y = np.zeros(n)
    tap = np.sin(2 * np.pi * tap_hz * np.arange(n) / SR) * env_ad(n, 0.001, 0.025)
    y += 0.5 * tap
    a = bell(hz(first), 0.75, decay, ratio=2.0, index=1.3, index_decay=0.06)
    b = bell(hz(second), 0.75, decay, ratio=2.0, index=1.3, index_decay=0.06)
    y += 0.55 * a
    i = int(SR * gap)
    y[i:] += 0.5 * b[: n - i]
    l, _ = reverb(y, 0.9, 0.2, predelay=0.012)
    return fade_out(l[: int(SR * 0.7)], 0.15)


def select_chime():
    """Selecting: a light tap and a two-note chime rising a major third."""
    return two_notes("D6", "F#6", 0.035, 0.16, 190)


def back_chime():
    """Going back: the same pair, falling."""
    return two_notes("F#6", "D6", 0.035, 0.13, 170)


def error_tone():
    """Something went wrong: two soft low notes, falling."""
    n = int(SR * 0.5)
    y = np.zeros(n)
    a = bell(hz("A4"), 0.5, 0.12, ratio=1.0, index=0.6)
    b = bell(hz("F4"), 0.5, 0.16, ratio=1.0, index=0.6)
    y += 0.6 * a
    i = int(SR * 0.11)
    y[i:] += 0.6 * b[: n - i]
    l, _ = reverb(y, 0.7, 0.15)
    return fade_out(l[:n], 0.1)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/raw"
    os.makedirs(out, exist_ok=True)
    write(os.path.join(out, "mc_intro.ogg"), startup_chime(), 0.32)
    write(os.path.join(out, "mc_focus.ogg"), focus_tick(), 0.14)
    write(os.path.join(out, "mc_select.ogg"), select_chime(), 0.2)
    write(os.path.join(out, "mc_back.ogg"), back_chime(), 0.18)
    write(os.path.join(out, "mc_error.ogg"), error_tone(), 0.2)
    print("wrote", ", ".join(sorted(f for f in os.listdir(out) if f.startswith("mc_"))), "to", out)


if __name__ == "__main__":
    main()
