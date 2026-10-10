package org.DJB.no_24_heli.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.DJB.no_24_heli.No_24_heli;
import java.io.*;
import java.nio.file.*;

/**
 * 八个固定音乐槽位的公开接入点。优先读config/no_24_heli_radio/slot_1.mp3…slot_8.mp3，
 * 未找到时读资源包assets/no_24_heli/radio/slot_N.mp3。模组本身不内置歌曲。
 * 服务端只同步槽位/时间；联机请用相同资源包或相同本地MP3，音频不会通过网络上传。
 */
public final class RadioLibrary {
    public static final int TRACK_COUNT=8;
    public static Path directory(){return FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_radio");}
    public static void initialize(){try{Files.createDirectories(directory());}catch(IOException e){No_24_heli.LOGGER.warn("无法创建电台音乐目录",e);}}
    private static String filename(int track){if(track<0||track>=TRACK_COUNT)throw new IllegalArgumentException("Invalid radio slot");return "slot_"+(track+1)+".mp3";}
    public static boolean available(int track){
        return Files.isRegularFile(directory().resolve(filename(track)))||MinecraftClient.getInstance().getResourceManager().getResource(No_24_heli.id("radio/"+filename(track))).isPresent();
    }
    public static InputStream open(int track)throws IOException{
        Path file=directory().resolve(filename(track));
        if(Files.isRegularFile(file))return Files.newInputStream(file);
        return MinecraftClient.getInstance().getResourceManager().getResource(No_24_heli.id("radio/"+filename(track))).orElseThrow(()->new FileNotFoundException(filename(track))).getInputStream();
    }
}
