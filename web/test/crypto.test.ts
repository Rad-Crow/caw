// node --experimental-strip-types test/crypto.test.ts
import { b64, unb64, hpkeOpen, hpkeSeal, newX25519, newEd25519, sign, verify, pairingWords, messageSigBytes, callSigBytes, INFO, requestKey, sealRequest, openRequest, helloId, requestContactId } from '../src/lib/crypto.ts'
import { argon2id } from 'hash-wasm'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// from server/src/webpush.rs hpke_vector (rust hpke crate)
const V = {
  skR: 't95UAESODeP3F2-yxoye38Lon_Ijq82us7_Zd28Bbs0',
  pkR: 'tarVPutDGeHZEOwEQPhJ0Z4KOqn-O8uRNCvYDkiDV1U',
  w: 'yh9lYMmDB0ihEzkBytN2OU7SEf1Nl9yyccDaIig3iRXLixXnFr8dsoDan4bHYixUDCg8j9SxBYKu-LMcLN_J2Q',
  pt: 'BwcHBwcHBwcHBwcHBwcHBw',
}
const aad = new TextEncoder().encode('message-id')
const skR = await crypto.subtle.importKey('jwk', { kty: 'OKP', crv: 'X25519', d: V.skR, x: V.pkR }, { name: 'X25519' }, false, ['deriveBits'])
assert.equal(b64(await hpkeOpen(skR, unb64(V.pkR), unb64(V.w), INFO, aad)), V.pt)
console.log('ok  opens the rust hpke vector')

const r = await newX25519()
const msg = new TextEncoder().encode('hello')
assert.deepEqual(await hpkeOpen(r.priv, r.pub, await hpkeSeal(r.pub, INFO, aad, msg), INFO, aad), msg)
console.log('ok  seal/open round trip')

const s = await newEd25519()
const sig = await sign(s.priv, msg)
assert.ok(await verify(s.pub, sig, msg))
assert.ok(!(await verify(s.pub, sig, new TextEncoder().encode('hellO'))))
console.log('ok  ed25519 sign/verify')

const words = readFileSync(new URL('../../protocol/words.txt', import.meta.url), 'utf8').trim().split('\n')
// shared with the android CryptoTest
const LINK = '6f1c2a4e-0b7d-4e8a-9c3f-2d5e8a1b7c90'
const w = await pairingWords(LINK, words)
assert.equal(w.join(' '), 'koala blanket plum ankle ribbon bunny')
console.log('ok  pairing words')

const sigText = new TextDecoder().decode(
  messageSigBytes({ id: 'id', from_device: 'dev-a', ttl: 86400, body: 'body', keys: [{ d: 'dev-b', k: 'kb', w: 'wb', o: 'to' }, { d: 'dev-a', k: 'ka', w: 'wa', o: 'from' }] }),
)
assert.equal(sigText, 'caw-message-v1\nid\ndev-a\n86400\nbody\ndev-a:ka:wa:from,dev-b:kb:wb:to')
assert.equal(new TextDecoder().decode(callSigBytes('call-id', 'offer', 'v=0')), 'caw-call-v1\ncall-id\noffer\nv=0')
console.log('ok  signature inputs')

// same parameters as src/lib/kdf.ts
const out = await argon2id({ password: 'correct horse battery staple', salt: unb64('AAECAwQFBgcICQoLDA0ODw'), parallelism: 1, iterations: 3, memorySize: 65536, hashLength: 64, outputType: 'binary' })
assert.equal(b64(out.slice(0, 32)), '9YPGbrDJg_a-DxgB00WA26bOORo13vYf7QQ8Nl3DfFI')
assert.equal(b64(out.slice(32)), '8cKBUJdhXyGHl-1XTHCE7SH9Jgm40LNwa__U3sfmrrg')
console.log('ok  argon2id vector')

const rk = await requestKey(unb64('AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8'))
assert.equal(b64(rk.pub), 'ruLfkL5uTuSU7f1JqZJ_tsVPJO0Ct_GYTLZ6fsMLtE4')
assert.equal(await openRequest(rk, await sealRequest(rk.pub, 'Bob')), 'Bob')
assert.equal(await openRequest(await newX25519(), await sealRequest(rk.pub, 'Bob')), null)
console.log('ok  request key vector; a request opens only with its key')

assert.equal(await helloId('0b1f2a3c-4d5e-4f60-8a71-92b3c4d5e6f7'), '924e8313-800c-8771-997e-450c0ddc8847')
assert.equal(await requestContactId('0b1f2a3c-4d5e-4f60-8a71-92b3c4d5e6f7'), '3aee150e-f8d1-89d8-8682-09b1418af66b')
console.log('ok  hello and contact id vectors')
