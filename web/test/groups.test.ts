// node --experimental-strip-types test/groups.test.ts
import { applyState, mergeGroup, digestList, MAX_ENTRIES, type Entry, type Group } from '../src/lib/groups.ts'
import { groupToken, groupMessageId, membershipDigest, unb64 } from '../src/lib/crypto.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// shared with the android GroupsTest
const V = JSON.parse(readFileSync(new URL('../../testdata/groups.json', import.meta.url), 'utf8'))

assert.equal(await groupToken(unb64(V.token.secret), V.token.gen), V.token.token)
console.log('ok  group token')
assert.equal(await groupMessageId(unb64(V.id.secret), V.id.trigger, V.id.member), V.id.id)
console.log('ok  derived message id')
const list = digestList({ name: { v: 'x', u: 0 }, ttl: { v: 1, u: 0 }, members: V.digest.members })
assert.equal(list, V.digest.list)
assert.equal(await membershipDigest(list), V.digest.sv)
console.log('ok  membership digest')

for (const c of V.apply) {
  const r = applyState(c.local, c.incoming, c.sender, c.now, c.bootstrap ?? false)
  if (c.result === null) {
    assert.equal(r, null, c.name)
  } else {
    assert.ok(r, c.name)
    assert.deepEqual(r.state, c.result.state, c.name)
    assert.deepEqual([...r.added].sort(), [...c.result.added].sort(), c.name)
    assert.deepEqual([...r.gone].sort(), [...c.result.gone].sort(), c.name)
    assert.equal(r.renamed, c.result.renamed, c.name)
    assert.equal(r.timer, c.result.timer, c.name)
  }
  console.log(`ok  ${c.name}`)
}

for (const c of V.merge) {
  assert.deepEqual(mergeGroup(c.a, c.b, c.now), c.result, c.name)
  assert.deepEqual(mergeGroup(c.b, c.a, c.now), c.result, c.name)
  console.log(`ok  ${c.name}`)
}

const id = (n: number) => `e0000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}`
const local = V.apply[0].local
const many: Record<string, Entry> = {}
for (let i = 0; i < MAX_ENTRIES - 1; i++) many[id(i)] = { u: 1, gone: true }
assert.equal(applyState(local, { members: many }, V.apply[0].sender, V.apply[0].now), null)
console.log(`ok  more than ${MAX_ENTRIES} entries are refused`)

// merges between own devices give the same result in either order
const base: Group = V.merge[0].a
const pick = <T>(xs: T[]) => xs[Math.floor(Math.random() * xs.length)]
const card = V.merge[0].a.members[Object.keys(V.merge[0].a.members)[0]].c
function random(): Group {
  const members: Record<string, Entry> = {}
  for (const m of [base.me, id(1), id(2), id(3)]) {
    const r = Math.random()
    if (r < 0.25) continue
    members[m] = r < 0.45 ? { u: pick([1, 2]), gone: true } : { u: pick([1, 2]), c: { ...card, id: m, gen: pick([0, 1]), link_id: pick([id(8), id(9)]) } }
  }
  const g: Group = {
    ...base,
    members,
    name: { v: pick(['a', 'b']), u: pick([1, 2]) },
    ttl: { v: pick([60, 120]), u: pick([1, 2]) },
    own: { ...base.own, link_id: pick([id(8), id(9)]), gen: pick([0, 1]) },
    invites: Object.fromEntries([id(5), id(6)].filter(() => Math.random() < 0.5).map((k) => [k, { member: k, expires: pick([1, 2e9, 3e9]) }])),
  }
  if (Math.random() < 0.5) g.inviter = { contact: 'k', member: id(1) }
  return g
}
for (let i = 0; i < 500; i++) {
  const a = random()
  const b = random()
  assert.deepEqual(mergeGroup(a, b, 1e12), mergeGroup(b, a, 1e12))
}
console.log('ok  merge order does not matter')
