package cool.hin.memox.data.note

import android.content.Context
import cool.hin.memox.data.model.Audio
import cool.hin.memox.data.model.Converters
import cool.hin.memox.data.model.FileAttachment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local, process-surviving draft for the "ask on exit" save policy.
 *
 * Only ordinary / legacy-locked notes use this store: their bodies are already plaintext in the
 * database. Content-encrypted notes must never leave plaintext here, so drafts are disabled for
 * them (unsaved edits survive configuration changes, not process death).
 */
object NoteDraftStore {

    private const val PREFS = "note_drafts"
    private const val MAX_DRAFT_CHARS = 393_216

    data class Draft(
        val title: String,
        val body: String,
        val labels: List<String>,
        val images: List<FileAttachment>,
        val files: List<FileAttachment>,
        val audios: List<Audio>,
        val modifiedAt: Long,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        context: Context,
        key: String,
        title: String,
        body: String,
        labels: List<String>,
        images: List<FileAttachment>,
        files: List<FileAttachment>,
        audios: List<Audio>,
    ) {
        if (body.length > MAX_DRAFT_CHARS) return
        val json =
            JSONObject()
                .put("title", title)
                .put("body", body)
                .put("labels", JSONArray(labels))
                .put("images", JSONArray(Converters.filesToJson(images)))
                .put("files", JSONArray(Converters.filesToJson(files)))
                .put("audios", JSONArray(Converters.audiosToJson(audios)))
                .put("modifiedAt", System.currentTimeMillis())
        prefs(context).edit().putString(key, json.toString()).apply()
    }

    fun load(context: Context, key: String): Draft? {
        val raw = prefs(context).getString(key, null) ?: return null
        return runCatching {
                val json = JSONObject(raw)
                Draft(
                    title = json.optString("title", ""),
                    body = json.optString("body", ""),
                    labels =
                        json.optJSONArray("labels")?.let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        } ?: emptyList(),
                    images =
                        json.optJSONArray("images")?.let {
                            Converters.jsonToFiles(it.toString())
                        } ?: emptyList(),
                    files =
                        json.optJSONArray("files")?.let {
                            Converters.jsonToFiles(it.toString())
                        } ?: emptyList(),
                    audios =
                        json.optJSONArray("audios")?.let {
                            Converters.jsonToAudios(it.toString())
                        } ?: emptyList(),
                    modifiedAt = json.optLong("modifiedAt", 0),
                )
            }
            .getOrNull()
    }

    fun clear(context: Context, key: String) {
        prefs(context).edit().remove(key).apply()
    }
}
