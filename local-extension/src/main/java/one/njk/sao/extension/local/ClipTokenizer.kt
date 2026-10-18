package one.njk.sao.extension.local

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.Locale

/** CLIP byte-level BPE from the pinned Hugging Face tokenizer, including its Unicode normalization. */
class ClipTokenizer(json: String) {
    constructor(file: File): this(file.readText())
    private val vocabulary: Map<String,Int>
    private val ranks: Map<String,Int>
    private val bytes=Array(256) { "" }
    private val whitespace=Regex("[\\p{Z}\\x09-\\x0D\\x1C-\\x1F\\x85]+")
    private val words=Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+")
    init {
        val model=JSONObject(json).getJSONObject("model")
        val vocab=model.getJSONObject("vocab")
        vocabulary=vocab.keys().asSequence().associateWith(vocab::getInt)
        val merges=model.getJSONArray("merges")
        ranks=(0 until merges.length()).associate { index ->
            val pair=merges.get(index)
            (if(pair is org.json.JSONArray) "${pair.getString(0)} ${pair.getString(1)}" else pair.toString()) to index
        }
        val printable=(33..126).toList()+(161..172).toList()+(174..255).toList()
        var extra=256
        for(value in 0..255) bytes[value]=(if(value in printable) value else extra++).toChar().toString()
    }
    fun encode(text: String): IntArray {
        val clean=Normalizer.normalize(text,Normalizer.Form.NFC).replace(whitespace," ").trim().lowercase(Locale.ROOT)
        val result=ArrayList<Int>(77); result+=49406
        for(match in words.findAll(clean)) {
            if(result.size>=76) break
            val word=match.value
            val tokens=if(word=="<|startoftext|>" || word=="<|endoftext|>") listOf(word) else bpe(word)
            for(token in tokens) { if(result.size>=76) break; result+=vocabulary[token] ?: 49407 }
        }
        result+=49407
        return IntArray(77) { result.getOrElse(it) { 49407 } }
    }
    private fun bpe(word: String): List<String> {
        val parts=word.toByteArray(Charsets.UTF_8).map { bytes[it.toInt() and 255] }.toMutableList()
        if(parts.isEmpty()) return emptyList()
        parts[parts.lastIndex]+="</w>"
        while(parts.size>1) {
            val index=(0 until parts.lastIndex).minByOrNull { ranks["${parts[it]} ${parts[it+1]}"] ?: Int.MAX_VALUE } ?: break
            val key="${parts[index]} ${parts[index+1]}"
            if(key !in ranks) break
            val left=parts[index]; val right=parts[index+1]
            var position=0
            while(position<parts.lastIndex) {
                if(parts[position]==left && parts[position+1]==right) { parts[position]+=parts.removeAt(position+1) }
                position++
            }
        }
        return parts
    }
}
