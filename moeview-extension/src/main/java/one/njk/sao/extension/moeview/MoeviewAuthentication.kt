package one.njk.sao.extension.moeview

import okhttp3.Credentials
import okhttp3.Request
import one.njk.sao.moeview.data.Board

/** Secrets/clearance are restricted to one exact direct API origin and known API paths. */
object MoeviewAuthentication {
    fun request(config: MoeviewConfig,request: Request): Request {
        val origin=java.net.URI(config.board.directBase)
        val apiPath=request.url.encodedPath in setOf("/post.json","/tag.json","/posts.json","/tags.json","/index.php")
        if(!request.url.isHttps || request.url.host!=origin.host || request.url.port!=443 || !apiPath) return request
        var result=request.newBuilder()
        if(config.clearance.isNotBlank() && config.browserAgent.isNotBlank()) result=result.header("Cookie",config.clearance).header("User-Agent",config.browserAgent)
        if(config.authenticated) {
            if(config.board==Board.DANBOORU) result=result.header("Authorization",Credentials.basic(config.login,config.apiKey))
            if(config.board==Board.GELBOORU) result=result.url(request.url.newBuilder().addQueryParameter("user_id",config.login).addQueryParameter("api_key",config.apiKey).build())
        }
        return result.build()
    }
    fun cloudflareCookies(header: String)=header.split(';').map(String::trim).filter {
        val pair=it.split('=',limit=2)
        pair.size==2 && pair[0] in setOf("cf_clearance","__cf_bm","_cfuvid") && pair[1].isNotEmpty()
    }.distinctBy { it.substringBefore('=') }.joinToString("; ")
}
