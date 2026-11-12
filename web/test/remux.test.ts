// node --experimental-strip-types test/remux.test.ts
import { ALL_FORMATS, BlobSource, Input } from 'mediabunny'
import { remux } from '../src/lib/remux.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// shared with the android MetadataTest and the e2e test: a location in moov/udta, an encoder tag, a 2020 creation time and a quarter-turn rotation
const LOCATION = '+40.6892-074.0445/'

async function probe(b: Uint8Array) {
  const input = new Input({ source: new BlobSource(new Blob([b])), formats: ALL_FORMATS })
  try {
    const video = await input.getPrimaryVideoTrack()
    return { tags: await input.getMetadataTags(), rotation: await video?.getRotation(), audio: !!(await input.getPrimaryAudioTrack()) }
  } finally {
    input.dispose()
  }
}

function created(b: Uint8Array) {
  const i = Buffer.from(b).indexOf('mvhd')
  const v = new DataView(b.buffer, b.byteOffset, b.byteLength)
  return b[i + 4] === 1 ? Number(v.getBigUint64(i + 8)) : v.getUint32(i + 8)
}

for (const name of ['location.mp4', 'location-vp9.mp4']) {
  const src = readFileSync(new URL(`../../testdata/media/${name}`, import.meta.url))
  const before = await probe(src)
  assert.equal(before.tags.raw?.['\xA9xyz'], LOCATION, name)
  assert.equal(before.rotation, 270, name)

  const blob = await remux(new Blob([src], { type: 'video/mp4' }))
  assert.ok(blob, name)
  assert.equal(blob.type, 'video/mp4', name)
  const out = new Uint8Array(await blob.arrayBuffer())
  const text = Buffer.from(out).toString('latin1')
  assert.ok(!text.includes(LOCATION) && !text.includes('\xA9xyz'), `${name}: location`)
  assert.ok(!text.includes('Lavf'), `${name}: source tags`)
  assert.notEqual(created(out), created(src), `${name}: recording time`)
  const after = await probe(out)
  assert.equal(after.tags.raw?.['\xA9xyz'], undefined, name)
  assert.equal(after.rotation, before.rotation, name)
  assert.ok(after.audio, name)
  console.log(`ok  remux of ${name} drops location, tags and recording time, keeps rotation and audio`)
}
