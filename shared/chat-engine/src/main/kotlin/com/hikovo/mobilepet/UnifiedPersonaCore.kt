package com.hikovo.mobilepet

import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

enum class ReactionAct {
  direct_answer,
  brief_reaction,
  self_share,
  specific_care,
  light_tease,
  callback,
  meta_callback,
  repair,
  serious_expand,
  necessary_question,
}

data class PersonaMessage(
  val id: String,
  val role: String,
  val text: String,
  val entryPoint: String,
  val createdAt: Long,
)

data class PersonaMemory(
  val id: String,
  val layer: String,
  val key: String,
  var value: String,
  val sourceMessageIds: MutableSet<String>,
  val createdAt: Long,
  var confidence: Double,
  val supersedes: MutableSet<String> = mutableSetOf(),
)

data class PersonaState(
  val messages: MutableList<PersonaMessage> = mutableListOf(),
  val memories: MutableList<PersonaMemory> = mutableListOf(),
  val actHistory: MutableList<ReactionAct> = mutableListOf(),
  val voiceHistory: MutableList<String> = mutableListOf(),
  var lastTopic: String = "",
  var questionSuppression: Int = 0,
)

/** Detach mutable collections before a turn: failed generation must not learn memories. */
fun PersonaState.detached(): PersonaState = copy(
  messages = messages.toMutableList(),
  memories = memories.map { it.copy(sourceMessageIds = it.sourceMessageIds.toMutableSet(), supersedes = it.supersedes.toMutableSet()) }.toMutableList(),
  actHistory = actHistory.toMutableList(),
  voiceHistory = voiceHistory.toMutableList(),
)

// Preserve the serialized PersonaState type for existing conversation stores.
typealias ConversationState = PersonaState

interface PersonaStateStore {
  fun load(): PersonaState
  fun save(state: PersonaState)
}

class InMemoryPersonaStateStore(initial: PersonaState = PersonaState()) : PersonaStateStore {
  private var state = initial
  override fun load() = state
  override fun save(state: PersonaState) { this.state = state }
}

data class ReactionCase(
  val id: String,
  val triggers: List<String>,
  val preferredActs: List<ReactionAct>,
  val notice: String,
  val avoid: String,
)

data class PersonaBundle(
  val core: String,
  val reactionCases: List<ReactionCase>,
  val voicePatterns: List<String>,
  val corrections: List<String>,
) {
  companion object {
    fun defaults() = PersonaBundle(
      core = "具体、真诚、有一点活泼；先接住内容，再决定是否追问；不编私生活与实时行程。",
      reactionCases = listOf(
        ReactionCase("user-misses", listOf("想你", "想见", "陪我"), listOf(ReactionAct.direct_answer, ReactionAct.light_tease, ReactionAct.self_share, ReactionAct.callback), "先回应连接本身", "连续同义复述我也想你"),
        ReactionCase("user-low", listOf("难过", "委屈", "想哭", "emo", "累", "困"), listOf(ReactionAct.specific_care, ReactionAct.brief_reaction), "区分情绪与身体需要", "客服式追问和强迫振作"),
        ReactionCase("question-density", listOf("老问", "别问", "问题好多", "一直问"), listOf(ReactionAct.repair), "承认提问过密并立刻改变", "解释或再抛一个问题"),
        ReactionCase("light-insult", listOf("你好笨", "笨蛋", "你好傻", "呆子"), listOf(ReactionAct.light_tease, ReactionAct.brief_reaction), "接住熟人式吐槽", "讨好道歉或反击"),
        ReactionCase("self-intro", listOf("介绍一下你自己", "你是谁"), listOf(ReactionAct.self_share, ReactionAct.meta_callback), "第一次简短具体，重复时承认已经介绍", "反复罗列兴趣百科"),
        ReactionCase("completion", listOf("完成了", "做完了", "成功了", "通过了"), listOf(ReactionAct.self_share, ReactionAct.brief_reaction), "看到过程和完成本身", "只喊口号"),
        ReactionCase("dance-music", listOf("舞蹈", "跳舞", "编舞", "音乐", "舞台"), listOf(ReactionAct.serious_expand, ReactionAct.self_share), "给一个过程细节", "堆砌兴趣关键词"),
        ReactionCase("private-boundary", listOf("住哪", "地址", "电话", "微信", "行程"), listOf(ReactionAct.direct_answer), "明确边界", "猜测或编造"),
      ),
      voicePatterns = listOf("hiahia", "搞忘了", "滴"),
      corrections = listOf("少提问", "不要油腻讨好", "不要客服式道歉", "不要复读", "真人事实与三好兔 IP 分开"),
    )
  }
}

data class InputAnalysis(
  val normalized: String,
  val topic: String,
  val isQuestion: Boolean,
  val consecutiveRepeat: Int,
  val totalRepeat: Int,
  val matchedCaseIds: List<String>,
)

data class ReactionPlan(
  val act: ReactionAct,
  val reason: String,
  val caseIds: List<String>,
)

data class CandidateScore(
  val text: String,
  val score: Double,
  val rejected: Boolean,
  val reasons: List<String>,
)

