import { chromium } from 'playwright'
import { mkdirSync } from 'fs'
import { generateKeyPairSync, randomUUID, sign } from 'crypto'
mkdirSync('/out', { recursive: true })
const B = 'http://localhost:8080'
// second server, used by carol
const B2 = 'http://localhost:8081'
const browser = await chromium.launch({
  args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
})
let rawMic = 0
const ctx = async (name) => {
  const c = await browser.newContext({ permissions: ['microphone'], viewport: { width: 1100, height: 700 } })
  // tracks websockets and peer connections so tests can reach them
  await c.addInitScript(() => {
    const W = window.WebSocket
    window.__ws = []
    window.WebSocket = class extends W {
      constructor(...a) {
        super(...a)
        window.__ws.push(this)
      }
    }
    const P = window.RTCPeerConnection
    window.__pcs = []
    window.RTCPeerConnection = class extends P {
      constructor(...a) {
        super(...a)
        window.__pcs.push(this)
      }
    }
  })
  const p = await c.newPage()
  p.on('console', m => { if (m.type() === 'error' && !m.text().includes('401')) console.log(`${name} console:`, m.text()) })
  p.on('console', m => { if (m.text().includes('noise suppression off')) { rawMic++; console.log(`${name} console:`, m.text()) } })
  p.on('pageerror', e => console.log(`${name} pageerror:`, e.message))
  if (process.env.DEBUG) p.on('response', (r) => { if (r.status() >= 400 || r.url().includes('/api/link/') || r.url().includes('/api/vault')) console.log(`${name} http ${r.status()} ${r.request().method()} ${r.url()}`) })
  if (process.env.DEBUG) p.on('console', (m) => { if (m.type() === 'warning' || m.type() === 'log') console.log(`${name} ${m.type()}:`, m.text()) })
  return p
}
const step = (s) => console.log('--', s)
// a contact's row by its exact name; group previews also mention members
const row = (p, name) => p.locator('.caw-row', { has: p.locator('strong', { hasText: new RegExp(`^${name}$`) }) })
const vaultPut = (p) => p.waitForResponse((r) => r.url().endsWith('/api/vault') && r.request().method() === 'PUT')
const PW = { alice: 'amber kettle orbit', alice2: 'violet canyon drum', bobTemp: 'temp-pass-2', bob: 'maple tunnel glow', carol: 'cedar violin dusk' }
const a = await ctx('alice')
let b, c, a2
process.on('uncaughtException', async (e) => {
  console.log('FAIL', e.message.split('\n').slice(0, 6).join('\n'))
  for (const [n, p] of [['alice', a], ['bob', b], ['carol', c], ['alice2', a2]]) {
    if (p) await p.screenshot({ path: `/out/fail-${n}.png` }).catch(() => {})
  }
  process.exit(1)
})

await a.goto(`${B}/invite/${process.env.SETUP}`)
await a.getByLabel('Username').fill('alice')
await a.getByLabel('Display name').fill('Alice')
await a.getByLabel('Password', { exact: true }).fill('password1')
await a.getByLabel('Confirm password').fill('password1')
await a.getByRole('button', { name: 'Create account' }).click()
await a.getByText('Use at least 12 characters.').waitFor()
await a.getByLabel('Password', { exact: true }).fill(PW.alice)
await a.getByLabel('Confirm password').fill(PW.alice)
await a.getByRole('button', { name: 'Create account' }).click()
await a.getByRole('button', { name: /Alice/ }).click()
await a.screenshot({ path: '/out/menu.png' })
await a.getByRole('menuitem', { name: 'Admin settings' }).click()
step('weak password refused; alice registered via setup invite, is admin')

await a.getByLabel('Username').fill('bob')
await a.getByLabel('Display name').fill('Bob')
await a.getByLabel('Temporary password').fill(PW.bobTemp)
await a.getByRole('button', { name: 'Add user' }).click()
await a.getByText('@bob').waitFor()
step('admin created bob')

const signIn = async (p, user, pass, choose) => {
  await p.getByLabel('Username').fill(user)
  await p.getByLabel('Password').fill(pass)
  const ready = p.waitForResponse((r) => r.url().includes('/api/mail'))
  await p.getByRole('button', { name: 'Sign in' }).click()
  if (choose) {
    await p.getByRole('heading', { name: 'Choose a password' }).waitFor()
    await p.getByLabel('New password').fill(choose)
    await p.getByLabel('Confirm password').fill(choose)
    await p.getByRole('button', { name: 'Continue' }).click()
  }
  await ready
}
const login = async (name, user, pass, choose) => {
  const p = await ctx(name)
  await p.goto(B)
  await signIn(p, user, pass, choose)
  return p
}
b = await login('bob', 'bob', PW.bobTemp, PW.bob)
await a.goto(B)
await a.waitForResponse((r) => r.url().includes('/api/mail'))
if ((await b.locator('.caw-row').count()) !== 0) throw new Error('a new account should have no contacts')
step('bob signed in with the temporary password and chose his own; no contacts until paired, no user directory')

