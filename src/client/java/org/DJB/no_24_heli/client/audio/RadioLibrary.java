package org.DJB.no_24_heli.client.audio;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.client.network.ServerRadioCache;
import org.DJB.no_24_heli.data.RadioFiles;


import net.minecraft.client.MinecraftClient;
import java.io.*;
import java.nio.file.*;

/**
 * 八槽音乐的统一入口。单人读取本机config，多人读取服务器目录及其经过校验的下载缓存。
 * LAN房主的整合服务器与本机共用config，LAN来宾则和专用服务器玩家一样从网络取曲。
 * 不再回退到资源包或远程玩家自己的同名文件，保证同一服务器听到同一组音乐。
 */
public final class RadioLibrary {
    public static final int TRACK_COUNT=RadioFiles.TRACKS;
    public static Path directory(){return RadioFiles.directory();}
    public static void initialize(){try{Files.createDirectories(directory());}catch(IOException e){No_24_heli.LOGGER.warn("无法创建电台音乐目录",e);}}
    /** 是否有本机整合服务器是实际连接类型，不依据服务器地址或菜单来源猜测单/多人。 */
    private static boolean local(){return MinecraftClient.getInstance().getServer()!=null;}
    public static boolean available(int track){
        return local()?Files.isRegularFile(RadioFiles.file(track)):ServerRadioCache.available(track);
    }
    public static boolean prepare(int track){return local()?available(track):ServerRadioCache.prepare(track);}
    public static InputStream open(int track)throws IOException{
        return local()?Files.newInputStream(RadioFiles.file(track)):ServerRadioCache.open(track);
    }
}