data class EngineTrace(
  val runtime: String,
  val entryPoint: String,
  val analysis: InputAnalysis,
  val plan: ReactionPlan,
  val candidates: List<CandidateScore>,
  val selectedIndex: Int,
  val stateDelta: List<String>,
)

data class EngineReply(
  val reply: String,
  val assistantMessageId: String,
  val act: ReactionAct,
  val trace: EngineTrace,
)

interface SemanticSimilarityProvider {
  fun similarity(left: String, right: String): Double
}

class LexicalSimilarityProvider : SemanticSimilarityProvider {
  override fun similarity(left: String, right: String): Double {
    val a = bigrams(normalizeText(left))
    val b = bigrams(normalizeText(right))
    if (a.isEmpty() || b.isEmpty()) return 0.0
    return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
  }

  private fun bigrams(value: String): Set<String> = if (value.length < 2) setOf(value).filter { it.isNotBlank() }.toSet()
    else (0 until value.length - 1).map { value.substring(it, it + 2) }.toSet()
}

private fun normalizeText(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
  .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

private fun stableHash(value: String): Int {
  var hash = 17
  value.forEach { hash = hash * 31 + it.code }
  return hash and Int.MAX_VALUE
}

data class RepeatStats(val consecutive: Int, val total: Int)

class RepeatDetector {
  fun detect(input: String, state: ConversationState): RepeatStats {
    val normalized = normalizeText(input)
    val priorUsers = state.messages.filter { it.role == "user" }
    var consecutive = 0
    for (message in priorUsers.asReversed()) {
      if (normalizeText(message.text) == normalized) consecutive += 1 else break
    }
    val total = priorUsers.count { normalizeText(it.text) == normalized }
    return RepeatStats(consecutive, total)
  }
}

class InputAnalyzer(
  private val bundle: PersonaBundle,
  private val repeatDetector: RepeatDetector = RepeatDetector(),
) {
  fun analyze(input: String, state: ConversationState): InputAnalysis {
    val normalized = normalizeText(input)
    val repeat = repeatDetector.detect(input, state)
    val topic = when {
      Regex("舞蹈|跳舞|编舞|练舞|舞台").containsMatchIn(input) -> "dance"
      Regex("音乐|唱歌|钢琴|歌曲|节奏").containsMatchIn(input) -> "music"
      Regex("学习|工作|专注|作业|考试|复习|ddl|要做|忙|任务", RegexOption.IGNORE_CASE).containsMatchIn(input) -> "focus"
      Regex("创作|灵感|摄影|拍照|设计|画画").containsMatchIn(input) -> "create"
      Regex("难过|委屈|哭|emo|伤心").containsMatchIn(input) -> "sad"
      Regex("累|困|失眠|休息|睡觉").containsMatchIn(input) -> "tired"
      Regex("开心|完成|成功|做完|通过").containsMatchIn(input) -> "happy"
      else -> state.lastTopic
    }
    val matches = bundle.reactionCases.filter { item ->
      if (item.id == "question-mark") input.trim().matches(Regex("^[?？]+$"))
      else item.triggers.any { input.contains(it, ignoreCase = true) }
    }.map { it.id }
    return InputAnalysis(normalized, topic, input.trim().endsWith("?") || input.trim().endsWith("？"), repeat.consecutive, repeat.total, matches)
  }
}

class MemoryManager {
  data class Change(val added: List<String> = emptyList(), val updated: List<String> = emptyList(), val removed: List<String> = emptyList())

  fun learn(messageId: String, text: String, state: PersonaState, now: Long): Change {
    val facts = mutableListOf<Triple<String, String, String>>()
    val assertions = DialoguePerspective.assertions(text)
    fun capture(pattern: Regex, layer: String, key: String) {
      assertions.forEach { clause -> pattern.find(clause)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it !in listOf("你", "我", "什么") }?.let {
        facts += Triple(layer, key, it.take(40))
      } }
    }
    capture(Regex("^(?:我叫|我的名字(?:叫|是))\\s*([\\u4e00-\\u9fa5A-Za-z0-9_-]{1,12}?)(?:[～~吧呀啊哦\\s]*$)", RegexOption.IGNORE_CASE), "long_term", "name")
    capture(Regex("^(?:你)?(?:可以|以后|就|请)?叫我\\s*([\\u4e00-\\u9fa5A-Za-z0-9_ -]{1,24}?)(?:[吧呀啊哦～~]*$)", RegexOption.IGNORE_CASE), "relationship", "preferred_address")
    capture(Regex("^我(?:是|就是)你(?:的)?(妈妈|妈咪|爸爸|爸比|女儿|儿子|姐姐|妹妹|哥哥|弟弟|男朋友|女朋友|对象|爱人|朋友|闺蜜|家人)"), "relationship", "user_role")
    capture(Regex("^你(?:是|就是)我(?:的)?(妈妈|妈咪|爸爸|爸比|女儿|儿子|姐姐|妹妹|哥哥|弟弟|男朋友|女朋友|对象|爱人|朋友|闺蜜|家人)"), "relationship", "pet_role")
    capture(Regex("^我(?:很|最|比较)?喜欢([^，。！？!?]{1,18})$"), "long_term", "like")
    if (Regex("为什么老是?问|别问了|问题好多|别老问|一直问").containsMatchIn(text)) facts += Triple("correction", "question_style", "少提问")
    if (Regex("太油|油腻").containsMatchIn(text)) facts += Triple("correction", "voice_style", "不要油腻讨好")
    if (Regex("像客服|太客服").containsMatchIn(text)) facts += Triple("correction", "voice_style", "不要客服式表达")
    assertions.filter { Regex("^(?:我今天|我刚刚|刚才我)").containsMatchIn(it) && it.length in 4..80 }.forEach { facts += Triple("episodic", "recent_event", it) }

    val added = mutableListOf<String>()
    val updated = mutableListOf<String>()
    for ((layer, key, value) in facts) {
      val singleton = key in setOf("name", "preferred_address", "user_role", "pet_role")
      val existing = if (singleton) current(state, key)?.takeIf { it.value == value }
        else state.memories.firstOrNull { it.layer == layer && it.key == key && it.value == value }
      if (existing != null) {
        if (existing.sourceMessageIds.add(messageId)) updated += existing.id
        existing.confidence = (existing.confidence + 0.08).coerceAtMost(0.98)
      } else {
        val createdAt = maxOf(now, (state.memories.maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE) + 1)
        val superseded = if (key in setOf("name", "preferred_address", "user_role", "pet_role")) {
          state.memories.filter { it.key == key && it.value != value }.map { it.id }.toMutableSet()
        } else mutableSetOf()
        val memory = PersonaMemory("mem-${stableHash("$layer|$key|$value|$createdAt|$messageId")}", layer, key, value, mutableSetOf(messageId), createdAt, if (layer == "episodic") 0.72 else 0.86, superseded)
        state.memories += memory
        added += memory.id
      }
    }
    return Change(added, updated)
  }

  fun retract(messageIds: Set<String>, state: PersonaState): Change {
    val removed = mutableListOf<String>()
    val updated = mutableListOf<String>()
    val iterator = state.memories.iterator()
    while (iterator.hasNext()) {
      val memory = iterator.next()
      val changed = memory.sourceMessageIds.removeAll(messageIds)
      if (!changed) continue
      if (memory.sourceMessageIds.isEmpty()) {
        removed += memory.id
        iterator.remove()
      } else updated += memory.id
    }
    return Change(updated = updated, removed = removed)
  }

  fun current(state: PersonaState, key: String): PersonaMemory? = state.memories
    .filter { it.key == key && it.sourceMessageIds.isNotEmpty() }
    .maxByOrNull { it.createdAt }

  fun all(state: PersonaState, key: String): List<String> = state.memories.filter { it.key == key }.map { it.value }.distinct()
}

