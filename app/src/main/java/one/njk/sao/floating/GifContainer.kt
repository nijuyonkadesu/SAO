package one.njk.sao.floating

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Validate the output and use the video's end timestamp for the final GIF delay.
 * Gifski infers that delay from the previous frame interval; VFR videos can end differently. */
internal object GifContainer {
    fun finish(file: File,durationUs: Long,width: Int,height: Int) {
        RandomAccessFile(file,"rw").use { gif ->
            fun u16()=gif.readUnsignedByte() or (gif.readUnsignedByte() shl 8)
            fun skip(bytes: Long) {
                val end=gif.filePointer+bytes
                if(bytes<0 || end>gif.length()) throw IOException("Truncated GIF")
                gif.seek(end)
            }
            fun blocks() { while(true) { val size=gif.readUnsignedByte(); if(size==0) break; skip(size.toLong()) } }
            val header=ByteArray(6).also(gif::readFully).toString(Charsets.US_ASCII)
            if(header!="GIF89a" && header!="GIF87a") throw IOException("Invalid GIF")
            if(u16()!=width || u16()!=height) throw IOException("GIF dimensions changed")
            val global=gif.readUnsignedByte(); skip(2)
            if(global and 128!=0) skip(3L shl ((global and 7)+1))
            var pendingDelay=-1L; var lastDelay=-1L; var ticks=0L; var lastTicks=0
            var frames=0
            loop@ while(true) {
                when(gif.readUnsignedByte()) {
                    0x21 -> if(gif.readUnsignedByte()==0xf9) {
                        if(gif.readUnsignedByte()!=4) throw IOException("Invalid GIF control")
                        skip(1); pendingDelay=gif.filePointer; u16(); skip(1)
                        if(gif.readUnsignedByte()!=0) throw IOException("Invalid GIF control terminator")
                    } else blocks()
                    0x2c -> {
                        skip(8); val local=gif.readUnsignedByte()
                        if(local and 128!=0) skip(3L shl ((local and 7)+1))
                        skip(1); blocks(); frames++
                        if(pendingDelay<0) throw IOException("Missing GIF timing")
                        val end=gif.filePointer; gif.seek(pendingDelay)
                        lastTicks=u16(); ticks+=lastTicks; lastDelay=pendingDelay; pendingDelay=-1
                        gif.seek(end)
                    }
                    0x3b -> break@loop
                    else -> throw IOException("Invalid GIF block")
                }
            }
            if(frames==0 || lastDelay<0 || gif.filePointer!=gif.length()) throw IOException("Invalid GIF trailer")
            val finalTicks=(durationUs+5_000)/10_000-(ticks-lastTicks)
            if(finalTicks !in 1..65535) throw IOException("Video timing cannot be represented as GIF")
            gif.seek(lastDelay); gif.writeByte(finalTicks.toInt() and 255); gif.writeByte(finalTicks.toInt() ushr 8)
        }
    }
}
