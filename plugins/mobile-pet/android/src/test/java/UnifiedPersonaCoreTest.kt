package com.hikovo.mobilepet

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class UnifiedPersonaCoreTest {
  private fun engine(store: InMemoryPersonaStateStore = InMemoryPersonaStateStore()) = UnifiedPersonaCore(store, now = { 1_800_000_000_000L })

  @Test
  fun repeatedMissingVariesActsWithoutExposingRepeatCounters() {
    val runtime = engine()
    val replies = (1..10).map { runtime.reply("miss-$it", "我想你了", "main") }
    assertEquals(10, replies.map { it.reply }.distinct().size)
    assertTrue(replies.map { it.act }.distinct().size >= 3)
    assertTrue(replies.none { Regex("第[一二三四五六七八九十\\d]+次|重复|候选|权重|冷却|同义句").containsMatchIn(it.reply) })
  }

  @Test
  fun repeatedIntroductionDoesNotRepeatInterestInventory() {
    val runtime = engine()
    val replies = (1..5).map { runtime.reply("intro-$it", "介绍一下你自己", "main") }
    assertEquals(ReactionAct.self_share, replies.first().act)
    assertTrue(replies.drop(1).map { it.act }.distinct().size >= 2)
    assertTrue(replies.none { Regex("第[一二三四五六七八九十\\d]+次|重复|记忆").containsMatchIn(it.reply) })
    assertTrue(replies.drop(1).none { it.reply.contains("舞蹈、音乐、摄影、设计") })
  }

  @Test
  fun repeatedGreetingChangesBehaviorWithoutExactReplays() {
    val runtime = engine()
    val replies = (1..10).map { runtime.reply("hello-$it", "你好呀", "main") }
    assertEquals(10, replies.map { it.reply }.distinct().size)
    assertTrue(replies.drop(1).map { it.act }.distinct().size >= 3)
    assertTrue(replies.drop(2).any { it.reply.contains("刚才") || it.reply.contains("不用") || it.reply.contains("门口") })
    assertTrue(replies.none { Regex("第[一二三四五六七八九十\\d]+次|重复|候选|权重").containsMatchIn(it.reply) })
  }

  @Test
  fun reactionCasePreferencesInfluencePlanning() {
    val bundle = PersonaBundle(
      "测试用最小核心",
      listOf(ReactionCase("paper-plane", listOf("纸飞机"), listOf(ReactionAct.serious_expand), "看具体做法", "空泛夸奖")),
      emptyList(),
      emptyList(),
    )
    val runtime = UnifiedPersonaCore(InMemoryPersonaStateStore(), bundle, now = { 1_800_000_000_000L })
    val reply = runtime.reply("case-1", "我做了一架纸飞机", "main")
    assertEquals(ReactionAct.serious_expand, reply.act)
    assertEquals(listOf("paper-plane"), reply.trace.plan.caseIds)
  }

  @Test
  fun commonIntentsWorkBeyondTheAppleReplayPhrases() {
    val runtime = engine()
    val greeting = runtime.reply("intent-1", "嗨小苏", "main")
    val overload = runtime.reply("intent-2", "一堆任务还要做", "main")
    val leaving = runtime.reply("intent-3", "我先去忙了", "main")
    assertTrue(greeting.reply.contains("来") || greeting.reply.contains("你好"))
    assertEquals(ReactionAct.specific_care, overload.act)
    assertTrue(overload.reply.contains("一件"))
    assertEquals(ReactionAct.brief_reaction, leaving.act)
    assertTrue(leaving.reply.contains("去") || leaving.reply.contains("忙"))
  }

  @Test
  fun rerankerRejectsVisibleEngineMechanics() {
    val plan = ReactionPlan(ReactionAct.brief_reaction, "test", emptyList())
    val (scores, selected) = NoveltyReranker().rank(
      listOf("这是你第八次说想我了。", "我已经降低提问权重。", "嗯，我在。"),
      plan,
      PersonaState(),
      "mechanism-test",
    )
    assertTrue(scores[0].rejected)
    assertTrue(scores[1].rejected)
    assertEquals(2, selected)
  }

  @Test
  fun ordinaryQuestionDoesNotMatchQuestionMarkRepairCase() {
    val bundle = PersonaBundle(
      "测试用最小核心",
      listOf(ReactionCase("question-mark", listOf("？", "?"), listOf(ReactionAct.callback, ReactionAct.repair), "看前文", "机械回复")),
      emptyList(),
      emptyList(),
    )
    val runtime = UnifiedPersonaCore(InMemoryPersonaStateStore(), bundle, now = { 1_800_000_000_000L })
    val reply = runtime.reply("ordinary-question", "今天怎么样？", "main")
    assertEquals(ReactionAct.direct_answer, reply.act)
    assertTrue(reply.trace.analysis.matchedCaseIds.isEmpty())
  }

  @Test
  fun questionMarkUsesContextAndQuestionComplaintRepairs() {
    val runtime = engine()
    runtime.reply("context-1", "我今天有点累", "main")
    val mark = runtime.reply("context-2", "？", "main")
    assertFalse(mark.reply == "怎么了")
    assertTrue(mark.act == ReactionAct.callback || mark.act == ReactionAct.repair)
    val repair = runtime.reply("context-3", "为什么老问我问题", "main")
    assertEquals(ReactionAct.repair, repair.act)
    assertFalse(repair.reply.contains("？"))
    assertTrue(runtime.state().questionSuppression > 0)
  }

  @Test
  fun lightInsultIsNeitherAttackNorCustomerServiceApology() {
    val reply = engine().reply("tease-1", "你好笨", "main")
    assertEquals(ReactionAct.light_tease, reply.act)
    assertFalse(reply.reply.contains("抱歉"))
    assertFalse(reply.reply.contains("你才"))
  }

  @Test
  fun memoryRetractionPreservesOtherSourcesThenRemovesLastSource() {
    val store = InMemoryPersonaStateStore()
    val runtime = engine(store)
    runtime.reply("like-1", "我喜欢苹果", "main")
    runtime.reply("like-2", "我喜欢苹果", "memo")
    var memory = runtime.state().memories.single { it.key == "like" && it.value == "苹果" }
    assertEquals(setOf("like-1", "like-2"), memory.sourceMessageIds)
    runtime.retract(setOf("like-1"))
    memory = runtime.state().memories.single { it.key == "like" && it.value == "苹果" }
    assertEquals(setOf("like-2"), memory.sourceMessageIds)
    runtime.retract(setOf("like-2"))
    assertTrue(runtime.state().memories.none { it.key == "like" && it.value == "苹果" })
  }

  @Test
  fun manualMemoryAndChatLearningShareLatestWinsTimeline() {
    val runtime = engine()
    runtime.reply("auto-name-1", "我叫苹果", "main")
    runtime.reply("auto-like-1", "我喜欢舞台", "main")
    runtime.setManualMemory(mapOf(
      "name" to listOf("灯灯"),
      "preferredAddress" to listOf("my light"),
      "likes" to listOf("粉色", "苹果"),
      "currentTopic" to listOf("最近的学习计划"),
      "correction" to listOf("少问问题"),
    ))
    var state = runtime.state()
    assertEquals("灯灯", state.memories.filter { it.key == "name" }.maxBy { it.createdAt }.value)
    assertEquals("my light", state.memories.filter { it.key == "preferred_address" }.maxBy { it.createdAt }.value)
    assertEquals(setOf("粉色", "苹果"), state.memories.filter { it.key == "like" }.map { it.value }.toSet())
    assertEquals("最近的学习计划", state.lastTopic)

    runtime.reply("auto-name-2", "我叫星星", "main")
    state = runtime.state()
    assertEquals("星星", state.memories.filter { it.key == "name" }.maxBy { it.createdAt }.value)
    assertTrue(state.memories.any { it.key == "name" && it.value == "灯灯" })
  }

  @Test
  fun allEntryPointsShareTheSamePolicy() {
    fun run(entry: String) = engine().reply("same-message", "你好笨", entry)
    val main = run("main")
    val memo = run("memo")
    val overlay = run("overlay")
    assertEquals(main.reply, memo.reply)
    assertEquals(main.reply, overlay.reply)
    assertEquals(main.act, memo.act)
    assertEquals(main.act, overlay.act)
    assertNotEquals(main.trace.entryPoint, overlay.trace.entryPoint)
  }

  @Test
  fun appleConversationFixtureStaysHiddenAndRuntimeOwned() {
    val fixture = File(projectRoot(), "persona-data/evals/apple-conversation-v1.json").readText()
    assertTrue(fixture.contains("\"visibleInUi\": false"))
    assertTrue(fixture.contains("\"enabledFrom\": \"1.3.3\""))
    assertTrue(fixture.contains("\"assistantPolicy\": \"runtime-generated\""))
    assertTrue(fixture.contains("\"name\": \"苹果\""))
    assertTrue(fixture.contains("\"referenceOnly\": true"))
  }

  @Test
  fun appleConversationReplayUsesBuiltRuntimeFromV133() {
    assumeTrue(versionAtLeast(appVersion(), "1.3.3"))
    val runtime = engine()
    val inputs = listOf("你好呀小苏", "我叫苹果，记住哦", "我等会还有好多东西要做", "好，那我先去做啦")
    val results = inputs.mapIndexed { index, input -> runtime.reply("apple-${index + 1}", input, "replay") }
    assertTrue(results.all { it.reply.isNotBlank() })
    assertEquals("苹果", runtime.state().memories.last { it.key == "name" }.value)

    val output = File(projectRoot(), "build/replay/apple-conversation-latest.jsonl")
    output.parentFile?.mkdirs()
    output.writeText(results.mapIndexed { index, result ->
      "{\"scenario\":\"apple-hidden-conversation-v1\",\"engine\":\"runtime-generated\",\"input\":${quote(inputs[index])},\"output\":${quote(result.reply)},\"act\":${quote(result.act.name)}}"
    }.joinToString("\n", postfix = "\n"))
  }

  @Test
  fun replayGateWritesAuditableArtifacts() {
    val output = File(projectRoot(), "build/replay")
    output.mkdirs()
    val jsonl = File(output, "unified-replay.jsonl")
    val rows = mutableListOf<String>()
    val runtime = engine()
    fun record(scenario: String, messageId: String, input: String, entry: String = "main"): EngineReply {
      val result = runtime.reply(messageId, input, entry)
      val candidates = result.trace.candidates.joinToString(",") { candidate ->
        "{\"text\":${quote(candidate.text)},\"score\":${candidate.score},\"rejected\":${candidate.rejected},\"reasons\":[${candidate.reasons.joinToString(",") { quote(it) }}]}"
      }
      rows += "{\"engine\":\"new-kotlin-v1\",\"scenario\":${quote(scenario)},\"messageId\":${quote(messageId)},\"input\":${quote(input)},\"entryPoint\":${quote(entry)},\"output\":${quote(result.reply)},\"act\":${quote(result.act.name)},\"selectedIndex\":${result.trace.selectedIndex},\"candidates\":[$candidates],\"stateDelta\":[${result.trace.stateDelta.joinToString(",") { quote(it) }}]}"
      return result
    }
    val missing = (1..10).map { record("repeat-missing", "r-miss-$it", "我想你了") }
    val greeting = (1..10).map { record("repeat-greeting", "r-hello-$it", "你好呀") }
    val intro = (1..5).map { record("repeat-introduction", "r-intro-$it", "介绍一下你自己") }
    record("question-mark", "r-mark", "？")
    record("light-insult", "r-insult", "你好笨")
    record("question-repair", "r-repair", "为什么老问我问题")

    val deletionStore = InMemoryPersonaStateStore()
    val deletion = engine(deletionStore)
    val first = deletion.reply("r-memory-1", "我喜欢苹果", "main")
    val second = deletion.reply("r-memory-2", "我喜欢苹果", "memo")
    deletion.retract(setOf("r-memory-1"))
    val afterOne = deletion.state().memories.first { it.key == "like" }.sourceMessageIds.toList()
    deletion.retract(setOf("r-memory-2"))
    val afterAll = deletion.state().memories.filter { it.key == "like" }
    rows += "{\"engine\":\"new-kotlin-v1\",\"scenario\":\"memory-retraction\",\"input\":\"我喜欢苹果 x2\",\"outputs\":[${quote(first.reply)},${quote(second.reply)}],\"afterOneSource\":[${afterOne.joinToString(",") { quote(it) }}],\"afterAllCount\":${afterAll.size}}"

    listOf("main", "memo", "overlay").forEach { entry ->
      val result = engine().reply("r-entry", "你好笨", entry)
      rows += "{\"engine\":\"new-kotlin-v1\",\"scenario\":\"entry-consistency\",\"entryPoint\":${quote(entry)},\"input\":\"你好笨\",\"output\":${quote(result.reply)},\"act\":${quote(result.act.name)}}"
    }
    jsonl.writeText(rows.joinToString("\n", postfix = "\n"))

    val legacy = LegacyReplayFixture()
    val oldMissing = (1..10).map { legacy.reply("我想你了", "main") }
    val oldIntro = (1..5).map { legacy.reply("介绍一下你自己", "main") }
    val currentVersion = appVersion()
    val summary = """
      # Unified Persona Engine Replay

      Generated by the Kotlin runtime test suite. The legacy column is a deterministic fixture of
      the v1.2.7 relevant rules from checkpoint `eaba551`; it is evaluation-only, not production code.

      | Gate | v1.2.7 legacy fixture | v$currentVersion Kotlin runtime |
      |---|---:|---:|
      | 10x 我想你了 exact duplicate count | ${oldMissing.size - oldMissing.distinct().size} | ${missing.size - missing.map { it.reply }.distinct().size} |
      | 10x 你好呀 exact duplicate count | n/a | ${greeting.size - greeting.map { it.reply }.distinct().size} |
      | distinct acts for 10x 你好呀 | n/a | ${greeting.map { it.act }.distinct().size} |
      | distinct acts for 10x 我想你了 | 1 | ${missing.map { it.act }.distinct().size} |
      | replies exposing repeat counters | n/a | ${missing.count { Regex("第[一二三四五六七八九十\\d]+次").containsMatchIn(it.reply) }} |
      | 5x self-intro exact duplicate count | ${oldIntro.size - oldIntro.distinct().size} | ${intro.size - intro.map { it.reply }.distinct().size} |
      | distinct acts for 5x self-intro | 1 | ${intro.map { it.act }.distinct().size} |
      | deletion retracts unique-source memory | no | yes |
      | keeps remaining source for same fact | no provenance | yes |
      | main/memo/overlay policy owner | split | one Kotlin runtime |

      Full candidate scores, rejection reasons, acts and state deltas are in `unified-replay.jsonl`.
      Similarity is lexical/structural in v$currentVersion; semantic similarity is an interface, not a claimed feature.
    """.trimIndent() + "\n"
    File(output, "REPLAY_SUMMARY.md").writeText(summary)
    assertEquals(0, missing.size - missing.map { it.reply }.distinct().size)
  }

  private fun projectRoot(): File {
    var current = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
    repeat(7) {
      if (File(current, "package.json").exists() && File(current, "src-tauri").exists()) return current
      current = current.parentFile ?: return@repeat
    }
    error("Cannot locate app root from ${System.getProperty("user.dir")}")
  }

  private fun appVersion(): String {
    val packageJson = File(projectRoot(), "package.json").readText()
    return Regex("\\\"version\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(packageJson)?.groupValues?.get(1)
      ?: error("Cannot read app version")
  }

  private fun versionAtLeast(actual: String, minimum: String): Boolean {
    fun parts(value: String) = value.split('.').map { it.toIntOrNull() ?: 0 }
    val left = parts(actual)
    val right = parts(minimum)
    return (0 until maxOf(left.size, right.size)).firstNotNullOfOrNull { index ->
      val delta = (left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 })
      delta.takeIf { it != 0 }
    }?.let { it > 0 } ?: true
  }

  private fun quote(value: String): String = "\"" + value
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""
}

/** Snapshot of the relevant v1.2.7 rules for side-by-side replay only. */
private class LegacyReplayFixture {
  private var index = 0
  fun reply(input: String, entry: String): String {
    index += 1
    if (entry == "overlay") return if (input.contains("笨")) "收到。我先不急着总结，也不把问题丢回去。" else "我在呢。"
    if (input.contains("想你")) return listOf("我也想。你突然这么一说，我一下就开心了。", "想念收到啦，我先好好把这句话揣进口袋。")[(index - 1) % 2]
    if (input.contains("介绍")) return listOf(
      "我叫苏新皓，熟一点叫小苏、帅帅都行。跳舞和音乐基本绕不开，平时也喜欢拍东西、画画、做点小设计；现在是三好兔的样子陪你聊天。",
      "大名苏新皓，叫我小苏或者帅帅都可以。很喜欢舞蹈、音乐和舞台创作，也会拍照、画画；现在顶着三好兔这张脸坐在这里。",
      "我是苏新皓。跳舞是很重要的一部分，音乐、钢琴、摄影和视觉设计也都喜欢。熟了以后叫小苏就好，现在是三好兔形态。",
    )[(index - 1) % 3]
    return "收到。"
  }
}