// alice shows her code; the other side pastes it
const showCode = async (p) => {
  await p.getByRole('button', { name: 'Add contact' }).first().click()
  const t = p.getByLabel('Pairing code text')
  await t.waitFor()
  await p.locator('.qr svg').waitFor()
  return (await t.inputValue()).trim()
}
const enterCode = async (p, code) => {
  await p.getByRole('button', { name: 'Add contact' }).first().click()
  await p.getByRole('tab', { name: 'Enter a code' }).click()
  await p.getByLabel('Code').fill(code)
  await p.getByRole('button', { name: 'Add contact', exact: true }).last().click()
  await p.getByText(/Waiting for .* to accept/).first().waitFor()
  return (await p.locator('.add-contact .words').textContent()).trim()
}
const respell = (code) => {
  const o = JSON.parse(Buffer.from(code.slice('caw:2:'.length), 'base64url'))
  o.s = o.s.toUpperCase() + '/'
  return 'caw:2:' + Buffer.from(JSON.stringify(o)).toString('base64url')
}

let code = await showCode(a)
const bobWords = await enterCode(b, code)
await a.getByRole('heading', { name: 'Bob wants to add you' }).waitFor()
const aliceWords = (await a.locator('.dialog.prompt .words').textContent()).trim()
if (aliceWords !== bobWords || aliceWords.split(' ').length !== 6) throw new Error(`words differ: ${aliceWords} / ${bobWords}`)
await b.getByRole('button', { name: 'Done' }).click()
await a.getByRole('button', { name: 'Accept' }).click()
await b.getByText('Contact added.').waitFor({ timeout: 20000 })
await a.locator('.caw-row', { hasText: 'Bob' }).waitFor({ timeout: 20000 })
await b.locator('.caw-row', { hasText: 'Alice' }).waitFor()
step(`paired by code, words match on both sides: ${aliceWords}`)

await a.locator('.caw-row', { hasText: 'Bob' }).locator('.caw-item').click()
await a.getByPlaceholder('Message to Bob').fill('hey bob')
await a.keyboard.press('Enter')
await b.locator('.bubble', { hasText: 'hey bob' }).waitFor()
await b.getByPlaceholder('Message to Alice').fill('hi alice')
await b.keyboard.press('Enter')
await a.locator('.bubble', { hasText: 'hi alice' }).waitFor()
step('sealed messages both ways over links')

const stored = await b.evaluate(async () => (await (await fetch('/api/mail')).json()).mail)
if (!stored.length || JSON.stringify(stored).includes('hey bob')) throw new Error('server copy is not ciphertext')
await a.reload()
await a.locator('.caw-row', { hasText: 'Bob' }).locator('.caw-item').click()
await a.locator('.bubble', { hasText: 'hey bob' }).waitFor()
await a.locator('.bubble', { hasText: 'hi alice' }).waitFor()
step(`server holds ${stored.length} message(s) for bob, ciphertext only; history reloads from both servers' copies`)

await b.getByRole('button', { name: /Bob/ }).click()
await b.getByRole('menuitem', { name: 'Sign out' }).click()
await signIn(b, 'bob', PW.bob)
await b.locator('.caw-row', { hasText: 'Alice' }).locator('.caw-item').click()
await b.locator('.bubble', { hasText: 'hey bob' }).waitFor()
await b.getByPlaceholder('Message to Alice').fill('back again')
await b.keyboard.press('Enter')
await a.locator('.bubble', { hasText: 'back again' }).waitFor()
step('signing out clears the device; signing back in restores contacts from the vault')

c = await ctx('carol')
await c.goto(`${B2}/invite/${process.env.SETUP2}`)
await c.getByLabel('Username').fill('carol')
await c.getByLabel('Display name').fill('Carol')
await c.getByLabel('Password', { exact: true }).fill(PW.carol)
await c.getByLabel('Confirm password').fill(PW.carol)
await c.getByRole('button', { name: 'Create account' }).click()
await c.getByRole('button', { name: 'Add contact' }).first().waitFor()
step('carol registered on a second server')
await c.getByRole('button', { name: 'Add contact' }).first().click()
await c.getByRole('tab', { name: 'Enter a code' }).click()
await c.getByLabel('Code').fill(code)
await c.getByRole('button', { name: 'Add contact', exact: true }).last().click()
await c.getByText('This code has expired or was already used.').waitFor()
await c.locator('.dialog-head').getByRole('button', { name: 'Close' }).click()
step('a used code is refused')

await a.locator('.dialog-head').getByRole('button', { name: 'Close' }).click().catch(() => {})
// before alice accepts, carol's app sends only a name, and alice's app sends nothing to carol's server
const toCarol = []
const watch = (r) => r.url().startsWith(B2) && toCarol.push(r.url())
a.on('request', watch)
code = await showCode(a)
const carolWords = await enterCode(c, code)
await c.getByRole('button', { name: 'Done' }).click()
await c.locator('.caw-row', { hasText: 'Alice' }).waitFor()
if (!(await c.locator('.composer textarea').isDisabled())) throw new Error('composer should be locked while pending')
await a.getByRole('heading', { name: 'Carol wants to add you' }).waitFor()
if ((await a.locator('.dialog.prompt .words').textContent()).trim() !== carolWords) throw new Error('words differ')
const requests = await a.evaluate(async () => (await (await fetch('/api/links')).json()).filter((l) => l.state === 'pending'))
if (requests.length !== 1 || requests[0].peer_server || requests[0].peer_account) throw new Error(`alice's server knows more than a request: ${JSON.stringify(requests)}`)
let saved = vaultPut(c)
await a.getByRole('button', { name: 'Decline' }).click()
await c.getByText("Alice didn't accept.").waitFor({ timeout: 20000 })
await c.locator('.caw-row', { hasText: 'Alice' }).waitFor({ state: 'detached' })
await saved
a.off('request', watch)
if (toCarol.length) throw new Error(`alice's app contacted carol's server: ${toCarol}`)
step("pending request locks the composer; alice sees carol's name and the words but nothing about her server, and her app never contacts it; decline ends the request for carol")

