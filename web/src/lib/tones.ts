class Tones {
  private ctx: AudioContext | null = null
  private timer: number | null = null

  private beep(freqs: number[], at: number, dur: number, vol = 0.07) {
    const ctx = (this.ctx ??= new AudioContext())
    if (ctx.state === 'suspended') ctx.resume().catch(() => {})
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

  private loop(fn: () => void, every: number) {
    this.stop()
    fn()
    this.timer = window.setInterval(fn, every)
  }

  ringback() {
    this.loop(() => this.beep([440, 480], 0, 2), 6000)
  }

  ring() {
    this.loop(() => {
      this.beep([523, 659], 0, 0.3, 0.12)
      this.beep([523, 659], 0.45, 0.3, 0.12)
    }, 2500)
  }

  hangup() {
    this.stop()
    this.beep([480, 620], 0, 0.25)
    this.beep([480, 620], 0.35, 0.25)
  }

  message() {
    this.beep([880], 0, 0.12, 0.06)
    this.beep([1175], 0.13, 0.2, 0.06)
  }

  stop() {
    if (this.timer !== null) clearInterval(this.timer)
    this.timer = null
  }
}

export const tones = new Tones()