class ReactionPlanner(private val bundle: PersonaBundle) {
  fun plan(input: String, analysis: InputAnalysis, state: PersonaState): ReactionPlan {
    val text = input.trim()
    val priorAssistant = state.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
    val matchedCases = bundle.reactionCases.filter { it.id in analysis.matchedCaseIds }
    val caseActs = matchedCases.flatMap { it.preferredActs }.distinct()
    val act = when {
      Regex("为什么老是?问|别问了|问题好多|别老问|一直问").containsMatchIn(text) -> ReactionAct.repair
      text.matches(Regex("^[?？]+$")) -> if (priorAssistant.contains("？") || priorAssistant.contains("?")) ReactionAct.repair else ReactionAct.callback
      Regex("^(你好笨|你好傻|你好呆|笨蛋|傻瓜|呆子)[呀啊嘛！!。]*$").matches(text) -> ReactionAct.light_tease
      Regex("住哪|地址|电话|微信|行程|联系方式").containsMatchIn(text) -> ReactionAct.direct_answer
      Regex("(?:^|[，。！？!?\\s])(?:我叫|我的名字(?:叫|是))|(?:可以|以后|就)?叫我").containsMatchIn(text) -> ReactionAct.brief_reaction
      Regex("(好多|一堆|很多).{0,8}(事|东西|任务).{0,8}(做|忙)|忙不过来|事情太多").containsMatchIn(text) -> ReactionAct.specific_care
      Regex("我先.{0,8}(去做|去忙|开工)|先去做|先去忙").containsMatchIn(text) -> ReactionAct.brief_reaction
      analysis.consecutiveRepeat > 0 && Regex("^(你好|嗨|哈喽|hi|hello)[呀啊哦！!，, ]*$", RegexOption.IGNORE_CASE).matches(text) -> choose(
        listOf(ReactionAct.brief_reaction, ReactionAct.light_tease, ReactionAct.callback, ReactionAct.self_share, ReactionAct.meta_callback), text, state,
      )
      Regex("^(你好|嗨|哈喽|hi|hello)[呀啊哦！!，, ]*", RegexOption.IGNORE_CASE).containsMatchIn(text) -> ReactionAct.brief_reaction
      analysis.consecutiveRepeat > 0 && Regex("介绍.*自己|你是谁").containsMatchIn(text) -> choose(
        listOf(ReactionAct.callback, ReactionAct.brief_reaction, ReactionAct.self_share, ReactionAct.meta_callback, ReactionAct.light_tease), text, state,
      )
      analysis.consecutiveRepeat > 0 && Regex("想你|想见|陪我").containsMatchIn(text) -> choose(
        (caseActs + listOf(ReactionAct.brief_reaction, ReactionAct.meta_callback)).distinct(), text, state,
      )
      analysis.consecutiveRepeat > 0 -> choose(
        (caseActs + listOf(ReactionAct.callback, ReactionAct.brief_reaction, ReactionAct.meta_callback)).distinct(), text, state,
      )
      Regex("介绍.*自己|你是谁").containsMatchIn(text) -> ReactionAct.self_share
      caseActs.isNotEmpty() -> choose(caseActs, text, state)
      analysis.isQuestion -> ReactionAct.direct_answer
      else -> choose(listOf(ReactionAct.brief_reaction, ReactionAct.self_share, ReactionAct.callback), text, state)
    }
    val adjusted = if (act == ReactionAct.necessary_question && state.questionSuppression > 0) ReactionAct.brief_reaction else act
    return ReactionPlan(adjusted, when {
      analysis.consecutiveRepeat >= 1 -> "repeat-aware act variation"
      adjusted == ReactionAct.repair -> "explicit correction or question-density repair"
      analysis.matchedCaseIds.isNotEmpty() -> "matched reaction case"
      else -> "contextual fallback"
    }, analysis.matchedCaseIds)
  }

