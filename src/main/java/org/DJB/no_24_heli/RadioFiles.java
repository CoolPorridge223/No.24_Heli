package org.DJB.no_24_heli;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** 单人本地与多人服务器共用固定槽位规则；网络中只传槽号，绝不接受客户端提交路径。 */
public final class RadioFiles {
    public static final int TRACKS=8;
    /** 单曲最多32MiB；每包24KiB，低于自定义负载上限，也避免整首歌曲塞进一个网络包。 */
    public static final int MAX_BYTES=32*1024*1024, CHUNK_BYTES=24*1024;
    private RadioFiles() {}
    public static Path directory(){return FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_radio");}
    public static Path file(int track){
        if(track<0||track>=TRACKS)throw new IllegalArgumentException("Invalid radio slot");
        return directory().resolve("slot_"+(track+1)+".mp3");
    }
    public static MessageDigest digest(){
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    /** 流式计算内容摘要，调用者必须放到IO线程；不把32MiB文件完整展开到服务器内存。 */
    public static String hash(Path file)throws IOException{
        MessageDigest digest=digest();int total=0;
        try(InputStream in=Files.newInputStream(file)){
            byte[] buffer=new byte[65536];int count;
            while((count=in.read(buffer))!=-1){total+=count;if(total>MAX_BYTES)throw new IOException("电台单曲超过32MiB");digest.update(buffer,0,count);}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