// carol signs in again before alice accepts: the request comes from the vault and her new device completes it
code = await showCode(a)
saved = vaultPut(c)
await enterCode(c, respell(code))
await c.getByRole('button', { name: 'Done' }).click()
await saved
await c.getByRole('button', { name: /Carol/ }).click()
await c.getByRole('menuitem', { name: 'Sign out' }).click()
await signIn(c, 'carol', PW.carol)
await c.locator('.caw-row', { hasText: 'Alice' }).waitFor()
await a.getByRole('button', { name: 'Accept' }).click()
await c.getByText('Alice accepted.').waitFor({ timeout: 20000 })
await a.locator('.caw-row', { hasText: 'Carol' }).waitFor({ timeout: 20000 })
await c.locator('.caw-row', { hasText: 'Alice' }).locator('.caw-item').click()
await c.getByPlaceholder('Message to Alice').fill('hello from the other server')
await c.keyboard.press('Enter')
await a.locator('.caw-row', { hasText: 'Carol' }).locator('.caw-item').click()
await a.locator('.bubble', { hasText: 'hello from the other server' }).waitFor()
await a.getByPlaceholder('Message to Carol').fill('got it across servers')
await a.keyboard.press('Enter')
await c.locator('.bubble', { hasText: 'got it across servers' }).waitFor()
await a.getByRole('button', { name: 'Call', exact: true }).click()
await c.getByRole('button', { name: 'Answer' }).click()
await a.locator('.callbar', { hasText: /\d:\d\d/ }).waitFor({ timeout: 15000 })
await c.locator('.callbar', { hasText: /\d:\d\d/ }).waitFor({ timeout: 15000 })
await a.getByRole('button', { name: 'Hang up' }).click()
await c.locator('.callbar').waitFor({ state: 'detached' })
step("carol (server 2) and alice (server 1): request from a code with alice's address respelled (capitals, trailing slash), completed by carol's next device after alice accepted; messages both ways, call connected")

await a.locator('.caw-row', { hasText: 'Bob' }).locator('.caw-item').click()
await a.getByPlaceholder('Message to Bob').fill('half typed')
await a.locator('.caw-row', { hasText: 'Carol' }).locator('.caw-item').click()
await a.getByPlaceholder('Message to Carol').waitFor()
await a.locator('.caw-row', { hasText: 'Bob' }).locator('.caw-item').click()
if ((await a.getByPlaceholder('Message to Bob').inputValue()) !== 'half typed') throw new Error('draft not kept per caw')
await a.getByPlaceholder('Message to Bob').fill('')
step('draft kept per caw')

await a.getByLabel('Search contacts').fill('car')
await a.locator('.caw-row', { hasText: 'Bob' }).waitFor({ state: 'detached' })
await a.locator('.caw-row', { hasText: 'Carol' }).waitFor()
await a.getByLabel('Search contacts').fill('')
step('search filters the contact list')

await a.locator('.caw-row', { hasText: 'Carol' }).locator('.caw-item').click()
saved = vaultPut(a)
await a.getByRole('button', { name: 'Add favorite' }).click()
await saved
await a.reload()
await a.locator('.caw-row', { hasText: 'Carol' }).locator('.fav-mark').waitFor()
await a.goto(B)
await a.locator('.caw-row', { hasText: 'Carol' }).waitFor()
await a.waitForTimeout(1000)
const order = await a.locator('.caw-row strong').allTextContents()
if (order.join() !== 'Carol,Bob') throw new Error(`open caws should sort by latest message: ${order}`)
step(`favorite survives reload; open caws by latest message (${order.join(', ')})`)
await a.getByRole('button', { name: 'Message Bob' }).click()
await a.waitForFunction(() => document.activeElement?.getAttribute('placeholder') === 'Message to Bob')
step('quick message focuses the composer')
await a.locator('.contact-btn').click()
await a.getByLabel('Name', { exact: true }).fill('Bobby')
saved = vaultPut(a)
await a.getByRole('button', { name: 'Save' }).click()
await a.locator('.caw-row', { hasText: 'Bobby' }).waitFor()
await saved
await a.reload()
await a.locator('.caw-row', { hasText: 'Bobby' }).locator('.caw-item').click()
await a.locator('.caw-head strong', { hasText: 'Bobby' }).waitFor()
await a.locator('.contact-btn').click()
await a.getByRole('button', { name: 'Reset' }).click()
await a.locator('.caw-head strong', { hasText: /^Bob$/ }).waitFor()
step('contact name saved, survives reload, resets')

await a.getByRole('button', { name: '1 day' }).click()
await a.getByRole('menuitemradio', { name: '1 hour' }).click()
await b.getByText('Alice set messages to expire after 1 hour').waitFor()
await b.getByRole('button', { name: '1 hour' }).waitFor()
step('timer change reached bob')

