package com.hikovo.mobilepet

/** GBNF binds known identity facts while llama.cpp still samples the wording. No reply lookup table. */
object GroundedIdentityConstraint {
  private fun literal(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"")
    .replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\""

  fun build(c:WriterContext):String? {
    val targets=DialoguePerspective.targets(c.input)
    val name=c.memories.current(c.state,"name")
    val address=c.memories.current(c.state,"preferred_address")
    val user=if("user_address" in targets) (address ?: name)?.value else name?.value
    val bodies=mutableListOf<String>()
    val rules=mutableListOf<String>()
    if("user_name" in targets){
      if(user==null)return null
      rules+="user-name ::= (\"你叫\" | \"你的名字是\") ${literal(user)}"
      bodies+="user-name"
    }
    if("assistant_name" in targets){
      rules+="self-name ::= (\"我叫\" | \"我的名字是\") (\"小苏\" | \"三好兔\")"
      bodies+="self-name"
    }
    if("user_address" in targets){
      if(user==null)return null
      rules+="user-address ::= (\"我叫你\" | \"叫你\") ${literal(user)}"
      bodies+="user-address"
    }
    if(bodies.isEmpty()){
      val updated=if(address?.sourceMessageIds?.contains(c.messageId)==true)address
        else name?.takeIf {it.sourceMessageIds.contains(c.messageId)}
      if(updated==null)return null
      rules+="ack ::= ${literal(updated.value)} (\"，记住啦\" | \"，好呀\")"
      bodies+="ack"
    }
    val body=if(bodies.size==1)bodies.single() else "(${bodies.joinToString(" \"，\" ")} | ${bodies.asReversed().joinToString(" \"，\" ")})"
    return (listOf("root ::= (\"嗯，\" | \"记得呀，\" | \"\") $body \"。\"")+rules).joinToString("\n")
  }
}