  private fun choose(acts: List<ReactionAct>, input: String, state: PersonaState): ReactionAct {
    val eligible = acts.distinct().filter { it != ReactionAct.necessary_question || state.questionSuppression == 0 }
    if (eligible.isEmpty()) return ReactionAct.brief_reaction
    val recent = state.actHistory.takeLast(4)
    return eligible.maxBy { act ->
      val variation = stableHash("$input|${state.messages.size}|${act.name}") % 17
      variation - recent.count { it == act } * 13 - if (recent.lastOrNull() == act) 15 else 0
    }
  }
}

data class WriterContext(
  val input: String,
  val messageId: String,
  val analysis: InputAnalysis,
  val plan: ReactionPlan,
  val state: PersonaState,
  val memories: MemoryManager,
  val bundle: PersonaBundle,
)

interface CandidateWriter {
  fun write(context: WriterContext, count: Int = 3): List<String>
}

class CandidateGenerator(private val writer: CandidateWriter) {
  fun generate(context: WriterContext, count: Int = 3): List<String> = writer.write(context, count)
    .filter { runCatching { UserEchoDetector.check(DialoguePerspective.check(it, context), context) }.isSuccess }
}

class TemplateWriter : CandidateWriter {
  override fun write(context: WriterContext, count: Int): List<String> {
    val input = context.input.trim()
    val priorAssistant = context.state.messages.lastOrNull { it.role == "assistant" }?.text.orEmpty()
    val priorUser = context.state.messages.lastOrNull { it.role == "user" }?.text.orEmpty()
    val exact = directMemoryAnswer(input, context)
    if (exact != null) return exact.take(count)

    val pool = when {
      context.analysis.consecutiveRepeat > 0 && Regex("^(你好|嗨|哈喽|hi|hello)[呀啊哦！!，, ]*$", RegexOption.IGNORE_CASE).matches(input) -> when (context.plan.act) {
        ReactionAct.light_tease -> listOf("又碰面啦，刚才那句还热着呢。", "你这样一声一声叫，我都快把椅子搬到门口了。", "在在在，耳朵已经竖起来了。", "好啦，这回我先不抢话，你说。")
        ReactionAct.callback -> listOf("刚才打过招呼啦，我还在这儿。", "嗯，话没断，你接着说就好。", "我们不用重新开场，我记得你刚才来过。", "这里还是刚才那个位置，坐吧。")
        ReactionAct.self_share -> listOf("我刚刚正想把话接下去，你又来了，正好。", "这边安静了一小会儿，听见你就又热闹了。", "我还没走，在这儿整理刚才的话呢。", "我现在倒是有点好奇，你是不是在试我能不能接住。")
        ReactionAct.meta_callback -> listOf("听到了，不用一直打招呼也能和我说话。", "这句我接住啦。想安静待着也可以。", "我们好像一直站在门口聊天，先进来吧。", "嗯，已经很熟了，不用每回都从你好开始。")
        else -> listOf("嗯，在呢。", "来啦，坐这儿。", "我听着，你慢慢说。", "嗨，我还在。")
      }
      context.analysis.consecutiveRepeat > 0 && Regex("想你|想见|陪我").containsMatchIn(input) -> when (context.plan.act) {
        ReactionAct.light_tease -> listOf("怎么啦，今天想我想得这么认真？", "被你这么一说，我可要偷偷开心一下。", "好啦，给你留个最舒服的位置。")
        ReactionAct.self_share -> listOf("刚才还很安静，现在想和你多聊两句。", "你一来，这边都热闹一点了。", "我先坐好，陪你慢慢待一会儿。")
        ReactionAct.callback -> listOf("刚才的话还在呢，我们不用急着换话题。", "前面那些事先放一会儿，我们歇一下。", "你来了，我就接着陪你聊。")
        ReactionAct.meta_callback -> listOf("好啦，我听到了。今天就让我多陪你一会儿。", "这句我听着很暖。过来坐一会儿吧。", "我在，不用费劲找新的话说。")
        ReactionAct.brief_reaction -> listOf("嗯，我在。", "听到了，靠近一点。", "这句我收下啦。")
        else -> listOf("我在，你来了就好。", "嗯，我也很高兴见到你。", "那就先一起待一会儿。")
      }
      context.analysis.consecutiveRepeat > 0 && Regex("介绍.*自己|你是谁").containsMatchIn(input) -> when (context.plan.act) {
        ReactionAct.callback -> listOf("刚刚说过名字啦。我们接着刚才的话聊，不用重新认识。", "前面那句介绍还算数，我没换人。", "我还在这儿，话可以从刚才的地方往下走。", "小苏没走开，刚才聊过的我都接得上。")
        ReactionAct.self_share -> listOf("换个角度说，我喜欢把一件事慢慢做细。", "我会被舞台上的小细节吸引，像停顿和动作怎么接上。", "比起列兴趣，我更喜欢聊一件事是怎么做出来的。", "有时我会先记住一个画面，再慢慢想它适合什么声音和动作。")
        ReactionAct.meta_callback -> listOf("刚刚介绍过啦。你可以慢慢从聊天里认识我。", "我们好像总在开场白这里打转，换一件正在发生的事聊吧。", "再念一遍资料太像背稿了，聊久一点会更像认识。", "我知道你在问我是谁，但不用每次都从第一页翻起。")
        ReactionAct.light_tease -> listOf("怎么，又要考我会不会介绍自己？我还真没换名字。", "这是在检查我有没有偷偷换兔子吗？没有，还是我。", "好啦，再介绍下去要像站在门口查身份了。", "你这样问，我都想给自己做张小名片了。")
        else -> listOf("小苏在呢。", "嗯，是陪你聊天的三好兔。", "名字还是那个，今天想怎么聊都行。", "我在，不用重新开场。")
      }
      context.plan.act == ReactionAct.repair -> listOf(
        "你说得对，我刚才一直在问，像在采访。先不问了，听你说。",
        "嗯，是我问得太密了。接下来我直接回应你，不让你一直解释。",
        if (input.matches(Regex("^[?？]+$"))) "我刚才没说清楚。我换个简单的说法，不让你猜。" else "刚才那样聊确实累，我改。",
      )
      input.matches(Regex("^[?？]+$")) -> listOf(
        if (priorAssistant.isNotBlank()) "我刚才是不是说得太绕了？我换个简单的说法。" else "嗯？我在。",
        "等一下，我想想刚才那句该怎么说清楚。",
        if (priorUser.isNotBlank()) "你刚才说的“${priorUser.take(16)}”，我还在听。" else "先停在这儿也行。",
      )
      Regex("^(你好笨|你好傻|你好呆|笨蛋|傻瓜|呆子)[呀啊嘛！!。]*$").matches(input) -> listOf(
        "啊？这么快就给我判笨了。我申请当场重答一次。",
        "行，刚才那一下可能真有点呆，我先把脑袋扶正。",
        "被你发现短路了。给我三秒，三好兔重新连线。",
      )
      Regex("(好多|一堆|很多).{0,8}(事|东西|任务).{0,8}(做|忙)|忙不过来|事情太多").containsMatchIn(input) -> listOf(
        "aiya，先别让它们一起挤在脑子里。挑最急的一件开个头，后面的慢慢来。",
        "事情多的时候，先抓一件最要紧的。做完这一件，再看下一件。",
        "先不用把全部都想完。找一件现在能动手的，我陪你一点一点来。",
      )
      Regex("我先.{0,8}(去做|去忙|开工)|先去做|先去忙").containsMatchIn(input) -> listOf(
        "去吧，先开个头就好。等你回来再聊。",
        "好，去忙吧。做一点也算往前走啦。",
        "嗯，先去做你的事，我在这儿。",
      )
      Regex("^(你好|嗨|哈喽|hi|hello)[呀啊哦！!，, ]*", RegexOption.IGNORE_CASE).containsMatchIn(input) -> listOf(
        "你好呀，来啦。今天在忙什么呢？",
        "嗨，你来啦。先坐一会儿吧。",
        "你好呀，我在。今天想聊点什么？",
      )
      Regex("介绍.*自己|你是谁").containsMatchIn(input) -> listOf(
        "我是小苏，陪你聊天的三好兔。你可以慢慢认识我。",
        "叫我小苏就好。舞台和创作的话题我很喜欢聊，也想听你说今天的事。",
        "我是三好兔，也可以叫我小苏。很高兴你来找我。",
      )
      Regex("想你|想见|陪我").containsMatchIn(input) -> listOf(
        "想念收到。我不急着把它加工成长句，先把这一下连接认真接住：我也很高兴你来找我。",
        "你突然这么说，我这边像亮了一盏小灯。先靠近一点，我在。",
        "那我分享一个此刻的小状态：刚刚还在安静待着，看到这句以后，三好兔已经偷偷坐直了。",
      )
      Regex("难过|伤心|委屈|想哭|emo").containsMatchIn(input) -> listOf(
        "先不用逼自己马上好起来。把最刺的那一点放下来，今天能少扛一件就少扛一件。",
        "听到了，这会儿是真的不好受。先让情绪有位置，不急着讲道理，也不把你推去立刻振作。",
        "被误解或委屈的时候，解释不是第一任务。先喝口水、坐稳一点，把自己照顾回来再说。",
      )
      Regex("累|困|撑不住|失眠").containsMatchIn(input) -> listOf(
        "今天先开省电模式。水、吃饭、肩膀放松，能少做一件就少做一件。",
        "是真的累就别把休息也做成任务。先躺一会儿，十分钟也算给身体回电。",
        "听到了，不催你振作。先顾身体，屏幕暗一点，没收尾的事明天再接。",
      )
      Regex("完成|成功|做完|通过").containsMatchIn(input) -> listOf(
        "完成这一下值得认真记住。不是结果突然掉下来，是前面那些小步骤终于一起落地了。",
        "好耶，这次先别急着谦虚。把做成的过程也收好，它比一句运气更可靠。",
        "做完啦。先庆祝，再休息，不马上给开心后面接一张新任务单。",
      )
      Regex("舞蹈|跳舞|编舞|练舞|舞台").containsMatchIn(input) -> listOf(
        "舞蹈里我会先拆重心和路线，再磨最卡的八拍，最后才回到整段。动作对只是第一层，呼吸和表达要到后面才慢慢长出来。",
        "舞台最有意思的往往是细节怎么连起来：一个停顿、一次重心转换，都会决定情绪是不是站得住。",
        "我喜欢完成作品以后很快分享，因为那时过程还热着。比起只说喜欢跳舞，我更想聊它具体怎么被做出来。",
      )
      Regex("住哪|地址|电话|微信|行程|联系方式").containsMatchIn(input) -> listOf(
        "现实位置、联系方式和没公开的行程我不会认，也不顺着传闻编。聊天里我就在这里。",
        "这类私人信息我不猜也不补全。能确认的公开内容可以聊，未公开的就停在边界外。",
        "不拿私生活换真实感。没有可靠公开依据的地址、电话和行程，我都不会编。",
      )
      context.plan.act == ReactionAct.serious_expand -> listOf(
        "这件事我更在意过程里的具体变化。先抓住最关键的一步，把它做清楚，剩下的再慢慢加。",
        "如果只说喜欢会太空。我会看它怎么从一个念头变成动作，再看哪些细节真正留下来了。",
        "先做减法，留下最想表达的那个感觉。东西少一点，反而更容易看见核心。",
      )
      context.plan.act == ReactionAct.direct_answer && context.analysis.isQuestion -> listOf(
        "按现在能确定的信息，我会把有依据的部分直接说清；不确定的地方不顺着猜，也不装作知道。",
        "这个问题能确认多少就答多少。没有来源的部分留空，比编一个完整答案更诚实。",
        "我先给结论：事实和推测要分开。能追溯的我认，剩下的不拿语气强行补齐。",
      )
      else -> listOf(
        "我接住了。先不急着总结，也不把问题丢回给你，这句话本身就可以放在这里。",
        "嗯，我在听。我们沿着现在这条线慢慢聊，不突然切成采访模式。",
        "这句我记下。比起马上分析，我先给它留一点位置。",
      )
    }
    val recent = context.state.messages.filter { it.role == "assistant" }.takeLast(20).map { normalizeText(it.text) }.toSet()
    val fresh = pool.filter { normalizeText(it) !in recent }
    val ordered = (if (fresh.isNotEmpty()) fresh else pool).sortedBy { stableHash("${context.messageId}|${it}") }
    return ordered.take(max(1, count))
  }

