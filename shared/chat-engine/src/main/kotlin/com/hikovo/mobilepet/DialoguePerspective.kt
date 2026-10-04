package com.hikovo.mobilepet

/** Resolve ownership before language generation. Values still live in the existing MemoryManager. */
object DialoguePerspective {
  val assistantNames = listOf("三好兔", "小苏")

  fun owner(key: String): String = when (key) {
    "pet_role" -> "assistant"
    "question_style", "voice_style" -> "conversation"
    else -> "user"
  }

  fun label(key: String): String = when (key) {
    "name" -> "用户名字"
    "preferred_address" -> "用户希望被称呼"
    "like" -> "用户喜欢"
    "user_role" -> "用户相对于三好兔的关系称呼"
    "pet_role" -> "三好兔相对于用户的关系称呼"
    "recent_event" -> "用户讲述的经历（原句中我指用户）"
    else -> "用户提出的聊天偏好"
  }

  /** Quoted/imagined/reported clauses are not first-person autobiographical assertions. */
  fun assertions(text: String): List<String> {
    val unquoted = text
      .replace(Regex("```[\\s\\S]*?```|“[^”]*”|「[^」]*」|『[^』]*』|\"[^\"]*\"|'[^']*'|‘[^’]*’|`[^`]*`"), " 引用内容 ")
    return unquoted.split(Regex("[，,。；;！？!?\\n]+"))
      .map { it.trim().replace(Regex("^(?:其实|对了|另外|不过|但是|但|不对|更正一下)[：: ]*"), "") }
      .filter { it.isNotBlank() && !Regex("如果|假如|假设|比如|例如|可能|也许|不是|并非|并不|我不|我没|是不是|是否|什么|怎么|为何|吗|么|谁|叫啥|说[：:]|问[：:]").containsMatchIn(it) }
  }

  fun targets(input: String): List<String> = buildList {
    val text = input.replace(Regex("“[^”]*”|「[^」]*」|\"[^\"]*\""), "")
    if (Regex("我(?:到底)?叫(?:什么|啥)|我的名字(?:是(?:什么|啥)|叫)|我是谁").containsMatchIn(text)) add("user_name")
    if (Regex("你(?:到底)?叫(?:什么|啥)|你的名字(?:是(?:什么|啥)|叫)").containsMatchIn(text)) add("assistant_name")
    if (Regex("怎么叫我|怎么称呼我").containsMatchIn(text)) add("user_address")
    if (Regex("我.{0,4}喜欢").containsMatchIn(text)) add("user_preferences")
    if (Regex("你.{0,4}喜欢").containsMatchIn(text)) add("assistant_preferences")
  }.distinct()

  fun direction(c: WriterContext): String {
    val topics = targets(c.input).joinToString("；") { when (it) {
      "user_name" -> "当前涉及用户名字，读取用户名字；不要回答三好兔的名字"
      "assistant_name" -> "当前涉及三好兔的称呼，可用三好兔或小苏；不要把用户名字当成自己的名字"
      "user_address" -> "当前涉及怎样称呼用户，优先读取用户希望被称呼的称呼"
      "user_preferences" -> "用户喜好只来自用户自己明确说过的内容"
      else -> "三好兔的喜好来自人物资料，不能把用户喜好复制给三好兔"
    } }
    return "用户发言中的我=用户、你=三好兔；三好兔回复中的我=三好兔、你=用户。双方名字、称呼、喜好、经历各自归属，不互换。引用里的我归被引用的说话人。$topics"
  }

  /** Reject explicit name swaps; absence of a match is not a semantic-quality certificate. */
  fun check(reply: String, c: WriterContext): String {
    val userNames = listOfNotNull(c.memories.current(c.state, "name")?.value,
      c.memories.current(c.state, "preferred_address")?.value).filter { it !in assistantNames }
    val clauses = assertions(reply)
    fun claims(clause: String, subject: String, names: List<String>): Boolean = names.any { name ->
      Regex("^$subject(?:的名字(?:是|叫)|叫|是)\\s*${Regex.escape(name)}(?:[呀啊哦呢啦～~\\s]*$|[，,。！!])").containsMatchIn(clause)
    }
    require(clauses.none { claims(it, "我", userNames) }) { "Assistant claimed the user's name" }
    require(clauses.none { claims(it, "你", assistantNames.filter { name -> name !in userNames && name != c.memories.current(c.state,"name")?.value }) }) { "User assigned the assistant's name" }
    val actualUserLabels=listOfNotNull(c.memories.current(c.state,"name")?.value,c.memories.current(c.state,"preferred_address")?.value)
    require(assistantNames.filter { it !in actualUserLabels }.none { name ->
      Regex("^${Regex.escape(name)}[，,、！!]").containsMatchIn(reply.trim())
    }) { "Assistant addressed the user with its own name" }
    val targets=targets(c.input)
    require(c.memories.current(c.state,"name")==null || !Regex("你(?:到底)?叫(?:什么|啥)|你的名字(?:是(?:什么|啥)|叫什么)").containsMatchIn(reply)) { "Asked for an already known user name" }
    if("user_name" in targets) c.memories.current(c.state,"name")?.value?.let {
      require(reply.contains(it,ignoreCase=true)) { "Reply did not answer the user's name" }
    }
    if("assistant_name" in targets) require(assistantNames.any { reply.contains(it) }) { "Reply did not answer the assistant's name" }
    if("user_address" in targets) (c.memories.current(c.state,"preferred_address") ?: c.memories.current(c.state,"name"))?.value?.let {
      require(reply.contains(it,ignoreCase=true)) { "Reply did not answer the user's preferred address" }
    }
    return reply
  }
}
