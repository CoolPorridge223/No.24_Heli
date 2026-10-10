package org.DJB.no_24_heli.client;

import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.DJB.no_24_heli.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * 多人客户端只播放服务器目录对应的完整MP3，不以自己的同名槽位或资源包替代。
 * 按SHA-256存储缓存，换服或服务器换歌不会串曲；未校验的.part文件永不交给解码器。
 */
public final class ServerRadioCache {
    private static RadioCatalogPayload catalog;
    /** 0未请求、1核对旧缓存、2等候传输、3可播放、4暂时失败；最多八个任务。 */
    private static final int[] state=new int[RadioFiles.TRACKS];
    private static final long[] retryAt=new long[RadioFiles.TRACKS];
    private static final Map<Integer,Path> ready=new ConcurrentHashMap<>();
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"No24-radio-cache");t.setDaemon(true);return t;});
    private static int generation,track=-1,offset;
    private static long deadline;
    private static OutputStream output;
    private static MessageDigest digest;
    private static Path partial;
    private ServerRadioCache() {}

    public static void register(){
        // INIT早于播放阶段数据包；不能在JOIN重置，否则可能抹掉服务器刚发来的目录。
        ClientPlayConnectionEvents.INIT.register((handler,client)->reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
        ClientPlayNetworking.registerGlobalReceiver(RadioCatalogPayload.ID,(p,c)->c.client().execute(()->{
            if(c.client().getServer()==null){reset();catalog=p;}
        }));
        ClientPlayNetworking.registerGlobalReceiver(RadioDataPayload.ID,(p,c)->c.client().execute(()->receive(p)));
    }
    /** 连接代数使异步摘要检查的旧回调失效；完成缓存可复用，未完成分片立即关闭删除。 */
    private static void reset(){generation++;discardPartial();catalog=null;ready.clear();Arrays.fill(state,0);Arrays.fill(retryAt,0);}
    public static boolean available(int slot){return catalog!=null&&slot>=0&&slot<RadioFiles.TRACKS&&catalog.tracks().get(slot).size()>0;}
    private static Path cacheFile(RadioCatalogPayload.Track entry){
        return FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_radio_cache").resolve(entry.hash()+".mp3");
    }
    /** 只有正在播放且在听取范围内的槽位才调用本方法，因此不在登录时下载全部八首歌。 */
    public static boolean prepare(int slot){
        if(!available(slot))return false;
        if(state[slot]==3)return true;
        if(state[slot]==4&&System.nanoTime()>=retryAt[slot])state[slot]=0;
        if(state[slot]==0){
            state[slot]=1;int epoch=generation;var entry=catalog.tracks().get(slot);Path file=cacheFile(entry);
            IO.execute(()->{
                boolean valid=false;
                try{valid=Files.isRegularFile(file)&&Files.size(file)==entry.size()&&RadioFiles.hash(file).equals(entry.hash());}
                catch(IOException e){No_24_heli.LOGGER.debug("电台缓存需重新下载: {}",file);}
                boolean found=valid;
                MinecraftClient.getInstance().execute(()->{
                    if(epoch!=generation)return;
                    if(found){ready.put(slot,file);state[slot]=3;}else state[slot]=2;
                });
            });
        }
        return false;
    }
    public static InputStream open(int slot)throws IOException{
        Path file=ready.get(slot);if(file==null)throw new FileNotFoundException("服务器电台歌曲尚未下载完成");return Files.newInputStream(file);
    }
    /** 下载串行进行，每收到一包再请求下一包；15秒无响应释放文件，10秒后可重新尝试。 */
    public static void tick(){
        if(catalog==null)return;
        if(track>=0){if(System.nanoTime()>deadline)fail();return;}
        for(int slot=0;slot<RadioFiles.TRACKS;slot++)if(state[slot]==2){
            track=slot;offset=0;
            try{
                Path file=cacheFile(catalog.tracks().get(slot));Files.createDirectories(file.getParent());
                partial=Files.createTempFile(file.getParent(),"radio-",".part");output=new BufferedOutputStream(Files.newOutputStream(partial));digest=RadioFiles.digest();
                request();message("downloading",slot+1);
            }catch(IOException e){No_24_heli.LOGGER.warn("无法建立电台下载缓存",e);fail();}
            break;
        }
    }
    private static void request(){
        deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        if(!ClientPlayNetworking.canSend(RadioRequestPayload.ID)){fail();return;}
        ClientPlayNetworking.send(new RadioRequestPayload(track,offset));
    }
    /** 严格按请求的槽位和偏移接收，最后一包校验长度及内容摘要；失败不覆盖原完整缓存。 */
    private static void receive(RadioDataPayload p){
        if(track<0||p.track()!=track||p.offset()!=offset||catalog==null)return;
        var entry=catalog.tracks().get(track);int expected=Math.min(RadioFiles.CHUNK_BYTES,entry.size()-offset);
        if(p.bytes().length!=expected||expected<=0){fail();return;}
        try{
            output.write(p.bytes());digest.update(p.bytes());offset+=p.bytes().length;
            if(offset<entry.size()){request();return;}
            output.close();output=null;
            if(!HexFormat.of().formatHex(digest.digest()).equals(entry.hash()))throw new IOException("服务器歌曲SHA-256校验失败");
            Path file=cacheFile(entry);Files.move(partial,file,StandardCopyOption.REPLACE_EXISTING);partial=null;
            ready.put(track,file);state[track]=3;track=-1;offset=0;
        }catch(IOException e){No_24_heli.LOGGER.warn("电台下载失败",e);fail();}
    }
    private static void fail(){
        if(track>=0){state[track]=4;retryAt[track]=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);message("download_failed",track+1);}
        discardPartial();
    }
    private static void discardPartial(){
        try{if(output!=null)output.close();}catch(IOException ignored){}
        try{if(partial!=null)Files.deleteIfExists(partial);}catch(IOException e){No_24_heli.LOGGER.debug("未完成电台缓存待清理",e);}
        output=null;partial=null;digest=null;track=-1;offset=0;
    }
    /** 只有乘员显示进度提示，避免路过的外放听者反复收到状态栏文字。 */
    private static void message(String key,int slot){
        var player=MinecraftClient.getInstance().player;
        if(player!=null&&player.getVehicle() instanceof HelicopterEntity)player.sendMessage(Text.translatable("radio.no_24_heli."+key,slot),true);
    }
}
