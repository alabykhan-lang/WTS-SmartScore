package com.wts.smartscore.ai
import com.wts.smartscore.model.AiMarkProposal

data class MarkingRequest(val scriptId:String,val subject:String,val maximumMarks:Double,val questionPaperPaths:List<String>,val markingSchemePaths:List<String>,val scriptPagePaths:List<String>,val context:String?)
interface AiMarkerProvider { val id:String; suspend fun mark(request:MarkingRequest):AiMarkProposal }
class NoAiProvider:AiMarkerProvider{ override val id="none"; override suspend fun mark(request:MarkingRequest):AiMarkProposal=throw IllegalStateException("No AI provider configured. Script scanning remains available.") }
class AiMarkerService(private val provider:AiMarkerProvider){ suspend fun propose(r:MarkingRequest)=provider.mark(r).copy(reviewRequired=true) }