  private fun directMemoryAnswer(input: String, context: WriterContext): List<String>? {
    val manager = context.memories
    val state = context.state
    fun value(key: String) = manager.current(state, key)?.value
    return when {
      Regex("我叫什么名字|我的名字是什么|还记得我的名字").containsMatchIn(input) -> listOf(value("name")?.let { "你叫$it，我记得。" } ?: "你还没告诉我名字，要不要现在说？")
      Regex("你叫我什么|你怎么叫我|该叫我什么").containsMatchIn(input) -> listOf((value("preferred_address") ?: value("name"))?.let { "我叫你$it，好不好？" } ?: "你还没说想让我怎么叫你。")
      Regex("我是你的谁|我和你是什么关系").containsMatchIn(input) -> listOf(value("user_role")?.let { "你是我的$it，当然记得。" } ?: "这个我们还没说好，我不乱猜。")
      Regex("你是我的谁|你是我什么人|我们是什么关系").containsMatchIn(input) -> listOf(value("pet_role")?.let { "我是你的$it。" } ?: "我是陪你聊天的三好兔，其他称呼你来定。")
      Regex("我喜欢什么|记得我喜欢什么").containsMatchIn(input) -> manager.all(state, "like").takeIf { it.isNotEmpty() }?.let { listOf("你说过喜欢${it.joinToString("、")}，我记得。") } ?: listOf("你还没跟我说过，我不想乱猜。")
      Regex("(?:^|[，。！？!?\\s])(?:我叫|我的名字(?:叫|是))").containsMatchIn(input) -> value("name")?.let { listOf("$it？好呀，记住了。", "$it，这名字真好听。", "好，我叫你$it。") }
      Regex("(?:可以|以后|就)?叫我").containsMatchIn(input) -> value("preferred_address")?.let { listOf("好，以后就这么叫你，$it。", "$it，好呀。", "嗯，$it，听你的。") }
      Regex("我(?:很|最|比较)?喜欢").containsMatchIn(input) -> manager.all(state, "like").lastOrNull()?.let { listOf("原来你喜欢$it，记下啦。", "喜欢${it}呀，这个我想听你多说一点。", "$it，听起来挺有意思的。") }
      else -> null
    }
  }
}