await a.context().setOffline(true)
await a.getByPlaceholder('Message to Bob').fill('sent while offline')
await a.keyboard.press('Enter')
await a.locator('.msg.mine', { hasText: 'sent while offline' }).locator('.meta', { hasText: 'Sending...' }).waitFor()
await a.waitForTimeout(1500)
await a.context().setOffline(false)
await b.locator('.bubble', { hasText: 'sent while offline' }).waitFor({ timeout: 90000 })
if ((await b.locator('.bubble', { hasText: 'sent while offline' }).count()) !== 1) throw new Error('outbox sent a duplicate')
step('offline message waited in the outbox, delivered once')

const blockSends = (route) => (route.request().method() === 'POST' ? route.abort() : route.continue())
await a.route('**/api/link/mail', blockSends)
await a.getByPlaceholder('Message to Bob').fill('survives a reload')
await a.keyboard.press('Enter')
await a.locator('.msg.mine', { hasText: 'survives a reload' }).locator('.meta', { hasText: 'Sending...' }).waitFor()
await a.waitForTimeout(500)
const outboxStored = await a.evaluate(() => Object.entries(localStorage).filter(([k]) => k.startsWith('caw.outbox.')).map(([, v]) => v).join(''))
if (!outboxStored || outboxStored.includes('survives')) throw new Error('outbox not stored, or stored in the clear')
await a.unroute('**/api/link/mail', blockSends)
await a.reload()
await a.locator('.caw-row', { hasText: 'Bob' }).locator('.caw-item').click()
await b.locator('.bubble', { hasText: 'survives a reload' }).waitFor({ timeout: 30000 })
if ((await b.locator('.bubble', { hasText: 'survives a reload' }).count()) !== 1) throw new Error('outbox sent a duplicate after reload')
step('outbox kept encrypted across a reload, delivered once')

await a.evaluate(async () => {
  const c = document.createElement('canvas')
  c.width = 2400
  c.height = 1200
  const g = c.getContext('2d')
  g.fillStyle = '#c33'
  g.fillRect(0, 0, 2400, 1200)
  g.fillStyle = '#fff'
  g.font = '300px sans-serif'
  g.fillText('caw', 900, 700)
  const blob = await new Promise((r) => c.toBlob(r, 'image/png'))
  const dt = new DataTransfer()
  dt.items.add(new File([blob], 'big.png', { type: 'image/png' }))
  const input = document.querySelector('.composer input[type=file]')
  input.files = dt.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
})
await a.locator('.attachment', { hasText: /Photo/ }).waitFor()
await a.getByPlaceholder('Message to Bob').fill('look at this')
await a.keyboard.press('Enter')
await a.locator('.attachment').waitFor({ state: 'detached' })
const img = b.locator('.media img:not(.blur)')
await img.waitFor({ timeout: 15000 })
await b.locator('.bubble', { hasText: 'look at this' }).waitFor()
const nat = await img.evaluate((el) => [el.naturalWidth, el.naturalHeight, el.src.slice(0, 5)])
if (nat[0] !== 1600 || nat[1] !== 800 || nat[2] !== 'blob:') throw new Error(`photo not resized or not decrypted: ${nat}`)
step('photo sent: compressed to 1600x800, decrypted on the other side')

const blobCheck = await b.evaluate(async () => (await fetch('/api/blobs/00000000-0000-4000-8000-000000000000')).status)
const carolBlob = await c.evaluate(async () => {
  const id = document.querySelector('.media img')?.src
  return (await fetch('/api/blobs/00000000-0000-4000-8000-000000000001')).status
})
if (blobCheck !== 404 || carolBlob !== 404) throw new Error(`blob access: ${blobCheck} ${carolBlob}`)
step('attachments: unknown ids 404 for owners')

const vlog = []
a.on('console', (m) => { if (m.text().includes('transcode')) vlog.push(m.text()) })
await a.evaluate(async () => {
  const c = document.createElement('canvas')
  c.width = 1280
  c.height = 720
  const g = c.getContext('2d')
  const rec = new MediaRecorder(c.captureStream(30), { mimeType: 'video/webm' })
  const chunks = []
  rec.ondataavailable = (e) => chunks.push(e.data)
  rec.start()
  for (let i = 0; i < 60; i++) {
    g.fillStyle = `hsl(${i * 6}, 70%, 50%)`
    g.fillRect(0, 0, 1280, 720)
    await new Promise((r) => setTimeout(r, 33))
  }
  rec.stop()
  await new Promise((r) => (rec.onstop = r))
  const dt = new DataTransfer()
  dt.items.add(new File(chunks, 'clip.webm', { type: 'video/webm' }))
  const input = document.querySelector('.composer input[type=file]')
  input.files = dt.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
})
await a.locator('.attachment', { hasText: /Video/ }).waitFor({ timeout: 60000 })
const vinfo = await a.locator('.attachment').textContent()
await a.keyboard.press('Enter')
await a.locator('.attachment').waitFor({ state: 'detached' })
await b.locator('.media-btn[aria-label="Play video"]').last().click()
const vid = b.locator('.media video')
await vid.waitFor({ timeout: 15000 })
await b.waitForFunction(() => document.querySelector('.media video')?.readyState >= 1, null, { timeout: 15000 })
const vmeta = await vid.evaluate((el) => [el.videoWidth, el.videoHeight, Math.round(el.duration)])
step(`video sent (${vinfo.replace(/\s+/g, ' ').trim()}), plays on the other side at ${vmeta[0]}x${vmeta[1]}${vlog.length ? ', ' + vlog.join(' ') : ''}`)

