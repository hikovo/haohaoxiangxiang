package com.hikovo.mobilepet

import java.text.Normalizer
import java.util.Locale

/** Detect copying a user's earlier message as the assistant's own answer. */
object UserEchoDetector {
  private fun normalize(text: String) = Normalizer.normalize(text.lowercase(Locale.ROOT),Normalizer.Form.NFKC)
    .replace(Regex("[\\p{P}\\p{S}\\s]+"),"").replace(Regex("我|你|您"),"人")

  private fun overlap(answer:String,source:String):Int {
    var previous=IntArray(source.length+1);var longest=0
    for(character in answer){
      val next=IntArray(source.length+1)
      for(j in source.indices)if(character==source[j]){next[j+1]=previous[j]+1;longest=maxOf(longest,next[j+1])}
      previous=next
    }
    return longest
  }

  fun copied(candidate: String,input: String,state: PersonaState): Boolean {
    // A requested quotation or a factual recall can legitimately reuse its source words.
    if(Regex("复述|原话|重复.{0,5}(我|这句)|引用|我(?:叫|喜欢)(?:什么|啥)|我的名字是什么|怎么称呼我").containsMatchIn(input))return false
    val answer=normalize(candidate)
    if(answer.length<8)return false
    return (state.messages.filter { it.role=="user" }.takeLast(20).map {it.text}+input).any { text ->
      val source=normalize(text)
      if(source.length<8)false else {
        val longest=overlap(answer,source)
        val ownClaim=DialoguePerspective.assertions(candidate).any { clause ->
          clause.contains("我") && !Regex("你说|你提到|听你说|记得你|你刚|你之前").containsMatchIn(clause) &&
            DialoguePerspective.assertions(text).any { it.contains("我") && overlap(normalize(clause),normalize(it))>=10 }
        }
        answer==source || ownClaim || (longest>=10 && longest.toDouble()/answer.length>=0.78)
      }
    }
  }
  fun check(candidate: String,c: WriterContext): String {
    require(!copied(candidate,c.input,c.state)) { "Reply copied the user's words" }
    return candidate
  }
}
