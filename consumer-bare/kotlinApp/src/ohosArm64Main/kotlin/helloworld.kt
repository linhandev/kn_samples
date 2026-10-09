@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

@CName("kn_helloworld")
fun helloworld(): String = "bizAvTranscoder()=0x${bizAvTranscoder().toString(16)}"
