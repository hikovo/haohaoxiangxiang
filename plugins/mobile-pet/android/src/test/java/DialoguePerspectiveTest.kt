package com.hikovo.mobilepet

import org.junit.Assert.*
import org.junit.Test

class DialoguePerspectiveTest {
  private fun learn(vararg inputs: String): PersonaState {
    val state=PersonaState();val memory=MemoryManager()
    inputs.forEachIndexed { i,text -> memory.learn("message-$i",text,state,i.toLong()) }
    return state
  }
  private fun value(state: PersonaState,key: String)=MemoryManager().current(state,key)?.value
  private fun context(state: PersonaState)=WriterContext("我叫什么？","test",InputAnalysis("","",true,0,0,emptyList()),
    ReactionPlan(ReactionAct.direct_answer,"test",emptyList()),state,MemoryManager(),PersonaBundle.defaults())

  @Test fun namesAddressesAndPreferencesKeepTheirOwner() {
    val state=learn("我的名字是苹果，但你叫我 my light", "你叫小苏", "我喜欢摄影", "你喜欢舞蹈")
    assertEquals("苹果",value(state,"name"));assertEquals("my light",value(state,"preferred_address"))
    assertEquals(listOf("摄影"),MemoryManager().all(state,"like"))
    assertEquals("user",DialoguePerspective.owner("name"));assertEquals("assistant",DialoguePerspective.owner("pet_role"))
  }
  @Test fun quotesQuestionsNegationsAndHypotheticalsDoNotBecomeUserFacts() {
    val state=learn("我叫苹果", "我叫什么呀？", "我的名字是什么", "朋友说：我叫梨子", "朋友说：我喜欢音乐",
      "他说‘我叫香蕉’", "如果我叫橘子呢", "不是我喜欢舞蹈，是你喜欢舞蹈", "我不喜欢足球", "你喜欢摄影吗？")
    assertEquals("苹果",value(state,"name"));assertTrue(MemoryManager().all(state,"like").isEmpty())
  }
  @Test fun relationDirectionDoesNotInvertOrInferTheOtherSide() {
    val state=learn("我是你的姐姐", "你是我的弟弟")
    assertEquals("姐姐",value(state,"user_role"));assertEquals("弟弟",value(state,"pet_role"))
    assertNull(value(learn("我是你的朋友"),"pet_role"))
  }
  @Test fun returningToAnOldNameWinsAndRetractionRestoresPreviousName() {
    val state=learn("我叫苹果", "我叫灯灯", "我叫苹果")
    assertEquals("苹果",value(state,"name"));assertEquals(3,state.memories.map {it.id}.distinct().size)
    MemoryManager().retract(setOf("message-2"),state)
    assertEquals("灯灯",value(state,"name"))
  }
  @Test fun correctionsAndTwoSubjectsInOneMessageRemainSeparate() {
    val state=learn("我叫苹果", "不对，我叫灯灯，你叫小苏", "我喜欢摄影，你喜欢舞蹈")
    assertEquals("灯灯",value(state,"name"));assertEquals(listOf("摄影"),MemoryManager().all(state,"like"))
    assertEquals(listOf("user_name","assistant_name"),DialoguePerspective.targets("我叫什么，你叫什么？"))
    assertEquals(listOf("assistant_preferences"),DialoguePerspective.targets("你喜欢什么？"))
  }
  @Test fun explicitNameSwapsAreRejectedButAddressingAndQuotingAreAllowed() {
    val c=context(learn("我叫苹果"))
    listOf("我叫苹果。", "我是苹果呀。", "你是小苏。", "你叫三好兔。", "三好兔，你好呀！").forEach {
      try { DialoguePerspective.check(it,c);fail("wrong owner: $it") } catch (_:IllegalArgumentException) {}
    }
    listOf("苹果呀。", "你叫苹果，我叫小苏。", "我记得你叫苹果。", "你说过“我叫苹果”。", "我不是苹果，你才是。").forEach {
      assertEquals(it,DialoguePerspective.check(it,c))
    }
  }
  @Test fun unknownUserStaysUnknownAndSharedNamesAreNotAutomaticallyErrors() {
    assertNull(value(learn("你叫小苏"),"name"))
    val c=context(learn("我叫小苏"))
    assertEquals("你也叫小苏呀。",DialoguePerspective.check("你也叫小苏呀。",c))
    assertEquals("你叫小苏。",DialoguePerspective.check("你叫小苏。",c))
  }
  @Test fun bothNamesMustBeAnsweredWhenBothAreAsked() {
    val c=context(learn("我叫苹果", "叫我 my light")).copy(input="我叫什么，你叫什么？")
    listOf("三好兔可以替换成my light。", "你叫苹果。", "我叫小苏。").forEach {
      try { DialoguePerspective.check(it,c);fail("missing identity answer: $it") } catch (_:IllegalArgumentException) {}
    }
    assertEquals("你叫苹果，我叫小苏。",DialoguePerspective.check("你叫苹果，我叫小苏。",c))
  }
}
