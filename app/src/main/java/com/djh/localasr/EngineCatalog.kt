package com.djh.localasr

import com.djh.localasr.core.AsrEngineProvider
import com.djh.localasr.parakeet.ParakeetProvider
import com.djh.localasr.paraformer.ParaformerProvider
import com.djh.localasr.sensevoice.SenseVoiceProvider
import com.djh.localasr.streaming.StreamingProvider
import com.djh.localasr.whisper.WhisperProvider

object EngineCatalog {
    val providers: List<AsrEngineProvider> = listOf(
        WhisperProvider,
        ParaformerProvider,
        SenseVoiceProvider,
        ParakeetProvider,
        StreamingProvider,
    )
}
