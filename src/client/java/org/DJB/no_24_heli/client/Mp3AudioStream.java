package org.DJB.no_24_heli.client;

import javazoom.jl.decoder.*;
import net.minecraft.client.sound.AudioStream;
import javax.sound.sampled.AudioFormat;
import java.io.*;
import java.nio.*;

/**
 * 将MP3逐帧解码为Minecraft/OpenAL可接受的16位小端PCM流。
 * 不一次性把整首歌展开到内存：内存只保留一帧PCM和声音引擎请求的缓冲区。
 * 每个播放实例拥有自己的解码器，多个飞机同时外放不会争用同一个读取位置。
 */
public final class Mp3AudioStream implements AudioStream {
    @FunctionalInterface public interface Source { InputStream open() throws IOException; }
    private final Source source;
    private final boolean looping;
    private Bitstream input;
    private Decoder decoder;
    private AudioFormat format;
    private ByteBuffer pending=ByteBuffer.allocate(0);
    private boolean closed;

    /** 以服务器开始时间换算的偏移接入；超过一首长度时取余，后来靠近的玩家听到同一进度。 */
    public Mp3AudioStream(Source source,boolean looping,long offsetMillis)throws IOException{
        this.source=source;this.looping=looping;reopen();
        try{
        if(!frame()){close();throw new IOException("MP3没有可解码音频帧");}
        long skip=(long)(Math.max(0,offsetMillis)*format.getFrameRate()/1000)*format.getFrameSize();
        long traversed=0;
            while(skip>0){
                int n=(int)Math.min(skip,pending.remaining());pending.position(pending.position()+n);skip-=n;traversed+=n;
                if(skip>0&&!frame()){
                    if(!looping||traversed==0)break;
                    skip%=traversed;reopen();if(!frame())break;
                }
            }
        }catch(IOException e){close();throw e;}
    }
    private void reopen()throws IOException{
        if(input!=null)try{input.close();}catch(BitstreamException e){throw new IOException(e);}
        input=new Bitstream(new BufferedInputStream(source.open()));decoder=new Decoder();pending=ByteBuffer.allocate(0);
    }
    /** JLayer输出有符号short样本；显式小端避免不同机器的本机字节序改变音高或产生噪声。 */
    private boolean frame()throws IOException{
        try{
            Header header=input.readFrame();if(header==null)return false;
            SampleBuffer samples=(SampleBuffer)decoder.decodeFrame(header,input);input.closeFrame();
            if(format==null)format=new AudioFormat(samples.getSampleFrequency(),16,samples.getChannelCount(),true,false);
            else if(samples.getSampleFrequency()!=format.getSampleRate()||samples.getChannelCount()!=format.getChannels())throw new IOException("MP3中途改变音频格式");
            pending=ByteBuffer.allocate(samples.getBufferLength()*2).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<samples.getBufferLength();i++)pending.putShort(samples.getBuffer()[i]);pending.flip();return true;
        }catch(JavaLayerException e){throw new IOException("MP3解码失败",e);}
    }
    @Override public AudioFormat getFormat(){return format;}
    @Override public ByteBuffer read(int size)throws IOException{
        if(closed)throw new IOException("音频流已关闭");
        ByteBuffer out=ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
        while(out.hasRemaining()){
            if(!pending.hasRemaining()&&!frame()){
                if(!looping)break;
                reopen();if(!frame())break;
            }
            int n=Math.min(out.remaining(),pending.remaining());int limit=pending.limit();pending.limit(pending.position()+n);out.put(pending);pending.limit(limit);
        }
        out.flip();return out;
    }
    @Override public void close()throws IOException{
        if(closed)return;closed=true;
        if(input!=null)try{input.close();}catch(BitstreamException e){throw new IOException(e);}
    }
}
