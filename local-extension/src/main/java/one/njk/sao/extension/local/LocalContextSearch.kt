package one.njk.sao.extension.local

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.njk.sao.source.*
import org.json.JSONObject
import java.util.PriorityQueue

/** Exact cosine ranking, bounded memory and stable score/ID cursors. SAO controls page requests. */
class LocalContextSearch(private val store: LocalStore,private val text: ClipTextSession) {
    private data class Match(val id: Long,val signature: String,val score: Double)
    private val order=compareByDescending<Match> { it.score }.thenBy { it.id }
    suspend fun page(library: Library, source: String, query: MediaQuery, cursor: String?): MediaPage {
        val embedding=text.encode(query.text,ClipCatalog.get(library.contextModel))
        val key=digest(SourceWire.query(query).toString()+library.revision+library.contextModel)
        val boundary=cursor?.let {
            try {
                val value=JSONObject(String(android.util.Base64.decode(it,android.util.Base64.URL_SAFE),Charsets.UTF_8))
                require(value.getString("key")==key)
                val score=value.getDouble("score"); val id=value.getLong("id")
                require(score.isFinite() && score in -1.01..1.01 && id>0)
                Match(id,"",score)
            } catch(_: Exception) { throw ExtensionFailure("INVALID_RESPONSE") }
        }
        val matches=PriorityQueue(61,order.reversed())
        val job=currentCoroutineContext()
        val until=store.lastAssetId(library.id)
        var after=0L
        VectorMath.Scorer(embedding).use { scorer -> while(true) {
            job.ensureActive()
            var processed=0
            store.vectors(library.id,query,after,until,library.contextModel).use { rows ->
            while(rows.moveToNext()) {
                if(processed++%64==0) job.ensureActive()
                val id=rows.getLong(0); after=id
                val bytes=rows.getBlob(2); if(bytes.size!=512*4) continue
                val score=scorer.score(bytes)
                if(!score.isFinite()) continue
                if(boundary!=null && (score>boundary.score || score==boundary.score && id<=boundary.id)) continue
                val worst=matches.peek()
                if(matches.size<61 || score>worst.score || score==worst.score && id<worst.id) {
                    if(matches.size==61) matches.poll()
                    matches.add(Match(id,rows.getString(1),score))
                }
            }
            }
            if(processed<256) break
        } }
        job.ensureActive()
        val ranked=matches.sortedWith(order)
        val catalog=LocalCatalog(store,library,source)
        val items=ranked.take(60).mapNotNull { match ->
            try { store.asset(match.id,library.id).takeIf { it.signature==match.signature }?.let(catalog::item) }
            catch(error: ExtensionFailure) { if(error.code=="HTTP_404") null else throw error }
        }
        val next=ranked.take(60).lastOrNull()?.takeIf { ranked.size>60 }?.let {
            android.util.Base64.encodeToString(JSONObject().put("key",key).put("id",it.id).put("score",it.score).toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
        return MediaPage(items,next)
    }
}
