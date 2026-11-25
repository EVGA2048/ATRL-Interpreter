package net.astrolab.interpreter
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Archive(context: Context) {
    private val prefs = context.getSharedPreferences("interpreter_archive", Context.MODE_PRIVATE)
    fun terms(): List<Term> = runCatching {
        val a = JSONArray(prefs.getString("terms","[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Term(it.getString("ru"),it.getString("zh"),it.optInt("count",1),it.optBoolean("approved"),it.optString("category","未分类"),it.optString("example"),it.optString("language","ru")) } }
    }.getOrDefault(emptyList())
    fun saveTerms(items: List<Term>) {
        prefs.edit().putString("terms",JSONArray(items.map { JSONObject().put("ru",it.ru).put("zh",it.zh).put("count",it.count).put("approved",it.approved).put("category",it.category).put("example",it.example).put("language",it.language) }).toString()).apply()
    }
    fun sessions(): List<Session> = runCatching {
        val a = JSONArray(prefs.getString("sessions","[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            val s = o.getJSONArray("items")
            Session(o.getString("title"), (0 until s.length()).map { j -> s.getJSONObject(j).let {
                Segment(it.getString("raw"),it.optString("corrected"),it.optString("translated"),it.optString("scene"),it.optLong("time"),it.optString("error"),it.optString("language"),it.optString("target"),it.optLong("startTime",it.optLong("time")),it.optInt("manualRevision"),it.optString("sourceBeforeEdit").takeIf{_->it.has("sourceBeforeEdit")&&!it.isNull("sourceBeforeEdit")},it.optString("translationBeforeEdit").takeIf{_->it.has("translationBeforeEdit")&&!it.isNull("translationBeforeEdit")},it.optString("soundEvent"))
            } },o.optString("summary"),o.optLong("time"))
        }}
    }.getOrDefault(emptyList())
    fun saveSessions(items: List<Session>) {
        prefs.edit().putString("sessions",JSONArray(items.map { s -> JSONObject().put("title",s.title).put("summary",s.summary).put("time",s.time)
            .put("items",JSONArray(s.items.map { JSONObject().put("raw",it.raw).put("corrected",it.corrected).put("translated",it.translated)
                .put("scene",it.scene).put("time",it.time).put("error",it.error).put("language",it.language).put("target",it.target).put("startTime",it.startTime).put("manualRevision",it.manualRevision).put("sourceBeforeEdit",it.sourceBeforeEdit ?: JSONObject.NULL).put("translationBeforeEdit",it.translationBeforeEdit ?: JSONObject.NULL).put("soundEvent",it.soundEvent) })) }).toString()).apply()
    }
    fun cloudEnabled() = prefs.getBoolean("cloud", true)
    fun setCloud(value: Boolean) { prefs.edit().putBoolean("cloud",value).apply() }
}