// fixtures from testdata/media: gps, camera and encoder tags that must not reach the other side
const received = (el, marks) => fetch(el.src).then(async (r) => {
  const t = new TextDecoder('latin1').decode(await r.arrayBuffer())
  return { found: marks.filter((m) => t.includes(m)), vp9: t.includes('vp09') }
})
const sendFixture = async (file, kind, text) => {
  await a.locator('.composer input[type=file]').setInputFiles(`/media/${file}`)
  await a.locator('.attachment', { hasText: kind }).waitFor({ timeout: 60000 })
  await a.getByPlaceholder('Message to Bob').fill(text)
  await a.keyboard.press('Enter')
  await a.locator('.attachment').waitFor({ state: 'detached' })
  return b.locator('.msg', { hasText: text })
}
const exifMsg = await sendFixture('location.jpg', /Photo/, 'photo with exif')
const exifImg = exifMsg.locator('.media img:not(.blur)')
await exifImg.waitFor({ timeout: 15000 })
const photoLeft = await exifImg.evaluate(received, ['Exif', 'EXIF', 'CawTest', 'Lavc'])
if (photoLeft.found.length) throw new Error(`photo kept metadata: ${photoLeft.found}`)
// the test browser cannot decode H.264 or AAC, nor encode H.264, so this video is remuxed
const gpsMsg = await sendFixture('location-vp9.mp4', /Video/, 'video with a location')
await gpsMsg.locator('.media-btn[aria-label="Play video"]').click()
const gpsVid = gpsMsg.locator('.media video')
await gpsVid.waitFor({ timeout: 15000 })
const videoLeft = await gpsVid.evaluate(received, ['+40.6892-074.0445/', '\xA9xyz', 'Lavf'])
if (videoLeft.found.length) throw new Error(`video kept metadata: ${videoLeft.found}`)
step(`photo and video metadata dropped before sending (video ${videoLeft.vp9 ? 'remuxed' : 'transcoded'})`)

// stored 96x48 with a red block top left and EXIF orientation 6
const rotMsg = await sendFixture('rotated.jpg', /Photo/, 'photo with an orientation tag')
const rotImg = rotMsg.locator('.media img:not(.blur)')
await rotImg.waitFor({ timeout: 15000 })
const upright = await rotImg.evaluate(async (el) => {
  const bmp = await createImageBitmap(await (await fetch(el.src)).blob())
  const g = new OffscreenCanvas(bmp.width, bmp.height).getContext('2d')
  g.drawImage(bmp, 0, 0)
  const red = (x, y) => {
    const p = g.getImageData(x, y, 1, 1).data
    return p[0] > 200 && p[1] < 90 && p[2] < 90
  }
  return `${bmp.width}x${bmp.height}${red(6, 6) ? ' top left' : ''}${red(bmp.width - 7, 6) ? ' top right' : ''}`
})
if (upright !== '48x96 top right') throw new Error(`photo not turned upright: ${upright}`)
step('a photo stored sideways with an EXIF orientation arrived upright')

const crowUrls = new Set()
b.on('response', (r) => { if (/\/crow-\w+-[\w-]+\.mp3$/.test(r.url())) crowUrls.add(r.url()) })
await b.getByRole('button', { name: /Bob/ }).click()
await b.getByRole('menuitem', { name: 'Account' }).click()
await b.getByRole('radio', { name: 'Crow' }).click()
await b.waitForFunction(() => localStorage.getItem('caw.sounds') === 'crow')
await b.goBack()

await a.getByRole('button', { name: 'Call', exact: true }).click()
await b.getByRole('button', { name: 'Answer' }).waitFor({ timeout: 5000 })
await b.screenshot({ path: '/out/incoming.png' })
await b.getByRole('button', { name: 'Answer' }).click()
await a.locator('.callbar', { hasText: /\d:\d\d/ }).waitFor({ timeout: 15000 })
await b.locator('.callbar', { hasText: /\d:\d\d/ }).waitFor({ timeout: 15000 })
step('call connected on both sides')

const sendCodec = (p) => p.evaluate(() => window.__pcs.at(-1).getSenders()[0].getParameters().codecs[0]?.mimeType)
const codecs = [await sendCodec(a), await sendCodec(b)]
if (codecs.some((m) => m !== 'audio/red')) throw new Error(`audio sent as ${codecs.join(', ')}, not with redundancy`)
step('audio sent with redundancy both ways')

const before = await a.locator('.callbar .small').textContent()
await a.evaluate(() => window.__ws.at(-1).close())
await a.waitForFunction(() => window.__ws.at(-1).readyState === 1 && window.__ws.length > 1, null, { timeout: 15000 })
await a.waitForTimeout(6000)
if (!(await a.locator('.callbar', { hasText: /\d:\d\d/ }).count()) || !(await b.locator('.callbar', { hasText: /\d:\d\d/ }).count())) {
  throw new Error('call did not survive a dropped connection')
}
step(`call survived a dropped connection (was ${before.trim()}, now ${(await a.locator('.callbar .small').textContent()).trim()})`)
await a.waitForTimeout(2500)
await a.screenshot({ path: '/out/in-call.png' })
await b.getByRole('button', { name: 'Mute' }).click()
await b.getByRole('button', { name: 'Unmute' }).waitFor()
await a.getByRole('button', { name: 'Hang up' }).click()
await b.locator('.callbar').waitFor({ state: 'detached' })
await b.locator('.event', { hasText: /Incoming call . \d+:\d\d/ }).waitFor()
await a.locator('.event', { hasText: /Outgoing call . \d+:\d\d/ }).waitFor()
step('hangup propagated, call note on both sides')

