package com.jarvis.assistant.offline
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OfflineModelDownloader {
 const val MODEL_BYTES=1_282_439_328L
 private const val FILE_NAME="Qwen3-1.7B-Q4_K_M.gguf"
 private const val URL_MODEL="https://huggingface.co/lmstudio-community/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf?download=true"
 fun modelFile(context:Context)=File(File(context.applicationContext.filesDir,"offline-models"),FILE_NAME)
 private fun partFile(context:Context)=File(modelFile(context).parentFile,"$FILE_NAME.part")
 fun isModelValid(file:File):Boolean {
  if(!file.isFile||file.length()!=MODEL_BYTES)return false
  return try{FileInputStream(file).use{val b=ByteArray(4);it.read(b)==4&&b[0]=='G'.code.toByte()&&b[1]=='G'.code.toByte()&&b[2]=='U'.code.toByte()&&b[3]=='F'.code.toByte()}}catch(_:Exception){false}
 }
 fun isInternetAvailable(context:Context):Boolean {
  val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  val network=cm.activeNetwork?:return false
  val caps=cm.getNetworkCapabilities(network)?:return false
  return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
 }
 suspend fun download(context:Context,onProgress:(Long,Long)->Unit):File=withContext(Dispatchers.IO){
  val dest=modelFile(context)
  if(isModelValid(dest)){onProgress(MODEL_BYTES,MODEL_BYTES);return@withContext dest}
  dest.parentFile?.let{if(!it.exists()&&!it.mkdirs())error("Cannot create private model storage.")}
  val part=partFile(context)
  if(part.length()>MODEL_BYTES)part.delete()
  var offset=part.length()
  fun open(start:Long):HttpURLConnection {
   val c=(URL(URL_MODEL).openConnection() as HttpURLConnection).apply{
    connectTimeout=30000;readTimeout=60000;instanceFollowRedirects=true
    setRequestProperty("User-Agent","JARVIS-Android")
    if(start>0)setRequestProperty("Range","bytes=$start-")
   }
   c.connect()
   if(c.responseCode !in 200..299){val code=c.responseCode;c.disconnect();error("Model server returned HTTP $code.")}
   return c
  }
  var connection=open(offset)
  if(offset>0&&connection.responseCode!=HttpURLConnection.HTTP_PARTIAL){connection.disconnect();part.delete();offset=0;connection=open(0)}
  val len=connection.contentLengthLong
  val total=if(len>0)offset+len else MODEL_BYTES
  try{
   connection.inputStream.use{input->FileOutputStream(part,offset>0).use{output->
    val buf=ByteArray(256*1024);var count=offset;var report=count
    while(true){val n=input.read(buf);if(n<0)break;output.write(buf,0,n);count+=n
     if(count-report>=1_048_576L){onProgress(count,total.coerceAtMost(MODEL_BYTES));report=count}}
    output.fd.sync();onProgress(count,total.coerceAtMost(MODEL_BYTES))
   }}
  }finally{connection.disconnect()}
  if(part.length()!=MODEL_BYTES)error("Download incomplete (${part.length()} of $MODEL_BYTES bytes). Tap Download to resume.")
  if(!isModelValid(part)){part.delete();error("File validation failed. Download the model again.")}
  if(dest.exists()&&!dest.delete())error("Cannot replace the old model file.")
  if(!part.renameTo(dest))error("Cannot finalize the model file.")
  dest
 }
}
