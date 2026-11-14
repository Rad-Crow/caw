#!/usr/bin/env python3
# Renders the call and message sounds: Android res/raw (Ogg Vorbis, both themes) and the web client's crow theme (MP3).
# Classic matches the tones the web client synthesizes in web/src/lib/tones.ts; crow is synthesized here.
# Usage: tools/sounds.py    (Python 3 and ffmpeg with libvorbis and libmp3lame)
import math
import os
import random
import struct
import subprocess

RATE = 48000
TAU = 2 * math.pi
BLOCK = 32
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'res', 'raw')
WEB = os.path.join(ROOT, 'web', 'src', 'assets', 'sounds')
# Android plays ring and message at ring and notification volume, beside system sounds mastered near full scale
NOTIFY_GAIN = 10 ** (11 / 20)

# one loop of each sound: (length s, [(Hz, start s, duration s, gain)])
CLASSIC = {
    'ring': (2.5, [([523, 659], 0.0, 0.3, 0.12), ([523, 659], 0.45, 0.3, 0.12)]),
    'ringback': (6.0, [([440, 480], 0.0, 2.0, 0.07)]),
    'hangup': (0.6, [([480, 620], 0.0, 0.25, 0.07), ([480, 620], 0.35, 0.25, 0.07)]),
    'message': (0.33, [([880], 0.0, 0.12, 0.06), ([1175], 0.13, 0.2, 0.06)]),
}

# pitch: (position 0..1, Hz) points; formants: (points, bandwidth Hz, gain); noise_bands: (Hz, bandwidth Hz, gain)
CROW = {
    'dur': 0.34,
    'pitch': [(0, 560), (0.2, 640), (0.7, 600), (1, 470)],
    'formants': [([(0, 1300), (1, 1150)], 700, 1.0), ([(0, 2400), (1, 2100)], 900, 0.6), ([(0, 3600), (1, 3400)], 1200, 0.25)],
    'noise_bands': [(1300, 900, 1.0), (2400, 1100, 0.6), (3600, 1400, 0.3)],
    'tilt': 0.6, 'sub': 0.5, 'jitter': 0.05, 'shimmer': 0.35, 'noise': 0.4,
    'drive': 2.2, 'attack': 0.015, 'release': 0.07, 'click': 0.3, 'top': 9000,
}
CROW_FALL = {**CROW, 'dur': 0.42, 'pitch': [(0, 600), (0.15, 640), (1, 380)], 'release': 0.12}
JACKDAW = {
    'dur': 0.11,
    'pitch': [(0, 1050), (0.3, 1150), (1, 900)],
    'formants': [([(0, 2000), (1, 1900)], 1000, 1.0), ([(0, 3400), (1, 3300)], 1200, 0.5)],
    'noise_bands': [(2000, 1100, 1.0), (3400, 1300, 0.5)],
    'tilt': 0.5, 'sub': 0.25, 'jitter': 0.03, 'shimmer': 0.2, 'noise': 0.45,
    'drive': 2.0, 'attack': 0.004, 'release': 0.035, 'click': 0.5, 'top': 10000,
}

# one loop of each sound: (length s, [(start s, voice, seed)], peak, distant)
CROW_THEME = {
    'ring': (2.5, [(0.0, CROW, 51), (0.45, CROW, 52)], 0.24, False),
    'ringback': (3.0, [(0.0, CROW, 61)], 0.14, True),
    'hangup': (0.6, [(0.0, CROW_FALL, 71)], 0.14, False),
    'message': (0.35, [(0.0, JACKDAW, 81), (0.2, JACKDAW, 82)], 0.158, False),
}


def classic(length, beeps):
    buf = [0.0] * round(length * RATE)
    for freqs, start, dur, gain in beeps:
        a, b = round(start * RATE), round((start + dur) * RATE)
        for i in range(a, b):
            t = (i - a) / RATE
            buf[i] += gain * sum(math.sin(TAU * f * t) for f in freqs)
    return buf


def contour(points, u):
    for (u0, v0), (u1, v1) in zip(points, points[1:]):
        if u <= u1:
            k = (u - u0) / (u1 - u0) if u1 > u0 else 1.0
            return v0 + (v1 - v0) * (1 - math.cos(math.pi * k)) / 2
    return points[-1][1]


def unit_rms(x):
    rms = math.sqrt(sum(v * v for v in x) / len(x)) or 1.0
    return [v / rms for v in x]


def smooth_noise(n, rng, cutoff):
    a = math.exp(-TAU * cutoff / RATE)
    y, out = 0.0, []
    for _ in range(n):
        y = a * y + (1 - a) * rng.gauss(0, 1)
        out.append(y)
    return unit_rms(out)


def bandpass(x, f, q):
    w = TAU * f / RATE
    alpha = math.sin(w) / (2 * q)
    a0 = 1 + alpha
    b0, b2, a1, a2 = alpha / a0, -alpha / a0, -2 * math.cos(w) / a0, (1 - alpha) / a0
    x1 = x2 = y1 = y2 = 0.0
    out = []
    for v in x:
        y = b0 * v + b2 * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, y
        out.append(y)
    return out