for (let i = 0; i < 50 && crowUrls.size < 3; i++) await b.waitForTimeout(100)
const crowPlayed = await b.evaluate(async (urls) => {
  const ctx = new OfflineAudioContext(1, 1, 48000)
  const out = []
  for (const u of urls) out.push(`${u.match(/crow-(\w+)-/)[1]} ${(await ctx.decodeAudioData(await (await fetch(u)).arrayBuffer())).duration.toFixed(2)} s`)
  return out.sort()
}, [...crowUrls])
if (!['hangup', 'message', 'ring'].every((n) => crowPlayed.some((s) => s.startsWith(`${n} `)))) throw new Error(`crow sounds not played: ${crowPlayed}`)
step(`bob chose the crow sounds; selecting, ringing and hanging up loaded and decoded them (${crowPlayed.join(', ')})`)

await a.getByRole('button', { name: 'Call', exact: true }).click()
await b.getByRole('button', { name: 'Decline' }).click()
await a.locator('.event', { hasText: 'Outgoing call, declined' }).waitFor()
await b.locator('.event', { hasText: 'Incoming call, declined' }).waitFor()
step('decline propagated as a sealed call note')

// bob's page alters its answer after signing it; alice must refuse the call
await b.evaluate(() => {
  const send = WebSocket.prototype.send
  WebSocket.prototype.send = function (d) {
    const m = JSON.parse(d)
    if (m.type === 'signal' && m.data?.sdp) m.data.sdp.sdp = m.data.sdp.sdp.replace('a=fingerprint:sha-256 ', 'a=fingerprint:sha-256 00:')
    return send.call(this, JSON.stringify(m))
  }
})
await b.getByRole('button', { name: 'Call', exact: true }).click()
await a.getByRole('button', { name: 'Answer' }).click()
await a.getByText('Call could not be verified').waitFor({ timeout: 15000 })
await a.locator('.callbar').waitFor({ state: 'detached' })
await b.locator('.callbar').waitFor({ state: 'detached', timeout: 15000 })
await b.reload()
await b.getByPlaceholder('Message to Alice').waitFor()
step('a call answer altered after signing was refused')

if (rawMic) throw new Error('noise suppression fell back to the raw mic')
step('calls ran through noise suppression')

await b.screenshot({ path: '/out/caw.png' })

// second browser for alice: no pairing or approval; contacts come from the vault
a2 = await login('alice2', 'alice', PW.alice)
await a.getByText('New device signed in').waitFor({ timeout: 10000 })
await a2.locator('.caw-row', { hasText: 'Bob' }).waitFor({ timeout: 10000 })
await a2.locator('.caw-row', { hasText: 'Carol' }).locator('.fav-mark').waitFor()
step('second device: contacts and favorites came from the vault; the first device got a new-device notice')

await b.getByPlaceholder('Message to Alice').fill('to all of you')
await b.keyboard.press('Enter')
await a2.locator('.caw-row', { hasText: 'Bob' }).locator('.caw-item').click()
await a2.locator('.bubble', { hasText: 'to all of you' }).waitFor()
await a.locator('.bubble', { hasText: 'to all of you' }).waitFor()
await a2.getByPlaceholder('Message to Bob').fill('from my other device')
await a2.keyboard.press('Enter')
await b.locator('.bubble', { hasText: 'from my other device' }).waitFor()
await a.locator('.msg.mine', { hasText: 'from my other device' }).waitFor({ timeout: 10000 })
step("messages reach both of alice's devices; what one sends shows on the other")

await b.getByRole('button', { name: 'Call', exact: true }).click()
await a.getByRole('button', { name: 'Answer' }).waitFor({ timeout: 5000 })
await a2.getByRole('button', { name: 'Answer' }).click()
await b.locator('.callbar', { hasText: /\d:\d\d/ }).waitFor({ timeout: 15000 })
await a.getByRole('button', { name: 'Answer' }).waitFor({ state: 'detached' })
await b.getByRole('button', { name: 'Hang up' }).click()
await a2.locator('.callbar').waitFor({ state: 'detached' })
step('a call rang both devices; answered on one, the other stopped ringing')

// bob (server 1) and carol (server 2) are not contacts of each other
await a.locator('.group-fab').click()
await a.getByLabel('Group name').fill('Friends')
await a.locator('.new-group').getByLabel('Search contacts').fill('bo')
await a.locator('.new-group .pick-row', { hasText: 'Bob' }).click()
await a.locator('.new-group').getByLabel('Search contacts').fill('')
await a.locator('.new-group .pick-row', { hasText: 'Carol' }).click()
await a.locator('.new-group .chip', { hasText: 'Bob' }).waitFor()
await a.getByRole('button', { name: 'Create group' }).click()
await a.locator('.caw-head strong', { hasText: 'Friends' }).waitFor()
for (const p of [b, c]) {
  await p.getByRole('dialog', { name: 'Group invitation' }).waitFor({ timeout: 15000 })
  await p.getByRole('button', { name: 'Join' }).click()
  await p.locator('.event', { hasText: 'You joined the group.' }).waitFor({ timeout: 15000 })
}
await b.locator('.event', { hasText: 'Alice added Carol.' }).waitFor({ timeout: 15000 })
step("alice created a group; bob and carol accepted and joined; carol matched alice's card to the respelled address")