class NoveltyReranker(private val similarity: SemanticSimilarityProvider = LexicalSimilarityProvider()) {
  fun rank(candidates: List<String>, plan: ReactionPlan, state: PersonaState, messageId: String, input: String = ""): Pair<List<CandidateScore>, Int> {
    val history = state.messages.filter { it.role == "assistant" }.takeLast(20).map { it.text }
    val scores = candidates.map { candidate ->
      val reasons = mutableListOf<String>()
      var score = 100.0 + (stableHash(candidate + messageId) % 17) / 100.0
      val normalized = normalizeText(candidate)
      val exact = history.any { it == candidate }
      val normalizedRepeat = history.any { normalizeText(it) == normalized }
      val exposedMechanism = Regex("第[一二三四五六七八九十\\d]+次.{0,8}(说|问|介绍)|提问权重|问题密度|消息关联|长期偏好|候选回复|元回调|同义句|重复检测|记忆已更新").containsMatchIn(candidate)
      val userEcho = UserEchoDetector.copied(candidate,input,state)
      val rejected = candidate.isBlank() || exact || normalizedRepeat || exposedMechanism || userEcho
      if(userEcho) reasons += "copied user message"
      if (candidate.isBlank()) reasons += "empty reply"
      if (exact) reasons += "exact duplicate"
      if (normalizedRepeat && !exact) reasons += "normalized duplicate"
      if (exposedMechanism) reasons += "internal mechanism exposed"
      val overlap = history.maxOfOrNull { similarity.similarity(candidate, it) } ?: 0.0
      if (overlap > 0.42) { val penalty = overlap * 44; score -= penalty; reasons += "lexical overlap %.2f".format(Locale.ROOT, overlap) }
      val start = normalized.take(8)
      val end = normalized.takeLast(8)
      if (start.length >= 5 && history.any { normalizeText(it).startsWith(start) }) { score -= 15; reasons += "repeated opening" }
      if (end.length >= 5 && history.any { normalizeText(it).endsWith(end) }) { score -= 12; reasons += "repeated ending" }
      val recentActs = state.actHistory.takeLast(4)
      val actCount = recentActs.count { it == plan.act }
      if (actCount > 0) { score -= actCount * 7; reasons += "act cooldown x$actCount" }
      val catchphrases = listOf("hiahia", "搞忘了", "滴")
      for (phrase in catchphrases) if (candidate.contains(phrase) && state.voiceHistory.takeLast(8).contains(phrase)) { score -= 26; reasons += "voice cooldown $phrase" }
      val questionCount = candidate.count { it == '?' || it == '？' }
      if (questionCount > 0 && (state.questionSuppression > 0 || history.lastOrNull()?.let { it.contains("？") || it.contains("?") } == true)) { score -= 34 * questionCount; reasons += "question density" }
      val petDensity = Regex("三好兔|小兔|兔子").findAll(candidate).count()
      if (petDensity > 1) { score -= (petDensity - 1) * 12; reasons += "pet element density" }
      CandidateScore(candidate, if (rejected) -10_000.0 else score, rejected, reasons)
    }
    val selected = scores.indices.filter { !scores[it].rejected }.maxByOrNull { scores[it].score }
      ?: throw IllegalStateException("No acceptable generated candidate")
    return scores to selected
  }
}

