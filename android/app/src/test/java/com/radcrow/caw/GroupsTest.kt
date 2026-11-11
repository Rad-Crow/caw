package com.radcrow.caw

import com.radcrow.caw.sealed.Crypto
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import kotlin.random.Random

// shared with web/test/groups.test.ts
class GroupsTest {
    private val v = json.parseToJsonElement(File("../../testdata/groups.json").readText()).jsonObject
    private val members = MapSerializer(String.serializer(), Member.serializer())

    private fun str(e: JsonElement?) = e!!.jsonPrimitive.content

    private fun state(e: JsonElement) = json.decodeFromJsonElement(GroupState.serializer(), e)

    private fun group(e: JsonElement) = json.decodeFromJsonElement(Group.serializer(), e)

    private fun ids(e: JsonElement?) = e!!.jsonArray.map { it.jsonPrimitive.content }.sorted()

    @Test
    fun token() {
        val t = v["token"]!!.jsonObject
        assertEquals(str(t["token"]), Crypto.groupToken(Crypto.unb64(str(t["secret"])), t["gen"]!!.jsonPrimitive.long))
    }

    @Test
    fun derivedId() {
        val t = v["id"]!!.jsonObject
        assertEquals(str(t["id"]), Crypto.groupMessageId(Crypto.unb64(str(t["secret"])), str(t["trigger"]), str(t["member"])))
    }

    @Test
    fun digest() {
        val t = v["digest"]!!.jsonObject
        val list = digestList(json.decodeFromJsonElement(members, t["members"]!!))
        assertEquals(str(t["list"]), list)
        assertEquals(str(t["sv"]), Crypto.membershipDigest(list))
    }

    @Test
    fun server() {
        for (raw in v["server"]!!.jsonArray) {
            val c = raw.jsonObject
            assertEquals(str(c["in"]), c["out"]!!.jsonPrimitive.contentOrNull, normServer(str(c["in"])))
        }
    }

    @Test
    fun apply() {
        for (raw in v["apply"]!!.jsonArray) {
            val c = raw.jsonObject
            val name = str(c["name"])
            val bootstrap = c["bootstrap"]?.jsonPrimitive?.boolean ?: false
            val r = applyState(state(c["local"]!!), c["incoming"], str(c["sender"]), c["now"]!!.jsonPrimitive.long, bootstrap)
            val want = c["result"]
            if (want == null || want is JsonNull) {
                assertNull(name, r)
                continue
            }
            assertNotNull(name, r)
            val w = want.jsonObject
            assertEquals(name, w["state"], json.encodeToJsonElement(GroupState.serializer(), r!!.state))
            assertEquals(name, ids(w["added"]), r.added.sorted())
            assertEquals(name, ids(w["gone"]), r.gone.sorted())
            assertEquals(name, w["renamed"]!!.jsonPrimitive.boolean, r.renamed)
            assertEquals(name, w["timer"]!!.jsonPrimitive.boolean, r.timer)
        }
    }

    @Test
    fun merge() {
        for (raw in v["merge"]!!.jsonArray) {
            val c = raw.jsonObject
            val name = str(c["name"])
            val a = group(c["a"]!!)
            val b = group(c["b"]!!)
            val now = c["now"]!!.jsonPrimitive.long
            assertEquals(name, c["result"], json.encodeToJsonElement(Group.serializer(), mergeGroup(a, b, now)))
            assertEquals(name, c["result"], json.encodeToJsonElement(Group.serializer(), mergeGroup(b, a, now)))
        }
    }

    @Test
    fun entryCap() {
        val first = v["apply"]!!.jsonArray[0].jsonObject
        val many = (0 until MAX_ENTRIES - 1).associate { id(it) to json.encodeToJsonElement(Member.serializer(), Member(1, gone = true)) }
        val incoming = JsonObject(mapOf("members" to JsonObject(many)))
        assertNull(applyState(state(first["local"]!!), incoming, str(first["sender"]), first["now"]!!.jsonPrimitive.long))
    }

    // merges between own devices give the same result in either order
    @Test
    fun mergeOrder() {
        val base = group(v["merge"]!!.jsonArray[0].jsonObject["a"]!!)
        val card = base.members.values.first().c!!
        val rnd = Random(7)
        fun <T> pick(vararg xs: T) = xs[rnd.nextInt(xs.size)]
        fun random(): Group {
            val m = LinkedHashMap<String, Member>()
            for (id in listOf(base.me, id(1), id(2), id(3))) {
                val r = rnd.nextDouble()
                if (r < 0.25) continue
                m[id] = if (r < 0.45) Member(pick(1L, 2L), gone = true) else Member(pick(1L, 2L), card.copy(id = id, gen = pick(0L, 1L), linkId = pick(id(8), id(9))))
            }
            val invites = listOf(id(5), id(6)).filter { rnd.nextBoolean() }.associateWith { Invite(it, pick(1L, 2_000_000_000L, 3_000_000_000L)) }
            return base.copy(
                members = m,
                name = Reg(pick("a", "b"), pick(1L, 2L)),
                ttl = Reg(pick(60L, 120L), pick(1L, 2L)),
                own = base.own.copy(linkId = pick(id(8), id(9)), gen = pick(0L, 1L)),
                invites = invites,
                inviter = if (rnd.nextBoolean()) Inviter("k", id(1)) else null,
                left = listOf(id(2), id(3)).filter { rnd.nextBoolean() }.associateWith { pick(10L, 20L) }.takeIf { it.isNotEmpty() },
            )
        }
        repeat(500) {
            val a = random()
            val b = random()
            assertEquals(mergeGroup(a, b, 1_000_000_000_000L), mergeGroup(b, a, 1_000_000_000_000L))
        }
    }

    // what JSON.parse refuses is unreadable on both clients
    @Test
    fun strictParse() {
        assertNotNull(strictJson("""{"t":"group","u":1.5e3,"a":[true,false,null,-0],"s":"x"}"""))
        for (bad in listOf("""{"u":01}""", """{"u":1.}""", """{"u":+1}""", """{"t":text}""", """{"u":NaN}""", """{"u":1,}""")) {
            assertNull(bad, strictJson(bad))
        }
    }

    private fun id(n: Int) = "e0000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}"
}