await b.getByPlaceholder('Message to Friends').fill('hello group')
await b.keyboard.press('Enter')
await c.locator('.msg', { hasText: 'hello group' }).locator('.sender', { hasText: 'Bob' }).waitFor({ timeout: 15000 })
await a.locator('.msg', { hasText: 'hello group' }).locator('.sender', { hasText: 'Bob' }).waitFor({ timeout: 15000 })
// carol's copies to alice and bob go through their group links
const carolLinks = []
const capture = (r) => {
  const auth = r.headers()['authorization']
  if (auth?.startsWith('Link ') && r.url().endsWith('/api/link/mail') && r.url().startsWith(B)) carolLinks.push({ url: r.url(), auth })
}
c.on('request', capture)
await c.getByPlaceholder('Message to Friends').fill('hi from the other server')
await c.keyboard.press('Enter')
await b.locator('.msg', { hasText: 'hi from the other server' }).locator('.sender', { hasText: 'Carol' }).waitFor({ timeout: 15000 })
c.off('request', capture)
await a2.locator('.caw-row', { hasText: 'Friends' }).locator('.caw-item').click()
await a2.locator('.bubble', { hasText: 'hi from the other server' }).waitFor({ timeout: 15000 })
step("bob and carol message the group across servers; alice's second device has it from the vault")

await c.evaluate(async () => {
  const cv = document.createElement('canvas')
  cv.width = 800
  cv.height = 400
  const g = cv.getContext('2d')
  g.fillStyle = '#36c'
  g.fillRect(0, 0, 800, 400)
  const blob = await new Promise((r) => cv.toBlob(r, 'image/png'))
  const dt = new DataTransfer()
  dt.items.add(new File([blob], 'group.png', { type: 'image/png' }))
  const input = document.querySelector('.composer input[type=file]')
  input.files = dt.files
  input.dispatchEvent(new Event('change', { bubbles: true }))
})
await c.locator('.attachment', { hasText: /Photo/ }).waitFor()
await c.getByPlaceholder('Message to Friends').fill('a photo')
await c.keyboard.press('Enter')
await c.locator('.attachment').waitFor({ state: 'detached' })
await b.locator('.msg', { hasText: 'a photo' }).locator('.media img:not(.blur)').waitFor({ timeout: 15000 })
await a.locator('.msg', { hasText: 'a photo' }).locator('.media img:not(.blur)').waitFor({ timeout: 15000 })
step("carol's photo, uploaded once to her server, opened for bob and alice")

await c.locator('.caw-head .contact-btn').click()
await c.locator('.group-pop').getByLabel('Name').fill('Family')
await c.locator('.group-pop').getByRole('button', { name: 'Save' }).click()
await a.locator('.event', { hasText: 'Carol renamed the group to Family.' }).waitFor({ timeout: 15000 })
await c.getByRole('button', { name: '1 day' }).click()
await c.getByRole('menuitemradio', { name: '1 hour' }).click()
await b.locator('.event', { hasText: 'Carol set messages to expire after 1 hour.' }).waitFor({ timeout: 15000 })
step('carol renamed the group and changed its timer')

// alice's second device forgets its saved keys and reads nothing until carol was removed and bob has left
let release
const gone = new Promise((r) => (release = r))
await a2.route('**/api/link/devices', async (r) => {
  await gone
  await r.continue().catch(() => {})
})
await a2.evaluate(() => Object.keys(localStorage).filter((k) => k.startsWith('caw.contacts.')).forEach((k) => localStorage.removeItem(k)))
await a2.reload()

if (!carolLinks.length) throw new Error("no group link captured from carol's sends")
await b.locator('.caw-head .contact-btn').click()
const carolRow = b.locator('.group-pop .members li', { hasText: 'Carol' })
await carolRow.getByRole('button', { name: 'Remove' }).click()
await carolRow.getByRole('button', { name: 'Tap again' }).click()
await c.getByText('Bob removed you from Family.').waitFor({ timeout: 15000 })
await c.locator('.caw-row', { hasText: 'Family' }).waitFor({ state: 'detached' })
await a.locator('.event', { hasText: 'Bob removed Carol.' }).waitFor({ timeout: 15000 })
await b.getByPlaceholder('Message to Family').fill('after carol left')
await b.keyboard.press('Enter')
await a.locator('.bubble', { hasText: 'after carol left' }).waitFor({ timeout: 15000 })
await a.reload()
await a.locator('.caw-row', { hasText: 'Family' }).locator('.caw-item').click()
await a.locator('.bubble', { hasText: 'hello group' }).waitFor({ timeout: 15000 })
const { url, auth } = carolLinks[0]
const del = await fetch(url.replace(/\/mail$/, ''), { method: 'DELETE', headers: { authorization: auth } })
const read = await fetch(url, { headers: { authorization: auth } })
if (del.status !== 403 || read.status !== 403) throw new Error(`a group link token deleted or read the link: ${del.status} ${read.status}`)
step('bob removed carol; alice and bob keep messaging; history survives a reload; a group token cannot delete or read a link')

