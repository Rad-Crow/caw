import { RnnoiseWorkletNode, loadRnnoise } from '@sapphi-red/web-noise-suppressor'
import workletUrl from '@sapphi-red/web-noise-suppressor/rnnoiseWorklet.js?url'
import wasmUrl from '@sapphi-red/web-noise-suppressor/rnnoise.wasm?url'
import simdUrl from '@sapphi-red/web-noise-suppressor/rnnoise_simd.wasm?url'

const KEY = 'caw.denoise'

export function denoiseOn(): boolean {
  try {
    return localStorage.getItem(KEY) !== 'off'
  } catch {
    return true
  }
}

export function setDenoise(on: boolean) {
  try {
    localStorage.setItem(KEY, on ? 'on' : 'off')
  } catch {
    // storage unavailable; default (on) applies
  }
}

export type Mic = { stream: MediaStream; stop(): void }

let wasm: Promise<ArrayBuffer> | null = null

// falls back to the raw mic on any failure, including a suspended context, which would send silence
export async function denoise(raw: MediaStream): Promise<Mic> {
  const plain: Mic = { stream: raw, stop: () => raw.getTracks().forEach((t) => t.stop()) }
  if (!denoiseOn() || typeof AudioWorkletNode === 'undefined') return plain
  let ctx: AudioContext | null = null
  try {
    wasm ??= loadRnnoise({ url: wasmUrl, simdUrl })
    ctx = new AudioContext({ sampleRate: 48000 })
    const [wasmBinary] = await Promise.all([wasm, ctx.audioWorklet.addModule(workletUrl)])
    const node = new RnnoiseWorkletNode(ctx, { wasmBinary, maxChannels: 1 })
    const dest = ctx.createMediaStreamDestination()
    ctx.createMediaStreamSource(raw).connect(node).connect(dest)
    await Promise.race([ctx.resume(), new Promise((r) => setTimeout(r, 1000))])
    if (ctx.state !== 'running') throw new Error(`audio context ${ctx.state}`)
    const c = ctx
    return {
      stream: dest.stream,
      stop: () => {
        plain.stop()
        node.destroy()
        c.close().catch(() => {})
      },
    }
  } catch (e) {
    console.warn('noise suppression off', e)
    wasm = null
    ctx?.close().catch(() => {})
    return plain
  }
}
