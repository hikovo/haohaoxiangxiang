package com.hikovo.mobilepet

/** Product limit, not a claim that all public posts are short. Explicit requests may expand. */
object ReplyLengthPolicy {
  fun expanded(input:String)=Regex("详细|展开|多说一点|讲细一点|长一点|一步一步|具体解释").containsMatchIn(input)
  fun limit(input:String)=if(expanded(input))360 else 100
  fun direction(input:String)=if(expanded(input))"用户要求展开，最多360字。" else "日常接话一两句，尽量20到60字，最多100字；不写长段安慰或建议清单。"
  fun check(text:String,input:String):String {
    require(text.codePointCount(0,text.length)<=limit(input)){"Reply exceeds conversational length budget"}
    return text
  }
}
