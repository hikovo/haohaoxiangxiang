package com.hikovo.mobilepet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class AndroidPersonaStateStore(context: Context) : PersonaStateStore {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  override fun load(): PersonaState {
    val raw = prefs.getString(KEY_STATE, null) ?: return PersonaState()
    return runCatching {
      val root = JSONObject(raw)
      val state = PersonaState(
        lastTopic = root.optString("lastTopic"),
        questionSuppression = root.optInt("questionSuppression"),
      )
      val messages = root.optJSONArray("messages") ?: JSONArray()
      for (index in 0 until messages.length()) {
        val item = messages.getJSONObject(index)
        state.messages += PersonaMessage(
          item.getString("id"),
          item.getString("role"),
          item.getString("text"),
          item.optString("entryPoint", "migrated"),
          item.optLong("createdAt"),
        )
      }
      val memories = root.optJSONArray("memories") ?: JSONArray()
      for (index in 0 until memories.length()) {
        val item = memories.getJSONObject(index)
        state.memories += PersonaMemory(
          item.getString("id"),
          item.getString("layer"),
          item.getString("key"),
          item.getString("value"),
          item.optJSONArray("sourceMessageIds").toStringSet(),
          item.optLong("createdAt"),
          item.optDouble("confidence", 0.8),
          item.optJSONArray("supersedes").toStringSet(),
        )
      }
      val acts = root.optJSONArray("actHistory") ?: JSONArray()
      for (index in 0 until acts.length()) runCatching { state.actHistory += ReactionAct.valueOf(acts.getString(index)) }
      val voices = root.optJSONArray("voiceHistory") ?: JSONArray()
      for (index in 0 until voices.length()) state.voiceHistory += voices.getString(index)
      state
    }.getOrElse { PersonaState() }
  }

  override fun save(state: PersonaState) {
    val root = JSONObject()
      .put("lastTopic", state.lastTopic)
      .put("questionSuppression", state.questionSuppression)
      .put("messages", JSONArray().apply {
        state.messages.forEach { item -> put(JSONObject()
          .put("id", item.id).put("role", item.role).put("text", item.text)
          .put("entryPoint", item.entryPoint).put("createdAt", item.createdAt)) }
      })
      .put("memories", JSONArray().apply {
        state.memories.forEach { item -> put(JSONObject()
          .put("id", item.id).put("layer", item.layer).put("key", item.key).put("value", item.value)
          .put("sourceMessageIds", JSONArray(item.sourceMessageIds.toList()))
          .put("createdAt", item.createdAt).put("confidence", item.confidence)
          .put("supersedes", JSONArray(item.supersedes.toList()))) }
      })
      .put("actHistory", JSONArray(state.actHistory.map { it.name }))
      .put("voiceHistory", JSONArray(state.voiceHistory))
    prefs.edit().putString(KEY_STATE, root.toString()).apply()
  }

  companion object {
    private const val PREFS_NAME = "sanhao_unified_persona_v1"
    private const val KEY_STATE = "conversation_state"
  }
}

private fun JSONArray?.toStringSet(): MutableSet<String> {
  if (this == null) return mutableSetOf()
  return (0 until length()).map { getString(it) }.toMutableSet()
}

/**
 * The only production owner of persona, dialogue policy and conversation-derived memory.
 * Main chat and memo chat reach it through the Tauri plugin; the overlay calls it directly.
 */
class UnifiedPersonaEngine private constructor(context: Context) {
  private val core = UnifiedPersonaCore(AndroidPersonaStateStore(context), loadPersonaBundle(context), writer = TemplateWriter())

  fun reply(messageId: String, input: String, entryPoint: String): EngineReply = core.reply(messageId, input, entryPoint)
  fun retract(messageIds: Set<String>): MemoryManager.Change = core.retract(messageIds)
  fun clearConversation() = core.clearConversation()
  fun importLegacy(messages: List<PersonaMessage>, legacy: Map<String, List<String>>) = core.importLegacy(messages, legacy)
  fun setManualMemory(manual: Map<String, List<String>>) = core.setManualMemory(manual)
  fun snapshot(): PersonaState = core.state()

