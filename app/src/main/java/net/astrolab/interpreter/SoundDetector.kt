package net.astrolab.interpreter

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.*

/** Independent acoustic event classifier; called only from the recognition worker. */
internal class SoundDetector(private val assets:AssetManager) {
    private var model:AudioTagging?=null
    private var unavailable=false
    fun classify(samples:FloatArray):String?{
        if(unavailable||samples.size<8000)return null
        return runCatching {
            val tagger=model?:AudioTagging(assets,AudioTaggingConfig(
                model=AudioTaggingModelConfig(ced="asr/ced-tiny.int8.onnx",numThreads=1,provider="cpu"),
                labels="asr/ced-labels.csv",topK=5
            )).also{model=it}
            val stream=tagger.createStream()
            try{
                stream.acceptWaveform(samples,16000)
                soundLabel(tagger.compute(stream,5).map{SoundScore(it.name,it.prob)})
            }finally{stream.release()}
        }.getOrElse{unavailable=true;null}
    }
}
