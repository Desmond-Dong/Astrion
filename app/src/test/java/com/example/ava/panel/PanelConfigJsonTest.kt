package com.example.ava.panel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelConfigJsonTest {

    @Test
    fun `layout parses rooms cards and entities`() {
        val json = """
        {
          "rooms": [
            {
              "title": "客厅",
              "cards": [
                {
                  "type": "tv",
                  "uuid": "tv1",
                  "name": "小米电视",
                  "tv_type": "android_tv",
                  "entities": [
                    {"key": "POWER", "entity_id": "remote.mi_tv", "value": "POWER"},
                    {"key": "VOLUME_UP", "entity_id": "media_player.mi_tv"}
                  ]
                },
                {
                  "type": "light",
                  "name": "吸顶灯",
                  "entities": [{"entity_id": "light.ceiling", "alias": "主灯"}]
                }
              ]
            }
          ],
          "pages": ["全屋"],
          "sync_entities": ["light.ceiling", "climate.ac.temperature"]
        }
        """.trimIndent()

        val layout = PanelLayout.fromJson(json)

        assertNotNull(layout)
        assertEquals(1, layout.rooms.size)
        assertEquals("客厅", layout.rooms[0].title)
        assertEquals(2, layout.rooms[0].cards.size)

        val tv = layout.rooms[0].cards[0]
        assertEquals(PanelCardTypes.TV, tv.type)
        assertEquals("tv1", tv.cardId)
        assertEquals("android_tv", tv.tvType)
        assertEquals("remote", tv.entities[0].entityDomain)
        assertEquals("POWER", tv.entities[0].effectiveCommand)

        val mediaRef = tv.entities[1]
        assertEquals("media_player", mediaRef.entityDomain)
        // value falls back to the key name
        assertEquals("VOLUME_UP", mediaRef.effectiveCommand)

        val light = layout.rooms[0].cards[1]
        // cardId falls back to type + name when uuid missing
        assertEquals("light_吸顶灯", light.cardId)
        assertEquals("主灯", light.primaryEntity?.alias)

        assertEquals(listOf("全屋"), layout.pages)
        assertEquals(
            listOf("light.ceiling", "climate.ac.temperature"),
            layout.syncEntities
        )
    }

    @Test
    fun `layout ignores unknown keys and tolerates missing sections`() {
        val json = """{"future_field": 1, "rooms": [{"title": "卧室"}]}"""
        val layout = PanelLayout.fromJson(json)

        assertNotNull(layout)
        assertEquals("卧室", layout.rooms[0].title)
        assertTrue(layout.rooms[0].cards.isEmpty())
        assertTrue(layout.syncEntities.isEmpty())
    }

    @Test
    fun `invalid or blank layout json returns null`() {
        assertNull(PanelLayout.fromJson("not json {"))
        assertNull(PanelLayout.fromJson(""))
        assertNull(PanelLayout.fromJson("   "))
    }

    @Test
    fun `ir codebook parses device to button to code map`() {
        val json = """
        {
          "小米电视": {"POWER": "38000,9000,4500,560,560", "MUTE": "sGipAA=="},
          "机顶盒": {"POWER": "JgBMACHgERAQERAAHQAA", "VOL+": "bGlpAA=="}
        }
        """.trimIndent()

        val codebook = IrCodebook.fromJson(json)

        assertNotNull(codebook)
        assertEquals(2, codebook.devices.size)
        assertEquals(
            "38000,9000,4500,560,560",
            codebook.devices["小米电视"]?.get("POWER")
        )
        assertEquals("bGlpAA==", codebook.devices["机顶盒"]?.get("VOL+"))
    }

    @Test
    fun `ir codebook also accepts the wrapped devices form`() {
        val json = """{"devices": {"功放": {"MUTE": "sGipAA=="}}}"""

        val codebook = IrCodebook.fromJson(json)

        assertNotNull(codebook)
        assertEquals(1, codebook.devices.size)
        assertEquals("sGipAA==", codebook.devices["功放"]?.get("MUTE"))
    }

    @Test
    fun `invalid ir codebook json returns null`() {
        assertNull(IrCodebook.fromJson("[1,2,3]"))
        assertNull(IrCodebook.fromJson(""))
    }

    @Test
    fun `key bindings parse with long press flag`() {
        val json = """
        {
          "bindings": [
            {"keycode": 132, "action": "room", "target": "客厅"},
            {"keycode": 135, "long_press": true, "action": "card", "target": "tv1"},
            {"keycode": 136, "action": "service", "service": "scene.turn_on", "entity_id": "scene.film"}
          ]
        }
        """.trimIndent()

        val bindings = PanelKeyBindings.fromJson(json)

        assertNotNull(bindings)
        assertEquals(3, bindings.bindings.size)
        assertFalse(bindings.bindings[0].longPress)
        assertTrue(bindings.bindings[1].longPress)
        assertEquals("card", bindings.bindings[1].action)
        assertEquals("scene.turn_on", bindings.bindings[2].service)
    }

    @Test
    fun `invalid key bindings json returns null`() {
        assertNull(PanelKeyBindings.fromJson("nope"))
    }

    @Test
    fun `empty keycodes and blank names get safe object ids`() {
        assertEquals("ir_device", irObjectId("###"))
        assertEquals("mi_tv_2", irObjectId("Mi TV 2"))
    }
}
