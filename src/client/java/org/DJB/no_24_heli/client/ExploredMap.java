package org.DJB.no_24_heli.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.*;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import org.DJB.no_24_heli.No_24_heli;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * 客户端探索地图：每个区块保存16×16原版地图色字节，不持有卸载后的世界/实体引用。
 * 存档路径按服务器或单人存档、维度隔离；切换服务器不会把旧世界地形画到新世界。
 * 只记录客户端真正收到的区块，不为填满地图强制加载远处世界，也不暴露未探索地形。
 */
public final class ExploredMap {
    private static ClientWorld world;
    private static Path directory;
    private static final Map<Long,WorldChunk> pending=new LinkedHashMap<>();
    private static final Map<Long,byte[]> tiles=new LinkedHashMap<>(256,.75f,true);
    private static final java.util.concurrent.ExecutorService WRITER=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"No24-map-save");t.setDaemon(true);return t;});
    private ExploredMap() {}

    public static void register(){
        ClientChunkEvents.CHUNK_LOAD.register((w,chunk)->{selectWorld(w);pending.put(chunk.getPos().toLong(),chunk);});
        // 卸载事件是最后一次读取地形的机会，优先采样，避免高速飞行把队列中的区块丢掉。
        ClientChunkEvents.CHUNK_UNLOAD.register((w,chunk)->{selectWorld(w);pending.remove(chunk.getPos().toLong());capture(chunk);});
    }
    private static void selectWorld(ClientWorld next){
        if(world==next)return;world=next;pending.clear();tiles.clear();
        if(next==null){directory=null;return;}
        MinecraftClient mc=MinecraftClient.getInstance();
        String source=mc.getServer()!=null?mc.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().toString()
                :mc.getCurrentServerEntry()!=null?mc.getCurrentServerEntry().address:"local-connection";
        String key=UUID.nameUUIDFromBytes((source+"|"+next.getRegistryKey().getValue()).getBytes(StandardCharsets.UTF_8)).toString();
        directory=FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_maps").resolve(key);
    }
    /** 每tick最多采样4个已接收区块，平摊登录瞬间的地形读取工作。落盘只传不可变副本。 */
    public static void tick(){
        var mc=MinecraftClient.getInstance();selectWorld(mc.world);if(world==null)return;
        for(int i=0;i<4&&!pending.isEmpty();i++){
            var first=pending.entrySet().iterator();var entry=first.next();first.remove();capture(entry.getValue());
        }
        // 附近地形改动也需要刷新；每秒仅重新采样脚下区块，不做全世界重复扫描。
        if(mc.player!=null&&world.getTime()%20==0){var p=mc.player.getChunkPos();if(world.getChunkManager().isChunkLoaded(p.x,p.z))capture(world.getChunk(p.x,p.z));}
    }
    private static void capture(WorldChunk chunk){
        if(directory==null)return;byte[] colors=new byte[256];var origin=chunk.getPos();
        for(int z=0;z<16;z++)for(int x=0;x<16;x++){
            int wx=origin.getStartX()+x,wz=origin.getStartZ()+z;
            int y=chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE,x,z);
            BlockPos pos=new BlockPos(wx,y,wz);var state=chunk.getBlockState(pos);
            while(y>world.getBottomY()&&state.getMapColor(world,pos)==MapColor.CLEAR){pos=new BlockPos(wx,--y,wz);state=chunk.getBlockState(pos);}
            int previous=chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE,x,Math.max(0,z-1));
            MapColor.Brightness shade=y>previous?MapColor.Brightness.HIGH:y<previous?MapColor.Brightness.LOW:MapColor.Brightness.NORMAL;
            colors[z*16+x]=state.getMapColor(world,pos).getRenderColorByte(shade);
        }
        long key=origin.toLong();byte[] old=tiles.put(key,colors);trim();
        if(Arrays.equals(old,colors))return;
        Path target=directory.resolve(origin.x+"_"+origin.z+".bin");
        WRITER.execute(()->{try{
            Files.createDirectories(target.getParent());Path temp=target.resolveSibling(target.getFileName()+".tmp");
            Files.write(temp,colors);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
        }catch(java.io.IOException e){No_24_heli.LOGGER.warn("无法保存探索地图区块 {}",target,e);}});
    }
    private static void trim(){while(tiles.size()>4096)tiles.remove(tiles.keySet().iterator().next());}
    /** 未在内存中的旧区块按需从256字节磁盘瓦片读取；空瓦片也缓存，避免逐像素重复查文件。 */
    public static int color(int x,int z){
        if(directory==null)return 0xffd7c28e;
        long key=ChunkPos.toLong(x>>4,z>>4);byte[] tile=tiles.get(key);
        if(tile==null){
            Path file=directory.resolve((x>>4)+"_"+(z>>4)+".bin");
            try{tile=Files.exists(file)&&Files.size(file)==256?Files.readAllBytes(file):new byte[0];}
            catch(java.io.IOException e){tile=new byte[0];}
            tiles.put(key,tile);trim();
        }
        if(tile.length!=256)return ((x+z)&8)==0?0xffd7c28e:0xffd4be89;
        int abgr=MapColor.getRenderColor(Byte.toUnsignedInt(tile[(z&15)*16+(x&15)]));
        return (abgr&0xff00ff00)|((abgr>>>16)&255)|((abgr&255)<<16);
    }
}
