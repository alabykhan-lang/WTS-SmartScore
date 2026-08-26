package com.wts.smartscore.network
import okhttp3.*
import java.io.IOException

class ResultPortalReadOnlyClient(private val baseUrl:String,private val client:OkHttpClient=OkHttpClient()){
 // Intentionally read-only. No score mutation methods exist in V1.
 fun request(path:String,callback:Callback){ require(path in setOf("sessions","terms","classes","subjects","roster","assessment-config","broadsheet-batch")); val r=Request.Builder().url(baseUrl.trimEnd('/')+"/api/smartscore-read/"+path).get().build(); client.newCall(r).enqueue(callback) }
}
