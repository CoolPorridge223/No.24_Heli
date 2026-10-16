package org.DJB.no_24_heli.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
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
@Environment(EnvType.CLIENT)
public final class ExploredMap {
    private static long generation;
    private static final Set<Long> loading=new HashSet<>();
    /** 只通知实际变更的区块，地图无需因一个新瓦片重采样整个视口。 */
    private static final Set<Long> changed=new HashSet<>();
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
        if(world==next)return;world=next;pending.clear();tiles.clear();loading.clear();changed.clear();generation++;
        if(next==null){directory=null;return;}
        MinecraftClient mc=MinecraftClient.getInstance();
        String source=mc.getServer()!=null?mc.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().toString()
                :mc.getCurrentServerEntry()!=null?mc.getCurrentServerEntry().address:"local-connection";
        String key=UUID.nameUUIDFromBytes((source+"|"+next.getRegistryKey().getValue()).getBytes(StandardCharsets.UTF_8)).toString();
        directory=FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_maps").resolve(key);
    }
    /** 每 tick 在三毫秒内最多采样三十二块，优先玩家附近；读取世界仍在客户端主线程。 */
    public static void tick(){
        var mc=MinecraftClient.getInstance();selectWorld(mc.world);if(world==null)return;
        if(!(mc.currentScreen instanceof HeliMapScreen))changed.clear();
        long deadline=System.nanoTime()+3_000_000L;
        var queued=new ArrayList<>(pending.values());
        if(mc.player!=null){var p=mc.player.getChunkPos();queued.sort(Comparator.comparingLong(c->{
            long dx=(long)c.getPos().x-p.x,dz=(long)c.getPos().z-p.z;return dx*dx+dz*dz;}));}
        int count=0;
        for(var chunk:queued){
            if(count>=32||(count>0&&System.nanoTime()>=deadline))break;
            pending.remove(chunk.getPos().toLong());capture(chunk);count++;
        }
        // 每 tick 轮换附近一块，约一秒刷新周围四乘四块，避免只更新脚下而漏掉近处施工。
        if(mc.player!=null){var p=mc.player.getChunkPos();int at=(int)(world.getTime()&15);
            int x=p.x+(at&3)-1,z=p.z+(at>>2)-1;
            if(world.getChunkManager().isChunkLoaded(x,z))pending.putIfAbsent(ChunkPos.toLong(x,z),world.getChunk(x,z));}
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
        if(Arrays.equals(old,colors))return;changed.add(key);
        Path target=directory.resolve(origin.x+"_"+origin.z+".bin");
        WRITER.execute(()->{try{
            Files.createDirectories(target.getParent());Path temp=target.resolveSibling(target.getFileName()+".tmp");
            Files.write(temp,colors);Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
        }catch(java.io.IOException e){No_24_heli.LOGGER.warn("无法保存探索地图区块 {}",target,e);}});
    }
    private static void trim(){while(tiles.size()>32768)tiles.remove(tiles.keySet().iterator().next());}
    /** 地图每帧消费区块变更，集合最多与内存瓦片数同量级，关闭地图不积累逐帧记录。 */
    public static Set<Long> takeChanges(){Set<Long> result=Set.copyOf(changed);changed.clear();return result;}
    /** 未命中时异步读取 256 字节瓦片；null 表示仍需重试，空数组表示已确认的未知地形。 */
    public static byte[] tile(int chunkX,int chunkZ){
        if(directory==null)return null;
        long key=ChunkPos.toLong(chunkX,chunkZ);byte[] tile=tiles.get(key);
        if(tile==null){
            // 渲染线程只排队；负缓存与32K瓦片LRU覆盖缩小地图，最多256个在途磁盘任务。
            if(loading.size()<256&&loading.add(key)){
                Path file=directory.resolve(chunkX+"_"+chunkZ+".bin");long epoch=generation;
                WRITER.execute(()->{
                    byte[] loaded;
                    try{loaded=Files.isRegularFile(file)&&Files.size(file)==256?Files.readAllBytes(file):new byte[0];}
                    catch(java.io.IOException e){loaded=new byte[0];}
                    byte[] result=loaded;
                    MinecraftClient.getInstance().execute(()->{
                        if(epoch!=generation)return;loading.remove(key);
                        if(!tiles.containsKey(key)){tiles.put(key,result);trim();changed.add(key);}
                    });
                });
            }
            return null;
        }
        return tile;
    }
}