def call(v, seed):
    rng = random.Random(seed)
    lift = 1 + rng.uniform(-0.04, 0.04)
    dur = v['dur'] * (1 + rng.uniform(-0.08, 0.08))
    n = int(dur * RATE)
    jit = smooth_noise(n, rng, 30)
    shim = smooth_noise(n, rng, 70)
    white = [rng.gauss(0, 1) for _ in range(n)]
    noise = [0.0] * n
    for f, bw, g in v['noise_bands']:
        for i, s in enumerate(bandpass(white, f, f / bw)):
            noise[i] += g * s
    noise = unit_rms(noise)

    harm, phases, partials, phase = [0.0] * n, [0.0] * n, [], 0.0
    for i in range(n):
        u = i / n
        if i % BLOCK == 0:
            base = contour(v['pitch'], u) * lift
            fm = [(contour(pts, u), bw / 2, g) for pts, bw, g in v['formants']]

            def gain(f):
                return 0.04 + sum(g / (1 + ((f - c) / hb) ** 2) for c, hb, g in fm)

            partials = []
            k = 1
            while k * base < v['top']:
                partials.append((k, gain(k * base) * k ** -v['tilt']))
                k += 1
            # partials halfway between the harmonics give a caw its rasp
            k = 0.5
            while v['sub'] and k * base < v['top']:
                partials.append((k, v['sub'] * gain(k * base) * k ** -v['tilt']))
                k += 1
        phase += TAU * contour(v['pitch'], u) * lift * (1 + v['jitter'] * jit[i]) / RATE
        phases[i] = phase
        harm[i] = sum(a * math.sin(m * phase) for m, a in partials)
    harm = unit_rms(harm)

    att, rel = v['attack'], v['release']
    out = [0.0] * n
    for i in range(n):
        t = i / RATE
        if t < att:
            e = 0.5 - 0.5 * math.cos(math.pi * t / att)
        elif t < dur - rel:
            e = 1 - 0.15 * (t - att) / max(1e-6, dur - att - rel)
        else:
            e = 0.85 * (0.5 + 0.5 * math.cos(math.pi * min(1.0, (t - dur + rel) / rel)))
        a = e * max(0.0, 1 + 0.5 * v['shimmer'] * shim[i])
        out[i] = a * (harm[i] + v['noise'] * noise[i] * (0.6 + 0.4 * math.cos(phases[i])))
    tick, prev = int(0.006 * RATE), 0.0
    for i in range(min(tick, n)):
        w = rng.gauss(0, 1)
        out[i] += v['click'] * (w - prev) * (1 - i / tick)
        prev = w
    peak = max(abs(x) for x in out) or 1.0
    d = v['drive']
    return [math.tanh(d * x / peak) / math.tanh(d) for x in out]


def lowpass(x, fc):
    a = 1 - math.exp(-TAU * fc / RATE)
    y, out = 0.0, []
    for v in x:
        y += a * (v - y)
        out.append(y)
    return out


def reverb(x, rt60=1.1, wet=0.45):
    n = len(x)
    acc = [0.0] * n
    for d in (1215, 1293, 1390, 1476):
        g = 10 ** (-3 * d / (rt60 * RATE))
        buf = [0.0] * n
        for i in range(n):
            buf[i] = x[i] + (g * buf[i - d] if i >= d else 0.0)
            acc[i] += buf[i] / 4
    y = acc
    for d, g in ((605, 0.5), (480, 0.5)):
        out = [0.0] * n
        for i in range(n):
            out[i] = -g * y[i] + (y[i - d] + g * out[i - d] if i >= d else 0.0)
        y = out
    return [(1 - wet) * a + wet * b for a, b in zip(x, y)]


def crow(length, events, peak, distant):
    buf = [0.0] * round(length * RATE)
    for start, voice, seed in events:
        a = round(start * RATE)
        for i, s in enumerate(call(voice, seed)):
            if a + i < len(buf):
                buf[a + i] += s
    if distant:
        buf = reverb(lowpass(buf, 2000))
    top = max(abs(x) for x in buf)
    return [x * peak / top for x in buf]


def trim(buf):
    end = max(i for i, x in enumerate(buf) if abs(x) > 1e-4)
    return buf[:end + round(0.01 * RATE)]


def encode(buf, path, codec):
    subprocess.run(
        ['ffmpeg', '-v', 'error', '-y', '-f', 'f32le', '-ar', str(RATE), '-ac', '1', '-i', '-',
         '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact', *codec, path],
        input=struct.pack('<%df' % len(buf), *buf),
        check=True,
    )
    print('%-50s %6d bytes' % (os.path.relpath(path, ROOT), os.path.getsize(path)))


def main():
    os.makedirs(RAW, exist_ok=True)
    os.makedirs(WEB, exist_ok=True)
    themes = {
        'classic': {name: classic(*spec) for name, spec in CLASSIC.items()},
        'crow': {name: crow(*spec) for name, spec in CROW_THEME.items()},
    }
    for theme, sounds in themes.items():
        for name, buf in sounds.items():
            gain = NOTIFY_GAIN if name in ('ring', 'message') else 1.0
            encode([x * gain for x in buf], os.path.join(RAW, '%s_%s.ogg' % (theme, name)), ['-c:a', 'libvorbis', '-q:a', '5'])
    for name, buf in themes['crow'].items():
        encode(trim(buf), os.path.join(WEB, 'crow-%s.mp3' % name), ['-c:a', 'libmp3lame', '-b:a', '96k'])


if __name__ == '__main__':
    main()
