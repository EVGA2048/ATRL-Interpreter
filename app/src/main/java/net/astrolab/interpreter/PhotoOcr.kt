package net.astrolab.interpreter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

internal data class PhotoRecognition(val bitmap:Bitmap,val text:String,val confidence:Int)
internal class PhotoOcr(private val context:Context){
    private val root=File(context.filesDir,"ocr-3.04")
    private fun prepare(languages:List<String>){
        val dir=File(root,"tessdata").apply{mkdirs()}
        for(lang in languages){
            val model=File(dir,"$lang.traineddata")
            if(!model.isFile||model.length()==0L){
                val part=File(dir,"$lang.tmp")
                context.assets.open("ocr/$lang.traineddata").use{input->part.outputStream().use{input.copyTo(it)}}
                check(part.renameTo(model)){"无法准备识别模型"}
            }
        }
    }
    private fun decode(uri:Uri):Bitmap {
        val resolver=context.contentResolver
        val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,options)}
        require(options.outWidth>0&&options.outHeight>0){"无法读取照片"}
        val scale=BitmapFactory.Options().apply{
            inSampleSize=1
            while(maxOf(options.outWidth,options.outHeight)/inSampleSize>2600)inSampleSize*=2
            inPreferredConfig=Bitmap.Config.ARGB_8888
        }
        val image=resolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,scale)}?:error("无法读取照片")
        val orientation=runCatching{resolver.openInputStream(uri)?.use{
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        }}.getOrNull()?:ExifInterface.ORIENTATION_NORMAL
        val matrix=Matrix()
        when(orientation){
            2->matrix.setScale(-1f,1f)
            3->matrix.setRotate(180f)
            4->matrix.setScale(1f,-1f)
            5->{matrix.setRotate(90f);matrix.postScale(-1f,1f)}
            6->matrix.setRotate(90f)
            7->{matrix.setRotate(270f);matrix.postScale(-1f,1f)}
            8->matrix.setRotate(270f)
        }
        if(matrix.isIdentity)return image
        return Bitmap.createBitmap(image,0,0,image.width,image.height,matrix,true).also{if(it!==image)image.recycle()}
    }
    @Synchronized fun recognize(uri:Uri,language:String):PhotoRecognition{
        val langs=when(language){"zh"->listOf("chi_sim","eng");"ru"->listOf("rus","eng");"en"->listOf("eng");else->listOf("rus","eng","chi_sim")}
        prepare(langs)
        val image=decode(uri)
        val api=TessBaseAPI()
        try{
            check(api.init(root.absolutePath,langs.joinToString("+"),TessBaseAPI.OEM_TESSERACT_ONLY)){"无法载入文字识别模型"}
            api.pageSegMode=TessBaseAPI.PageSegMode.PSM_AUTO
            api.setImage(image)
            val text=api.utF8Text.orEmpty().trim()
            return PhotoRecognition(image,text,api.meanConfidence())
        }catch(e:Throwable){image.recycle();throw e}
        finally{api.end()}
    }
}
