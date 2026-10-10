package org.DJB.no_24_heli;

import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 多人电台的文件源只在服务器config。启动时后台建立内容目录，听者按需拉取小分片。
 * 世界tick仅处理权限与发包；计算摘要及磁盘读取均在单独IO线程，避免歌曲拖慢飞行物理。
 */
public final class RadioTransferServer {
    private static RadioTransferServer active;
    private final MinecraftServer server;
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"No24-radio-server");t.setDaemon(true);return t;});
    private RadioCatalogPayload catalog;
    private final long[] modified=new long[RadioFiles.TRACKS];
    private final Set<UUID> notified=new HashSet<>(),busy=new HashSet<>();
    private final Map<UUID,RadioRequestPayload> pending=new HashMap<>();
    private RadioTransferServer(MinecraftServer server){this.server=server;}

    public static void register(){
        PayloadTypeRegistry.playS2C().register(RadioCatalogPayload.ID,RadioCatalogPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RadioDataPayload.ID,RadioDataPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RadioRequestPayload.ID,RadioRequestPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server->{active=new RadioTransferServer(server);active.load();});
        ServerLifecycleEvents.SERVER_STOPPED.register(server->{if(active!=null){active.io.shutdownNow();active=null;}});
        ServerTickEvents.END_SERVER_TICK.register(server->{if(active!=null)active.tick();});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server)->{
            if(active!=null){UUID id=handler.player.getUuid();active.notified.remove(id);active.pending.remove(id);active.busy.remove(id);}
        });
        ServerPlayNetworking.registerGlobalReceiver(RadioRequestPayload.ID,(p,c)->c.server().execute(()->{
            if(active!=null){UUID id=c.player().getUuid();if(!active.busy.contains(id))active.pending.putIfAbsent(id,p);}
        }));
    }
    /** 建目录与哈希时拒绝空文件及超限文件；管理员更换歌曲后重启服务器/世界重新建目录。 */
    private void load(){io.execute(()->{
        List<RadioCatalogPayload.Track> tracks=new ArrayList<>();
        try{Files.createDirectories(RadioFiles.directory());}catch(IOException e){No_24_heli.LOGGER.warn("无法创建服务器电台目录",e);}
        for(int slot=0;slot<RadioFiles.TRACKS;slot++){
            var entry=new RadioCatalogPayload.Track(0,"");Path file=RadioFiles.file(slot);
            try{
                if(Files.isRegularFile(file)){
                    long size=Files.size(file);if(size<=0||size>RadioFiles.MAX_BYTES)throw new IOException("单曲须大于0且不超过32MiB");
                    long stamp=Files.getLastModifiedTime(file).toMillis();String hash=RadioFiles.hash(file);
                    if(size!=Files.size(file)||stamp!=Files.getLastModifiedTime(file).toMillis())throw new IOException("建立目录时歌曲发生变化");
                    modified[slot]=stamp;entry=new RadioCatalogPayload.Track((int)size,hash);
                }
            }catch(IOException e){No_24_heli.LOGGER.warn("服务器电台槽位 {} 不可用",slot+1,e);}
            tracks.add(entry);
        }
        server.execute(()->{if(active==this)catalog=new RadioCatalogPayload(tracks);});
    });}
    private boolean canListen(ServerPlayerEntity player,int slot){
        // 未乘机的附近玩家也要听见外放，因此许可按正在播放的飞机与64格范围判定。
        return !player.getServerWorld().getEntitiesByClass(HelicopterEntity.class,player.getBoundingBox().expand(64),
                h->!h.flight.wreck&&h.flight.radioTrack==slot&&h.squaredDistanceTo(player)<64*64).isEmpty();
    }
    /** 每名听者每tick最多分派一个24KiB请求；网络线程不能直接读取文件或堆积无界任务。 */
    private void tick(){
        if(catalog==null)return;
        for(ServerPlayerEntity p:server.getPlayerManager().getPlayerList()){
            UUID id=p.getUuid();
            if(!notified.contains(id)&&ServerPlayNetworking.canSend(p,RadioCatalogPayload.ID)){
                ServerPlayNetworking.send(p,catalog);notified.add(id);
            }
            RadioRequestPayload request=pending.remove(id);if(request==null||busy.contains(id))continue;
            int slot=request.track(),offset=request.offset();
            if(slot<0||slot>=RadioFiles.TRACKS||offset<0||offset%RadioFiles.CHUNK_BYTES!=0
                    ||offset>=catalog.tracks().get(slot).size()||!canListen(p,slot)){
                ServerPlayNetworking.send(p,new RadioDataPayload(slot,offset,new byte[0]));continue;
            }
            busy.add(id);int length=Math.min(RadioFiles.CHUNK_BYTES,catalog.tracks().get(slot).size()-offset);
            io.execute(()->{
                byte[] bytes=new byte[length];
                try{
                    Path file=RadioFiles.file(slot);
                    if(Files.size(file)!=catalog.tracks().get(slot).size()||Files.getLastModifiedTime(file).toMillis()!=modified[slot])throw new IOException("歌曲已改变，请重启服务器刷新目录");
                    try(RandomAccessFile in=new RandomAccessFile(file.toFile(),"r")){in.seek(offset);in.readFully(bytes);}
                }catch(IOException e){bytes=new byte[0];No_24_heli.LOGGER.warn("读取电台槽位 {} 失败: {}",slot+1,e.toString());}
                byte[] result=bytes;
                server.execute(()->{
                    if(active!=this)return;busy.remove(id);
                    if(server.getPlayerManager().getPlayer(id)==p)ServerPlayNetworking.send(p,new RadioDataPayload(slot,offset,result));
                });
            });
        }
    }
}
