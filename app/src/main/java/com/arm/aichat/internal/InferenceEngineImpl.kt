package com.arm.aichat.internal
import android.content.Context
import com.jarvis.assistant.offline.OfflineModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Serializes access to llama.cpp's process-wide native model/context. */
internal class InferenceEngineImpl private constructor(context:Context){
 private val app=context.applicationContext
 private val mutex=Mutex()
 private var initialized=false
 private var loadedPath:String?=null
 private external fun init(nativeLibDir:String)
 private external fun load(modelPath:String):Int
 private external fun prepare():Int
 private external fun systemInfo():String
 private external fun benchModel(pp:Int,tg:Int,pl:Int,nr:Int):String
 private external fun processSystemPrompt(systemPrompt:String):Int
 private external fun processUserPrompt(userPrompt:String,predictLength:Int):Int
 private external fun generateNextToken():String?
 private external fun unload()
 private external fun shutdown()
 suspend fun generate(context:Context,prompt:String):String=withContext(Dispatchers.IO){
  mutex.withLock{
   val model=OfflineModelDownloader.modelFile(context)
   check(OfflineModelDownloader.isModelValid(model)){"Offline model missing or incomplete. Download it from Offline JARVIS."}
   ensureLoaded(model)
   check(processUserPrompt(prompt,256)==0){"Local model could not process the prompt."}
   val out=StringBuilder();var n=0
   while(n<256){val token=generateNextToken()?:break;if(token.isNotEmpty())out.append(token);n++}
   out.toString().replace(Regex("<think>[\\s\\S]*?</think>"),"").replace("<|im_end|>","").replace("<|endoftext|>","").trim()
    .ifBlank{"I couldn't generate a response. Please try a shorter message."}
  }
 }
 private fun ensureLoaded(model:File){
  val path=model.absolutePath;if(loadedPath==path)return
  if(!initialized){System.loadLibrary("ai-chat");init(app.applicationInfo.nativeLibraryDir);initialized=true}
  if(loadedPath!=null){unload();loadedPath=null}
  check(load(path)==0){"llama.cpp could not load the Qwen3 model on this device."}
  check(prepare()==0){"Not enough available memory to initialize the model. Close other apps and retry."}
  check(processSystemPrompt("You are JARVIS, a helpful offline Android assistant. Reply naturally in the user's language, including Hinglish and English. Be concise and honest. You have no internet access; do not claim to know live news, weather, or current online information. Never claim to perform a phone action unless the app actually performed it.")==0){"Could not initialize the offline assistant prompt."}
  loadedPath=path
 }
 companion object{
  @Volatile private var instance:InferenceEngineImpl?=null
  fun getInstance(context:Context):InferenceEngineImpl=instance?: synchronized(this){instance?:InferenceEngineImpl(context.applicationContext).also{instance=it}}
 }
}
