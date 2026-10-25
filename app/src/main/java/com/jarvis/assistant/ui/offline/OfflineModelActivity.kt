package com.jarvis.assistant.ui.offline

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.R
import com.jarvis.assistant.offline.OfflineLlm
import com.jarvis.assistant.offline.OfflineModelDownloader
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Locale

class OfflineModelActivity:AppCompatActivity(){
 private lateinit var status:TextView
 private lateinit var progress:ProgressBar
 private lateinit var downloadButton:Button
 private lateinit var testButton:Button
 private lateinit var prompt:EditText
 private lateinit var answer:TextView
 override fun onCreate(savedInstanceState:Bundle?){
  ThemeManager.applyTheme(this);super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(24))}
  setContentView(ScrollView(this).apply{addView(root)});ThemeManager.applyDynamicBackground(root)
  root.addView(makeText("OFFLINE JARVIS",26f,true))
  root.addView(makeText("Qwen3-1.7B · Q4_K_M",18f,true),params(12))
  root.addView(makeText("Download the model once inside JARVIS. It is stored privately in this app and is not bundled into the APK.",14f),params(8))
  root.addView(makeText("Download: about 1.28 GB · Keep at least 1.5 GB free. Inference needs extra RAM; 4 GB RAM devices may struggle.",13f),params(8))
  status=makeText("Checking model…",14f,true);root.addView(status,params(18))
  progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=1000;progress=0}
  root.addView(progress,LinearLayout.LayoutParams(-1,dp(8)).apply{topMargin=dp(10)})
  downloadButton=Button(this).apply{text="Download Qwen3 (1.28 GB)"}
  root.addView(downloadButton,params(12));downloadButton.setOnClickListener{startDownload()}
  root.addView(makeText("Test offline conversation",18f,true),params(26))
  prompt=EditText(this).apply{hint="Ask in English or Hinglish…";minLines=2;maxLines=4;gravity=Gravity.TOP}
  root.addView(prompt,params(8))
  testButton=Button(this).apply{text="Generate offline response"}
  root.addView(testButton,params(8))
  answer=makeText("Your offline response will appear here.",15f);root.addView(answer,params(12))
  testButton.setOnClickListener{runLocalTest()};refreshModelState()
 }
 private fun startDownload(){
  if(!OfflineModelDownloader.isInternetAvailable(this)){Toast.makeText(this,"Connect to the internet to download the model. After download, it can run offline.",Toast.LENGTH_LONG).show();return}
  downloadButton.isEnabled=false;status.text="Preparing private model storage…"
  lifecycleScope.launch{
   try{
    OfflineModelDownloader.download(this@OfflineModelActivity){done,total->runOnUiThread{
     progress.progress=((done*1000L)/total.coerceAtLeast(1L)).toInt().coerceIn(0,1000)
     status.text="Downloading ${formatBytes(done)} / ${formatBytes(total)}"
    }}
    status.text="Model downloaded and validated. First load may take a little while.";progress.progress=1000
    Toast.makeText(this@OfflineModelActivity,"Qwen3 model downloaded and validated.",Toast.LENGTH_LONG).show()
    answer.text="The model file is ready. Tap Generate offline response to initialize and test the native engine."
   }catch(error:Exception){status.text=error.message?:"Download failed. Try again when the connection is stable."}
   finally{downloadButton.isEnabled=true;refreshModelState()}
  }
 }
 private fun runLocalTest(){
  val input=prompt.text?.toString()?.trim().orEmpty()
  if(input.isBlank()){prompt.error="Enter a message to test the model.";return}
  if(!OfflineModelDownloader.isModelValid(OfflineModelDownloader.modelFile(this))){status.text="Download the model before testing it.";return}
  testButton.isEnabled=false
  answer.text="Loading local model…"
  status.text="Loading local model. The first response can take longer while the model initializes."
  lifecycleScope.launch{
   try{
    answer.text=withTimeout(120_000L) {
     OfflineLlm.generate(this@OfflineModelActivity,input){partial,generated,total->
      runOnUiThread{
       if(partial.isNotBlank()) answer.text=partial
       status.text=if(generated==0) "Model loaded · preparing response…" else "Generating offline response · $generated/$total tokens"
      }
     }
    }
    status.text="Offline model is ready."
   }catch(error:TimeoutCancellationException){
    answer.text="Offline generation exceeded 2 minutes. The engine may be stuck initializing or this device may not have enough available RAM. Close other apps and try again."
    status.text="Offline model timed out after 2 minutes."
   }catch(error:Exception){
    answer.text="The local model could not respond.\n${error.message?:"Unknown error"}"
    status.text="Model initialization failed. Check available RAM and model integrity."
   }finally{testButton.isEnabled=true}
  }
 }
 private fun refreshModelState(){
  val file=OfflineModelDownloader.modelFile(this)
  if(OfflineModelDownloader.isModelValid(file)){status.text="Model downloaded · ${formatBytes(file.length())} · ready to load";progress.progress=1000;downloadButton.text="Model downloaded"}
  else{
   val partial=java.io.File(file.parentFile,file.name+".part")
   if(partial.exists()&&partial.length()>0){status.text="Partial download saved · ${formatBytes(partial.length())} · tap Download to resume";progress.progress=((partial.length()*1000L)/OfflineModelDownloader.MODEL_BYTES).toInt().coerceIn(0,1000)}
   else status.text="Model not downloaded yet"
  }
 }
 private fun makeText(value:String,size:Float,bold:Boolean=false)=TextView(this).apply{
  text=value;textSize=size;setTextColor(resolveThemeColor(R.attr.themeTextPrimary,android.graphics.Color.WHITE))
  if(bold)typeface=Typeface.DEFAULT_BOLD
  setLineSpacing(dp(2).toFloat(),1f)
 }
 private fun params(top:Int=0)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT).apply{topMargin=dp(top)}
 private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
 private fun resolveThemeColor(attr:Int,fallback:Int):Int{
  val value=android.util.TypedValue();if(!theme.resolveAttribute(attr,value,true))return fallback
  return if(value.resourceId!=0)androidx.core.content.ContextCompat.getColor(this,value.resourceId) else value.data
 }
 private fun formatBytes(bytes:Long)=String.format(Locale.US,"%.2f GB",bytes/(1024.0*1024.0*1024.0))
}
