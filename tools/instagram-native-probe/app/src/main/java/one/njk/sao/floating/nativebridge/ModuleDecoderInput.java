package one.njk.sao.floating.nativebridge;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import one.njk.sao.media.DecoderInput;

/** Loaded beside the minified module so its interface belongs to that exact loader. */
public final class ModuleDecoderInput implements DecoderInput {
    private final RandomAccessFile file;
    private final byte[] buffer=new byte[64*1024];
    public ModuleDecoderInput(File input) throws IOException { file=new RandomAccessFile(input,"r"); }
    @Override public int read(ByteBuffer destination) throws IOException {
        int count=file.read(buffer,0,Math.min(buffer.length,destination.remaining()));
        if(count>0) destination.put(buffer,0,count);
        return count;
    }
    @Override public long seek(long offset,int whence) throws IOException {
        if(whence==65536) return file.length();
        long target=switch(whence) { case 0 -> offset; case 1 -> file.getFilePointer()+offset; case 2 -> file.length()+offset; default -> -1; };
        if(target<0 || target>file.length()) return -1;
        file.seek(target); return target;
    }
    @Override public void close() throws IOException { file.close(); }
}
