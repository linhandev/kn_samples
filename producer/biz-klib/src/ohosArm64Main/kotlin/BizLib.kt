@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import platform.DataAugmentationKit.AIP.AIP_OK
import platform.MediaKit.AVTranscoder.OH_AVTranscoderConfig_Create
import platform.MediaKit.AVTranscoder.OH_AVTranscoderConfig_Release

/** 验证业务 KLIB 对独立 platform leaf 的解析。 */
@CName("kn_biz_aip")
fun bizAip(): UInt = AIP_OK

@CName("kn_biz_avtranscoder")
fun bizAvTranscoder(): Long {
    val ptr = OH_AVTranscoderConfig_Create()!!
    val address = ptr.rawValue.toLong()
    OH_AVTranscoderConfig_Release(ptr)
    return address
}