  companion object {
    @Volatile private var instance: UnifiedPersonaEngine? = null
    private val worker = Executors.newSingleThreadExecutor()
    fun execute(task: () -> Unit) { worker.execute(task) }

    fun get(context: Context): UnifiedPersonaEngine = instance ?: synchronized(this) {
      instance ?: UnifiedPersonaEngine(context.applicationContext).also { instance = it }
    }

    private fun loadPersonaBundle(context: Context): PersonaBundle {
      val defaults = PersonaBundle.defaults()
      return runCatching {
        val root = JSONObject(context.assets.open("persona/v1/persona_bundle.json").bufferedReader().use { it.readText() })
        val cases = root.getJSONArray("reactionCases")
        val parsed = (0 until cases.length()).map { index ->
          val item = cases.getJSONObject(index)
          ReactionCase(
            item.getString("id"),
            item.getJSONArray("triggers").strings(),
            item.getJSONArray("preferredActs").strings().map(ReactionAct::valueOf),
            item.getString("notice"),
            item.getString("avoid"),
          )
        }
        PersonaBundle(
          root.optString("core", defaults.core),
          parsed.ifEmpty { defaults.reactionCases },
          root.optJSONArray("voicePatterns")?.strings() ?: defaults.voicePatterns,
          root.optJSONArray("corrections")?.strings() ?: defaults.corrections,
        )
      }.getOrElse { defaults }
    }
  }
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

fun EngineReply.toJson(): JSONObject = JSONObject()
  .put("reply", reply)
  .put("assistantMessageId", assistantMessageId)
  .put("act", act.name)
  .put("trace", trace.toJson())

fun EngineTrace.toJson(): JSONObject = JSONObject()
  .put("runtime", runtime)
  .put("entryPoint", entryPoint)
  .put("analysis", JSONObject()
    .put("normalized", analysis.normalized)
    .put("topic", analysis.topic)
    .put("isQuestion", analysis.isQuestion)
    .put("consecutiveRepeat", analysis.consecutiveRepeat)
    .put("totalRepeat", analysis.totalRepeat)
    .put("matchedCaseIds", JSONArray(analysis.matchedCaseIds)))
  .put("plan", JSONObject()
    .put("act", plan.act.name)
    .put("reason", plan.reason)
    .put("caseIds", JSONArray(plan.caseIds)))
  .put("candidates", JSONArray().apply {
    candidates.forEach { candidate -> put(JSONObject()
      .put("text", candidate.text)
      .put("score", candidate.score)
      .put("rejected", candidate.rejected)
      .put("reasons", JSONArray(candidate.reasons))) }
  })
  .put("selectedIndex", selectedIndex)
  .put("stateDelta", JSONArray(stateDelta))

fun PersonaState.memoryJson(): JSONObject = JSONObject()
  .put("workingMemoryCount", messages.takeLast(16).size)
  .put("currentTopic", lastTopic)
  .put("episodic", JSONArray(memories.filter { it.layer == "episodic" }.map { it.toJson() }))
  .put("longTerm", JSONArray(memories.filter { it.layer == "long_term" }.map { it.toJson() }))
  .put("relationship", JSONArray(memories.filter { it.layer == "relationship" }.map { it.toJson() }))
  .put("corrections", JSONArray(memories.filter { it.layer == "correction" }.map { it.toJson() }))

private fun PersonaMemory.toJson() = JSONObject()
  .put("id", id).put("layer", layer).put("key", key).put("value", value)
  .put("sourceMessageIds", JSONArray(sourceMessageIds.toList()))
  .put("createdAt", createdAt).put("confidence", confidence)
  .put("supersedes", JSONArray(supersedes.toList()))
