import crowHangup from '../assets/sounds/crow-hangup.mp3'
import crowMessage from '../assets/sounds/crow-message.mp3'
import crowRing from '../assets/sounds/crow-ring.mp3'
import crowRingback from '../assets/sounds/crow-ringback.mp3'

export type SoundTheme = 'classic' | 'crow'
type Sound = 'ring' | 'ringback' | 'hangup' | 'message'

const KEY = 'caw.sounds'
const CROW: Record<Sound, string> = { ring: crowRing, ringback: crowRingback, hangup: crowHangup, message: crowMessage }
const EVERY = { classic: { ring: 2500, ringback: 6000 }, crow: { ring: 2500, ringback: 3000 } }

export function getSoundTheme(): SoundTheme {
  try {
    return localStorage.getItem(KEY) === 'crow' ? 'crow' : 'classic'
  } catch {
    return 'classic'
  }
}

export function setSoundTheme(t: SoundTheme) {
  try {
    if (t === 'classic') localStorage.removeItem(KEY)
    else localStorage.setItem(KEY, t)
  } catch {
    // storage unavailable; stays classic
  }
}

class Tones {
  private ctx: AudioContext | null = null
  private timer: number | null = null
  private run = 0
  private buffers = new Map<Sound, Promise<AudioBuffer>>()

  private context() {
    const ctx = (this.ctx ??= new AudioContext())
    if (ctx.state === 'suspended') ctx.resume().catch(() => {})
    return ctx
  }

  private beep(freqs: number[], at: number, dur: number, vol = 0.07) {
    const ctx = this.context()
    const gain = ctx.createGain()
    gain.gain.value = vol
    gain.connect(ctx.destination)
    const t = ctx.currentTime + at
    for (const f of freqs) {
      const o = ctx.createOscillator()
      o.frequency.value = f
      o.connect(gain)
      o.start(t)
      o.stop(t + dur)
    }
  }

  private classic(s: Sound) {
    switch (s) {
      case 'ring':
        this.beep([523, 659], 0, 0.3, 0.12)
        this.beep([523, 659], 0.45, 0.3, 0.12)
        break
      case 'ringback':
        this.beep([440, 480], 0, 2)
        break
      case 'hangup':
        this.beep([480, 620], 0, 0.25)
        this.beep([480, 620], 0.35, 0.25)
        break
      case 'message':
        this.beep([880], 0, 0.12, 0.06)
        this.beep([1175], 0.13, 0.2, 0.06)
    }
  }

  private buffer(s: Sound) {
    let p = this.buffers.get(s)
    if (!p) {
      p = fetch(CROW[s])
        .then((r) => {
          if (!r.ok) throw new Error(r.statusText)
          return r.arrayBuffer()
        })
        .then((b) => this.context().decodeAudioData(b))
      p.catch(() => this.buffers.delete(s))
      this.buffers.set(s, p)
    }
    return p
  }

  // a crow file that fails to load plays the classic sound instead
  private play(s: Sound) {
    if (getSoundTheme() === 'classic') return this.classic(s)
    const run = this.run
    this.buffer(s).then(
      (buf) => {
        if (run !== this.run) return
        const ctx = this.context()
        const src = ctx.createBufferSource()
        src.buffer = buf
        src.connect(ctx.destination)
        src.start()
      },
      () => {
        if (run === this.run) this.classic(s)
      },
    )
  }

  private loop(s: 'ring' | 'ringback') {
    this.stop()
    this.play(s)
    this.timer = window.setInterval(() => this.play(s), EVERY[getSoundTheme()][s])
  }

  ringback() {
    this.loop('ringback')
  }

  ring() {
    this.loop('ring')
  }

  hangup() {
    this.stop()
    this.play('hangup')
  }

  message() {
    this.play('message')
  }

  stop() {
    this.run++
    if (this.timer !== null) clearInterval(this.timer)
    this.timer = null
  }
}

export const tones = new Tones()
