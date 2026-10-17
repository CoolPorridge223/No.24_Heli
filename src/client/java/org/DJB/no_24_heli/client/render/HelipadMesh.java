/** 文件：合并停机坪灯芯的共享静态显存；端侧：CLIENT；方块：no_24_heli:helipad、helipad_surface。 */
package org.DJB.no_24_heli.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * 职责：同一昼夜状态与灯具集合只上传一次，跨停机坪复用；通常整圈十六灯只绘制一次。
 * 引用：net.minecraft.client.gl.VertexBuffer 保存局部灯芯，HelicopterMesh 共用矩阵与光影格式处理。
 * 生命周期：初始化登记，首次可见惰性上传，资源/光影重载和退出时释放；只在客户端渲染线程访问。
 * 排查：跨区块缺灯查实际碎片掩码；夜间不发光查信标图层；重载花屏查资源代数和缓存清理。
 */
@Environment(EnvType.CLIENT)
final class HelipadMesh {
    /** 白天暗琥珀与夜间暖黄，沿用原灯芯颜色；ARGB。 */
    static final int OFF_COLOR=0xff71613a,ON_COLOR=0xffffd477;
    /** 局部灯芯总量不足 64 KiB；缓存最多 64 组，防止不同遮挡光照或区块组合无限累积。 */
    private static final int BUILD_CAPACITY=64*1024,CACHE_LIMIT=64;
    /** 掩码只包含当前已加载且仍属于该停机坪的灯具，不按世界坐标分配显存。 */
    private record Key(int mask,boolean on){}
    /** 按最近使用顺序淘汰，所有实例共享；每项只有一个静态缓冲。 */
    private static final Map<Key,HelicopterMesh.Segment> CACHE=new LinkedHashMap<>(16,.75f,true);
    /** 记录飞机网格的资源代数，使两类缓存共同响应光影格式切换。 */
    private static int revision=-1;
    /** 主视图的当前视锥，覆盖整圈灯而非负责碎片的一格；阴影阶段使用其自身遍历范围。 */
    private static Frustum frustum;

    /** 私有构造：仅管理共享显存。 */
    private HelipadMesh(){}

    /** 功能：登记资源重载和退出清理；引用 Fabric 客户端事件，仅初始化调用一次。 */
    static void register(){
        CoreShaderRegistrationCallback.EVENT.register(context->clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->clear());
        WorldRenderEvents.BEFORE_ENTITIES.register(context->frustum=context.frustum());
    }

    /** 功能：以完整圆盘包围盒筛选主视图，阴影遍历不使用主相机视锥；客户端渲染线程调用。 */
    static boolean visible(BlockPos center){
        boolean gpu=prepare();
        return frustum==null||gpu&&HelicopterMesh.isShadowPass()||frustum.isVisible(new Box(center).expand(5,0,5));
    }

    /** 功能：同步可选光影与资源格式；客户端渲染线程调用；不改变当前方块实体绘制阶段。 */
    static boolean prepare(){
        if(!HelicopterMesh.prepare())return false;
        if(revision!=HelicopterMesh.revision()){
            clear();revision=HelicopterMesh.revision();
        }
        return true;
    }

    /** 功能：选择原有昼夜图层；客户端渲染线程调用；夜间保留光影识别的信标程序。 */
    static RenderLayer layer(boolean on){
        return on?RenderLayer.getBeaconBeam(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE,false):
                RenderLayer.getEntityCutout(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
    }

    /**
     * 功能：按同光照的灯具集合绘制一次；客户端渲染线程调用；首次使用上传局部坐标，后续仅更新矩阵和光照。
     * 引用 MC API：RenderLayer 保留原昼夜管线；副作用：有界 LRU 缓存，淘汰时立即关闭显存。
     */
    static void draw(int mask,boolean on,int light,MatrixStack matrices){
        Key key=new Key(mask,on);HelicopterMesh.Segment segment=CACHE.get(key);
        if(segment==null){
            if(CACHE.size()>=CACHE_LIMIT){
                var oldest=CACHE.entrySet().iterator();oldest.next().getValue().buffer().close();oldest.remove();
            }
            segment=upload(mask,on);CACHE.put(key,segment);
        }
        HelicopterMesh.draw(List.of(segment),matrices,layer(on),light,!on);
    }

    /**
     * 功能：将指定碎片灯芯平移到圆盘局部坐标后上传；客户端渲染线程调用；不读取世界。
     * 引用 MC API：BufferBuilder 自动补齐 Iris 扩展顶点；白天 UV2 改为动态光照常量属性。
     */
    private static HelicopterMesh.Segment upload(int mask,boolean on){
        try(BufferAllocator allocator=new BufferAllocator(BUILD_CAPACITY)){
            BufferBuilder builder=new BufferBuilder(allocator,VertexFormat.DrawMode.QUADS,layer(on).getVertexFormat());
            int color=on?ON_COLOR:OFF_COLOR;
            float red=(color>>16&255)/255f,green=(color>>8&255)/255f,blue=(color&255)/255f;
            MatrixStack matrices=new MatrixStack();var cells=HelipadModel.lampCells();
            for(int i=0;i<cells.size();i++)if((mask&(1<<i))!=0){
                var offset=cells.get(i);matrices.push();matrices.translate(offset.getX(),0,offset.getZ());
                for(var quad:HelipadModel.lenses(offset))
                    builder.quad(matrices.peek(),quad,red,green,blue,1,0,OverlayTexture.DEFAULT_UV);
                matrices.pop();
            }
            VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);buffer.bind();
            try{
                buffer.upload(builder.end());
                int attribute=on?-1:buffer.getVertexFormat().getElements().indexOf(VertexFormatElement.UV_2);
                if(attribute>=0)GlStateManager._disableVertexAttribArray(attribute);
                return new HelicopterMesh.Segment(buffer,attribute,0);
            }finally{VertexBuffer.unbind();}
        }
    }

    /** 功能：释放全部共享灯芯显存；引用 VertexBuffer#close；仅客户端渲染线程调用。 */
    private static void clear(){
        for(var segment:CACHE.values())segment.buffer().close();CACHE.clear();
    }
}
