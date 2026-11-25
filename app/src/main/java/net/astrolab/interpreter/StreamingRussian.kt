package net.astrolab.interpreter

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*

/** Stateful CTC preview. Accessed only from the streaming worker. */
internal class StreamingRussian(private val assets:AssetManager) {
    private var recognizer:OnlineRecognizer?=null
    private var stream:OnlineStream?=null
    fun start(){
        stream?.release();stream=null
        val r=recognizer ?: OnlineRecognizer(assets,OnlineRecognizerConfig(
            featConfig=FeatureConfig(sampleRate=8000,featureDim=80),
            modelConfig=OnlineModelConfig(toneCtc=OnlineToneCtcModelConfig(model="asr/tone-ru.onnx"),
                tokens="asr/tone-ru-tokens.txt",numThreads=2,provider="cpu"),
            enableEndpoint=false,decodingMethod="greedy_search"
        )).also{recognizer=it}
        stream=r.createStream()
    }
    fun feed(audio:FloatArray):String{
        val r=recognizer?:return ""
        val st=stream?:return ""
        val down=FloatArray(audio.size/2){(audio[it*2]+audio[it*2+1])*.5f}
        if(down.isEmpty())return ""
        st.acceptWaveform(down,8000)
        while(r.isReady(st))r.decode(st)
        return r.getResult(st).text.trim()
    }
    fun close(){stream?.release();stream=null;recognizer?.release();recognizer=null}
}