await b.locator('.caw-head .contact-btn').click()
await b.locator('.group-pop').getByRole('button', { name: 'Leave group' }).click()
await b.locator('.group-pop').getByRole('button', { name: 'Tap again to leave' }).click()
await b.locator('.caw-row', { hasText: 'Family' }).waitFor({ state: 'detached' })
await a.locator('.event', { hasText: 'Bob left.' }).waitFor({ timeout: 15000 })
await a.getByPlaceholder('Message to Family').fill('just me now')
await a.keyboard.press('Enter')
await a.locator('.msg.mine', { hasText: 'just me now' }).locator('.meta', { hasText: /left/ }).waitFor({ timeout: 15000 })
await row(a, 'Bob').locator('.caw-item').click()
step('bob left the group')

release()
await a2.locator('.caw-row', { hasText: 'Family' }).locator('.caw-item').click()
for (const t of ['hello group', 'hi from the other server', 'after carol left']) await a2.locator('.bubble', { hasText: t }).waitFor({ timeout: 20000 })
await a2.unroute('**/api/link/devices')
step("alice's second device caught up after carol was removed and bob left, without saved keys: their closed links still listed their devices, so their earlier messages verify")

// alice's server reports a device alice does not have
const raw = (k) => Buffer.from(k.export({ format: 'jwk' }).x, 'base64url')
const b64u = (b) => Buffer.from(b).toString('base64url')
const fakeId = randomUUID()
const fs = generateKeyPairSync('ed25519')
const fe = generateKeyPairSync('x25519')
const created = Math.floor(Date.now() / 1000)
const ts = Buffer.alloc(8)
ts.writeBigUInt64BE(BigInt(created))
const cert = Buffer.concat([Buffer.from('caw-enc-v1'), Buffer.from(fakeId), Buffer.from([0]), raw(fe.publicKey), ts])
const fake = { id: fakeId, sign_pub: b64u(raw(fs.publicKey)), enc_pub: b64u(raw(fe.publicKey)), enc_created_at: created, enc_sig: b64u(sign(null, cert, fs.privateKey)) }
const addFake = async (route) => {
  const r = await route.fetch()
  const j = await r.json()
  j.devices.push(fake)
  await route.fulfill({ response: r, json: j })
}
await b.route('**/api/link/devices', addFake)
await b.reload()
await b.locator('.caw-row', { hasText: 'Alice' }).locator('.caw-item').click()
await b.getByPlaceholder('Message to Alice').fill('who else is reading')
await b.keyboard.press('Enter')
await a.getByText("was also encrypted for a device that isn't yours").waitFor({ timeout: 10000 })
await b.unroute('**/api/link/devices', addFake)
step("a device slipped into alice's list was caught by her own device check")

const vaultBefore = await a.evaluate(async () => (await (await fetch('/api/vault')).json()).wrapped_key)
await a.getByRole('button', { name: "That wasn't me" }).click()
await a.getByRole('dialog', { name: 'Change your password' }).waitFor()
await a.getByLabel('Current password').fill(PW.alice)
await a.getByLabel('New password', { exact: true }).fill(PW.alice2)
await a.getByLabel('Confirm new password').fill(PW.alice2)
await a.getByRole('button', { name: 'Change password' }).click()
await a.getByText('Password changed.').waitFor({ timeout: 15000 })
await a2.getByRole('button', { name: 'Sign in' }).waitFor({ timeout: 15000 })
const vaultAfter = await a.evaluate(async () => (await (await fetch('/api/vault')).json()).wrapped_key)
if (vaultBefore === vaultAfter) throw new Error('vault key was not replaced')
await a2.getByLabel('Username').fill('alice')
await a2.getByLabel('Password').fill(PW.alice)
await a2.getByRole('button', { name: 'Sign in' }).click()
await a2.getByText('Incorrect username or password.').waitFor()
await signIn(a2, 'alice', PW.alice2)
await row(a2, 'Bob').waitFor({ timeout: 10000 })
await a.reload()
await row(a, 'Bob').waitFor()
await row(a, 'Family').locator('.caw-item').click()
await a.locator('.bubble', { hasText: 'just me now' }).waitFor({ timeout: 15000 })
await a.locator('.bubble', { hasText: 'after carol left' }).waitFor()
await row(a, 'Bob').locator('.caw-item').click()
step('"That wasn\'t me" signed the other device out, changed the password and replaced the vault key; the new password unlocks it, groups included')
await a2.close()

await row(a, 'Carol').locator('.caw-item').click()
await a.locator('.contact-btn').click()
await a.getByRole('button', { name: 'Remove contact' }).click()
await a.getByRole('button', { name: 'Tap again to remove' }).click()
await row(a, 'Carol').waitFor({ state: 'detached' })
await c.getByText('Alice is no longer a contact.').waitFor({ timeout: 10000 })
await row(c, 'Alice').waitFor({ state: 'detached' })
step('removing a contact removes it on the other side too')

// alice removes the link without notifying bob; bob's next send finds it gone
const linkId = await a.evaluate(async () => (await (await fetch('/api/links')).json()).find((l) => l.kind === 'contact').id)
await a.evaluate(async (id) => fetch(`/api/links/${id}`, { method: 'DELETE' }), linkId)
await b.getByPlaceholder('Message to Alice').fill('anyone there?')
await b.keyboard.press('Enter')
await b.getByText('Alice is no longer reachable and was removed from your contacts.').waitFor({ timeout: 10000 })
await row(b, 'Alice').waitFor({ state: 'detached' })
step('a gone link removes the contact with a notice')

await browser.close()
console.log('E2E OK')
