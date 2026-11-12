import { ALL_FORMATS, BlobSource, BufferTarget, Conversion, Input, Mp4OutputFormat, Output, WebMOutputFormat } from 'mediabunny'

// copies the primary video and audio into a new file; the source's tags and other metadata are left behind
export async function remux(file: Blob): Promise<Blob | null> {
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS })
  try {
    const audio = await input.getPrimaryAudioTrack()
    for (const format of [new Mp4OutputFormat({ fastStart: 'in-memory' }), new WebMOutputFormat()]) {
      const target = new BufferTarget()
      const conv = await Conversion.init({ input, output: new Output({ format, target }), tracks: 'primary', tags: {}, showWarnings: false })
      const used = conv.utilizedTracks
      if (!conv.isValid || !used.some((t) => t.isVideoTrack()) || (audio && !used.includes(audio))) continue
      await conv.execute()
      if (target.buffer) return new Blob([target.buffer], { type: format.mimeType })
    }
    return null
  } catch (e) {
    console.warn('remux', e)
    return null
  } finally {
    input.dispose()
  }
}
