package org.DJB.no_24_heli.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import java.util.BitSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 职责：客户端探索地图的分块 GPU 纹理缓存，不重采样整张视口。
 * net.minecraft.client.texture.NativeImageBackedTexture 保存地图页；DrawContext 交由 GPU 缩放。
 * 仅屏幕渲染线程创建/更新/释放；关闭屏幕销毁纹理。缺图先查异步瓦片，接缝查世界到页坐标。
 */
@Environment(EnvType.CLIENT)
public final class MapTerrainRenderer implements AutoCloseable {
    /** 每页 128 像素，最多保留 64 页约四 MiB；采样步长随缩放按二次幂变化。 */
    private static final int PAGE_PIXELS=128,MAX_PAGES=64;
    /** 每帧最多处理 1024 个区块、三毫秒；每个区块只在首次可用或内容变化时上传。 */
    private static final int FRAME_CHUNKS=1024;
    private static final long FRAME_NANOS=3_000_000L;
    /** 访问顺序缓存；一页覆盖 PAGE_PIXELS × step 个世界方块。 */
    private final Map<Long,Page> pages=new LinkedHashMap<>(16,.75f,true);
    private int step=1;
    /** 单页图片和已完成的区块位；未知区块用纸色，异步到达后独立更新。 */
    private final class Page {
        private final int worldX,worldZ,chunks;
        private final NativeImageBackedTexture texture=new NativeImageBackedTexture(PAGE_PIXELS,PAGE_PIXELS,false);
        private final Identifier id;
        private final BitSet ready;
        private int cursor;
        /** 预填纸色只执行一次；注册 GPU 纹理后逐块补充真实探索数据。 */
        private Page(int pageX,int pageZ){
            worldX=pageX*PAGE_PIXELS*step;worldZ=pageZ*PAGE_PIXELS*step;chunks=PAGE_PIXELS*step/16;
            ready=new BitSet(chunks*chunks);
            var image=texture.getImage();
            for(int z=0;z<PAGE_PIXELS;z++)for(int x=0;x<PAGE_PIXELS;x++)image.setColor(x,z,paper(worldX+x*step,worldZ+z*step));
            id=MinecraftClient.getInstance().getTextureManager().registerDynamicTexture("no24_map_page",texture);
            texture.upload();
        }
        /** 异步区块到达时仅上传所属矩形；NativeImage 采用 ABGR，原版地图色无需反复换通道。 */
        private boolean update(int chunkX,int chunkZ){
            int localX=chunkX-(worldX>>4),localZ=chunkZ-(worldZ>>4);
            if(localX<0||localZ<0||localX>=chunks||localZ>=chunks)return false;
            byte[] tile=ExploredMap.tile(chunkX,chunkZ);if(tile==null)return false;
            int pixels=16/step,x=localX*pixels,z=localZ*pixels;
            var image=texture.getImage();
            for(int dz=0;dz<pixels;dz++)for(int dx=0;dx<pixels;dx++){
                int color=tile.length==256?MapColor.getRenderColor(Byte.toUnsignedInt(tile[(dz*step)*16+dx*step]))
                        :paper(chunkX*16+dx*step,chunkZ*16+dz*step);
                image.setColor(x+dx,z+dz,color);
            }
            // 只更新这个区块的像素矩形，拖动和每帧缩放不进行 CPU 纹理重建。
            texture.bindTexture();image.upload(0,x,z,x,z,pixels,pixels,false,false,false,false);
            ready.set(localZ*chunks+localX);return true;
        }
        /** 游标循环重试尚在读取或未排入磁盘队列的区块，每帧共享工作预算。 */
        private int fill(int budget,long deadline){
            int tried=0,total=chunks*chunks;
            if(ready.nextClearBit(0)>=total)return 0;
            while(tried<budget&&tried<total&&System.nanoTime()<deadline){
                int at=cursor++%total;tried++;
                if(!ready.get(at))update((worldX>>4)+at%chunks,(worldZ>>4)+at/chunks);
            }
            return tried;
        }
        /** net.minecraft.client.texture.TextureManager 负责同时释放注册项及 GPU 图像。 */
        private void close(){MinecraftClient.getInstance().getTextureManager().destroyTexture(id);}
    }
    /** 纸色已转换为 NativeImage 的 ABGR，未探索区块不显示任何隐藏世界信息。 */
    private static int paper(int x,int z){return ((x+z)&8)==0?0xff8ec2d7:0xff89bed4;}
    /** 世界页键复用 net.minecraft.util.math.ChunkPos 的有符号打包，负坐标保持连续。 */
    private long key(int chunkX,int chunkZ){return ChunkPos.toLong(Math.floorDiv(chunkX*16,PAGE_PIXELS*step),Math.floorDiv(chunkZ*16,PAGE_PIXELS*step));}
    /**
     * 在当前视口绘制缓存页；平移仅修改矩阵，GPU 执行纹理采样、缩放与裁切。
     * 每帧先处理实际变更；缓存命中不读取世界、不逐像素重采样，磁盘读取仍异步。
     */
    public void draw(DrawContext context,int left,int top,int width,int height,double centerX,double centerZ,double blocksPerPixel){
        int nextStep=1;while(nextStep<blocksPerPixel&&nextStep<16)nextStep*=2;
        if(nextStep!=step){close();step=nextStep;}
        for(long changed:ExploredMap.takeChanges()){
            int chunkX=ChunkPos.getPackedX(changed),chunkZ=ChunkPos.getPackedZ(changed);
            Page page=pages.get(key(chunkX,chunkZ));
            if(page!=null)page.update(chunkX,chunkZ);
        }
        double minX=centerX-width*blocksPerPixel/2,minZ=centerZ-height*blocksPerPixel/2;
        int size=PAGE_PIXELS*step;
        int firstX=(int)Math.floor(minX/size),lastX=(int)Math.floor((minX+width*blocksPerPixel)/size);
        int firstZ=(int)Math.floor(minZ/size),lastZ=(int)Math.floor((minZ+height*blocksPerPixel)/size);
        long deadline=System.nanoTime()+FRAME_NANOS;int budget=FRAME_CHUNKS;
        for(int z=firstZ;z<=lastZ;z++)for(int x=firstX;x<=lastX;x++){
            long key=ChunkPos.toLong(x,z);Page page=pages.get(key);
            if(page==null){page=new Page(x,z);pages.put(key,page);}
            if(budget>0)budget-=page.fill(budget,deadline);
            var matrices=context.getMatrices();matrices.push();
            // 浮点平移和缩放由 GPU 完成，避免整数宽度舍入导致相邻地图页出现白缝。
            matrices.translate(left+(page.worldX-minX)/blocksPerPixel,top+(page.worldZ-minZ)/blocksPerPixel,0);
            float scale=(float)(step/blocksPerPixel);matrices.scale(scale,scale,1);
            context.drawTexture(page.id,0,0,0,0,PAGE_PIXELS,PAGE_PIXELS,PAGE_PIXELS,PAGE_PIXELS);matrices.pop();
        }
        // DrawContext 可能仍持有上一页的批次；先提交再回收 LRU，避免提交时纹理已被销毁。
        context.draw();
        int retained=Math.max(MAX_PAGES,(lastX-firstX+1)*(lastZ-firstZ+1));
        while(pages.size()>retained){Iterator<Page> iterator=pages.values().iterator();Page page=iterator.next();iterator.remove();page.close();}
    }
    /** 关闭地图或改变采样层级时释放所有页面，探索的磁盘/内存瓦片仍可复用。 */
    @Override public void close(){for(Page page:pages.values())page.close();pages.clear();}
}