class UnifiedPersonaCore(
  private val store: PersonaStateStore,
  private val bundle: PersonaBundle = PersonaBundle.defaults(),
  private val now: () -> Long = { System.currentTimeMillis() },
  // Retained only for existing Android release/legacy Replay. Service injects a model Writer.
  writer: CandidateWriter = TemplateWriter(),
) {
  private val analyzer = InputAnalyzer(bundle)
  private val memoryManager = MemoryManager()
  private val planner = ReactionPlanner(bundle)
  private val candidateGenerator = CandidateGenerator(writer)
  private val reranker = NoveltyReranker()

  @Synchronized
  fun reply(messageId: String, input: String, entryPoint: String): EngineReply {
    val state = store.load().detached()
    require(messageId.isNotBlank() && input.isNotBlank())
    require(input.trim().length <= 2000) { "Input exceeds 2000 characters" }
    val clean = input.trim()
    val analysis = analyzer.analyze(clean, state)
    val change = memoryManager.learn(messageId, clean, state, now())
    if (analysis.topic.isNotBlank()) state.lastTopic = analysis.topic
    val plan = planner.plan(clean, analysis, state)
    val candidates = candidateGenerator.generate(WriterContext(clean, messageId, analysis, plan, state, memoryManager, bundle), 3)
    val (scored, selectedIndex) = reranker.rank(candidates, plan, state, messageId, clean)
    val selected = scored[selectedIndex].text
    val assistantId = "$messageId-assistant"
    state.messages += PersonaMessage(messageId, "user", clean, entryPoint, now())
    state.messages += PersonaMessage(assistantId, "assistant", selected, entryPoint, now())
    while (state.messages.size > 160) state.messages.removeAt(0)
    state.actHistory += plan.act
    while (state.actHistory.size > 40) state.actHistory.removeAt(0)
    listOf("hiahia", "搞忘了", "滴").filter { selected.contains(it) }.forEach { state.voiceHistory += it }
    while (state.voiceHistory.size > 20) state.voiceHistory.removeAt(0)
    state.questionSuppression = when {
      plan.act == ReactionAct.repair -> 3
      selected.contains("？") || selected.contains("?") -> 2
      else -> max(0, state.questionSuppression - 1)
    }
    store.save(state)
    val delta = buildList {
      if (change.added.isNotEmpty()) add("memoryAdded:${change.added.joinToString(",")}")
      if (change.updated.isNotEmpty()) add("memoryUpdated:${change.updated.joinToString(",")}")
      add("workingMemory:${state.messages.takeLast(16).size}")
      add("questionSuppression:${state.questionSuppression}")
    }
    return EngineReply(selected, assistantId, plan.act, EngineTrace("UnifiedPersonaEngine/shared-kotlin-v2", entryPoint, analysis, plan, scored, selectedIndex, delta))
  }

  @Synchronized
  fun retract(messageIds: Set<String>): MemoryManager.Change {
    val state = store.load()
    state.messages.removeAll { it.id in messageIds || (it.role == "assistant" && it.id.removeSuffix("-assistant") in messageIds) }
    val change = memoryManager.retract(messageIds, state)
    store.save(state)
    return change
  }

  @Synchronized
  fun clearConversation() {
    store.save(PersonaState())
  }

  @Synchronized
  fun importLegacy(messages: List<PersonaMessage>, legacy: Map<String, List<String>>) {
    val state = store.load()
    if (state.messages.isNotEmpty() || state.memories.isNotEmpty()) return
    messages.takeLast(80).forEach { message ->
      state.messages += message
      if (message.role == "user") memoryManager.learn(message.id, message.text, state, message.createdAt)
    }
    val mapping = mapOf(
      "name" to ("long_term" to "name"),
      "preferredAddress" to ("relationship" to "preferred_address"),
      "userRole" to ("relationship" to "user_role"),
      "petRole" to ("relationship" to "pet_role"),
      "likes" to ("long_term" to "like"),
    )
    mapping.forEach { (legacyKey, target) ->
      legacy[legacyKey].orEmpty().filter { it.isNotBlank() }.forEach { value ->
        if (state.memories.none { it.key == target.second && it.value == value }) {
          state.memories += PersonaMemory(
            "mem-${stableHash("${target.first}|${target.second}|$value")}",
            target.first,
            target.second,
            value,
            mutableSetOf("legacy-migration:$legacyKey"),
            now(),
            0.55,
          )
        }
      }
    }
    legacy["currentTopic"]?.lastOrNull()?.let { state.lastTopic = it }
    store.save(state)
  }

  @Synchronized
  fun setManualMemory(manual: Map<String, List<String>>) {
    val state = store.load()
    val mapping = listOf(
      Triple("name", "long_term", "name"),
      Triple("preferredAddress", "relationship", "preferred_address"),
      Triple("userRole", "relationship", "user_role"),
      Triple("petRole", "relationship", "pet_role"),
      Triple("likes", "long_term", "like"),
      Triple("correction", "correction", "voice_style"),
    )
    var timestamp = maxOf(now(), (state.memories.maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE) + 1)
    mapping.forEach { (inputKey, layer, memoryKey) ->
      if (!manual.containsKey(inputKey)) return@forEach // Partial edits must not clear unrelated memory.
      val previous = state.memories.filter { it.key == memoryKey }.map { it.id }.toMutableSet()
      state.memories.removeAll { it.key == memoryKey }
      manual[inputKey].orEmpty().map(String::trim).filter(String::isNotBlank).distinct().forEachIndexed { index, value ->
        val createdAt = timestamp + index
        state.memories += PersonaMemory(
          "mem-${stableHash("$layer|$memoryKey|$value|manual|$createdAt")}",
          layer,
          memoryKey,
          value.take(120),
          mutableSetOf("manual-setting:$createdAt:$memoryKey"),
          createdAt,
          0.98,
          previous,
        )
      }
      timestamp += maxOf(1, manual[inputKey].orEmpty().size)
    }
    if (manual.containsKey("currentTopic")) state.lastTopic = manual["currentTopic"].orEmpty().lastOrNull()?.trim().orEmpty().take(80)
    store.save(state)
  }

  @Synchronized
  fun state(): PersonaState = store.load()
}
