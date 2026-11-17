package cool.hin.memox.data.model

import cool.hin.memox.test.createListItem
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mockStatic

class ModelExtensionsTest {

    @Test
    fun `Json toBaseNote()`() {
        val json =
            """
          {
              "type": "LIST",
              "color": "#E2F6D3",
              "title": "Test",
              "pinned": false,
              "timestamp": 1742822848689,
              "modifiedTimestamp": 1742823434623,
              "labels": [
                "TestLabel"
              ],
              "items": [
                {
                  "body": "A",
                  "checked": false,
                  "isChild": false,
                  "order": 0
                },
                {
                  "body": "B",
                  "checked": true,
                  "isChild": true,
                  "order": 1
                },
                {
                  "body": "C",
                  "checked": false,
                  "isChild": true,
                  "order": 2
                },
                {
                  "body": "D",
                  "checked": false,
                  "isChild": false,
                  "order": 3
                },
                {
                  "body": "E",
                  "checked": true,
                  "isChild": false,
                  "order": 4
                }
              ],
              "reminders": [
                {
                  "id": 0,
                  "dateTime": 1742822940000,
                  "repetition": "{\"value\":1,\"unit\":\"DAYS\"}",
                  "isNotificationVisible": false
                }
              ]
            }
        """
        val colorMock = mockStatic(android.graphics.Color::class.java)
        colorMock.`when`<Int> { android.graphics.Color.parseColor(anyString()) }.thenReturn(1)

        val baseNote = json.toBaseNote()

        assertEquals("Test", baseNote.title)
        assertEquals(false, baseNote.pinned)
        assertEquals("#E2F6D3", baseNote.color)
        assertEquals(Folder.NOTES, baseNote.folder)
        assertEquals(
            mutableListOf(
                ListItem("A", false, false, 0, mutableListOf()),
                ListItem("B", true, true, 1, mutableListOf()),
                ListItem("C", false, true, 2, mutableListOf()),
                ListItem("D", false, false, 3, mutableListOf()),
                ListItem("E", true, false, 4, mutableListOf()),
            ),
            baseNote.items,
        )
        assertEquals(1, baseNote.reminders.size)
        assertEquals(1742822848689, baseNote.timestamp)
        assertEquals(1742823434623, baseNote.modifiedTimestamp)
        assertEquals(listOf("TestLabel"), baseNote.labels)
        assertEquals(Repetition(1, RepetitionTimeUnit.DAYS), baseNote.reminders[0].repetition)
    }

    @Test
    fun `BaseNote toJson()`() {
        val baseNote =
            BaseNote(
                id = 1,
                Type.NOTE,
                Folder.DELETED,
                "#E2F6D3",
                "Title",
                true,
                12354632465L,
                945869546L,
                listOf("label"),
                "Body",
                listOf(SpanRepresentation(0, 10, bold = true)),
                mutableListOf(
                    createListItem("Item1", true, false),
                    createListItem("Item2", true, true),
                ),
                listOf(FileAttachment("localImage", "originalImage", "image/jpeg")),
                listOf(FileAttachment("localFile", "originalFile", "text/plain")),
                listOf(Audio("audio", 10L, 12312334L)),
                listOf(Reminder(1, Date(1743253506957), Repetition(10, RepetitionTimeUnit.WEEKS))),
                isPinnedToStatus = false,
            )

        val json = org.json.JSONObject(baseNote.toJson())

        assertEquals("Title", json.getString("title"))
        assertEquals("Body", json.getString("body"))
        assertEquals("NOTE", json.getString("type"))
        assertEquals("DELETED", json.getString("folder"))
        assertEquals("RICH_TEXT", json.getString("contentFormat"))
        assertEquals("NONE", json.getString("protection"))
        assertEquals(false, json.getBoolean("locked"))
        assertEquals(2, json.getJSONArray("items").length())
        assertEquals(1, json.getJSONArray("images").length())
        assertEquals(1, json.getJSONArray("files").length())
        assertEquals(1, json.getJSONArray("audios").length())
        assertEquals(1, json.getJSONArray("reminders").length())
        assertEquals("label", json.getJSONArray("labels").getString(0))
        assertEquals(12354632465L, json.getLong("timestamp"))
        assertEquals(945869546L, json.getLong("modifiedTimestamp"))
    }
}
