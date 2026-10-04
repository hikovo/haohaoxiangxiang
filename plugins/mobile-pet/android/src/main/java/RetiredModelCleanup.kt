package com.hikovo.mobilepet

import android.content.Context
import java.io.File

/** Remove only the two retired downloads; never touch conversations or user media. */
object RetiredModelCleanup {
  fun run(context: Context) {
    val paths = listOf(
      "offline-model/DeepSeek-R1-Distill-Qwen-1.5B-Q3_K_M.gguf",
      "offline-model/DeepSeek-R1-Distill-Qwen-1.5B-Q3_K_M.gguf.part",
      "models/sanhao-qwen3-0.6b-int4.litertlm",
      "models/sanhao-qwen3-0.6b-int4.litertlm.part",
    )
    for (path in paths) runCatching {
      val retired = File(context.filesDir, path)
      if (retired.isFile) retired.delete()
    }
  }
}
