/** 文件：收集实体与方块实体实际材质和动态模型，供探照灯受光和透视遮挡共用；端侧：CLIENT。 */
package org.DJB.no_24_heli.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;

/**
 * 职责：通过原渲染器收集箱盖、电梯轿厢、其他飞机等实际模型，保留纹理、顶点色和动画。
 * 引用 net.minecraft.client.render.block.entity.BlockEntityRenderer 和实体渲染器生成几何，VertexBuffer 按材质复用。
 * 生命周期：仅活动探照灯创建，每帧收集光锥内实体与方块实体；离开光锥的批次立即释放，随灯具关闭清理。
 * 端侧/线程：仅客户端渲染线程，只读已加载世界；不调用原图层绘制，不依赖主相机是否看见遮挡物。
 * 排查：漏照查渲染器是否向 VertexConsumerProvider 提交有纹理和法线的模型；纯特效层不参与遮挡。
 */
@Environment(EnvType.CLIENT)
final class SearchlightModels implements VertexConsumerProvider,AutoCloseable {
    /** 每种材质的初始容量为 16 KiB，按需扩容；离开光锥后释放，不累积探索过的纹理。 */
    private static final int BUILD_CAPACITY=16*1024;
    /** 飞机实际尾梁和旋翼超出实体碰撞箱，沿用实体渲染器的六格筛选余量；单位：格。 */
    private static final double HELICOPTER_MARGIN=6;
    /** 不采集文字、光束与附加闪光层；零消费者联合保留原渲染器的提交调用但不存储顶点。 */
    private static final VertexConsumer IGNORED=VertexConsumers.union(new VertexConsumer[0]);
    /** 同一个源图层共享一批动态显存，所有顶点均相对本灯地形网格的世界锚点。 */
    private final Map<RenderLayer,Batch> batches=new LinkedHashMap<>();

    /** 单材质缓冲独立分配，允许原渲染器交错提交多个图层；仅客户端渲染线程使用。 */
    private static final class Batch implements AutoCloseable {
        final BufferAllocator allocator=new BufferAllocator(BUILD_CAPACITY);
        final VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        final int texture;
        /** 受照面沿用源图层的背面剔除；薄内饰的背面不能覆盖已受光的可见正面。 */
        final boolean cull;
        BufferBuilder builder;
        VertexConsumer consumer;
        boolean active;

        /** 功能：借 net.minecraft.client.render.RenderLayer 解析纹理和剔除；默认剔除阶段不主动开启，须先恢复基态。 */
        Batch(RenderLayer layer){
            RenderSystem.enableCull();
            layer.startDrawing();
            try{texture=RenderSystem.getShaderTexture(0);cull=GL11C.glIsEnabled(GL11C.GL_CULL_FACE);}
            finally{layer.endDrawing();}
        }

        /** 功能：上传完整批次；引用 BuiltBuffer 随 VertexBuffer#upload 释放 CPU 数据，空批次不绘制。 */
        void upload(){
            BuiltBuffer built=builder.endNullable();builder=null;active=built!=null;
            if(active){buffer.bind();try{buffer.upload(built);}finally{VertexBuffer.unbind();}}
        }

        /** 功能：释放本材质显存与分配器，不关闭由原版材质管理器持有的纹理。 */
        @Override public void close(){buffer.close();allocator.close();}
    }

    /** 功能：为实际模型提供统一照明顶点；引用 RenderLayer 的源格式排除缺少法线或纹理的特效。 */
    @Override public VertexConsumer getBuffer(RenderLayer layer){
        var elements=layer.getVertexFormat().getElements();
        if(!elements.contains(VertexFormatElement.NORMAL)||!elements.contains(VertexFormatElement.UV_0)
                ||!elements.contains(VertexFormatElement.COLOR))return IGNORED;
        Batch batch=batches.computeIfAbsent(layer,Batch::new);
        if(batch.builder==null){
            batch.builder=new BufferBuilder(batch.allocator,layer.getDrawMode(),VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
            batch.consumer=new ModelVertices(batch.builder);
        }
        return batch.consumer;
    }

    /**
     * 功能：按当前插值动画收集候选模型；直接调用原渲染器，避免主相机距离筛选漏掉灯头前方遮挡物。
     * 引用 net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher#get 选择原渲染器；
     * net.minecraft.client.render.entity.EntityRenderDispatcher#getRenderer 保留第三方轿厢等模型和动画；
     * net.minecraft.world.World#isChunkLoaded 防止请求加载新区块。仅客户端渲染线程，无服务端写入。
     */
    void capture(HelicopterEntity heli,List<BlockPos> positions,Vec3d origin,Vec3d source,Vec3d direction,float delta){
        var client=MinecraftClient.getInstance();var world=heli.getWorld();
        int read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),draw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] viewport=new int[4];GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);
        try{
            for(BlockPos pos:positions){
                if(!world.isChunkLoaded(pos))continue;
                BlockEntity entity=world.getBlockEntity(pos);
                if(entity==null||entity.isRemoved())continue;
                var renderer=client.getBlockEntityRenderDispatcher().get(entity);
                if(renderer==null)continue;
                MatrixStack matrices=new MatrixStack();
                matrices.translate(pos.getX()-origin.x,pos.getY()-origin.y,pos.getZ()-origin.z);
                renderer.render(entity,delta,matrices,this,LightmapTextureManager.MAX_LIGHT_COORDINATE,OverlayTexture.DEFAULT_UV);
            }
            // 六格余量容纳直升机尾梁和旋翼，实体随后按实际包围球与光锥相交筛选。
            Box lightBounds=new Box(source,source.add(direction.multiply(SearchlightRenderer.RANGE)))
                    .expand(SearchlightRenderer.SAMPLE_APERTURE+SearchlightRenderer.RANGE*SearchlightRenderer.SAMPLE_TANGENT+HELICOPTER_MARGIN);
            for(Entity entity:world.getOtherEntities(heli,lightBounds)){
                if(entity.isRemoved()||entity.isInvisible()||!intersects(entity,source,direction))continue;
                var renderer=client.getEntityRenderDispatcher().getRenderer(entity);
                Vec3d position=renderPosition(entity,delta).add(renderer.getPositionOffset(entity,delta)).subtract(origin);
                MatrixStack matrices=new MatrixStack();matrices.translate(position.x,position.y,position.z);
                // 不调用分派器的火焰和脚底阴影，源直升机也不作为自己的受照模型收集。
                renderer.render(entity,entity.getYaw(delta),delta,matrices,this,LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            var iterator=batches.values().iterator();
            while(iterator.hasNext()){
                Batch batch=iterator.next();
                if(batch.builder==null){batch.close();iterator.remove();}
                else batch.upload();
            }
        }finally{
            // 借图层取纹理可能切换华丽画质的目标；即使本帧没有模型，也必须还原合成状态。
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,draw);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(false);RenderSystem.disableDepthTest();
            RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.blendFunc(GL11C.GL_ONE,GL11C.GL_ONE);
        }
    }

    /**
     * 功能：沿用 net.minecraft.client.render.WorldRenderer 的 lastRender 位置插值，和实际实体表面重合。
     * 端侧：客户端渲染线程；不能改用 prev 位置，轿厢渲染器会自行补偿其平滑运动与 lastRender 插值的差值。
     */
    static Vec3d renderPosition(Entity entity,float delta){
        return new Vec3d(MathHelper.lerp(delta,entity.lastRenderX,entity.getX()),
                MathHelper.lerp(delta,entity.lastRenderY,entity.getY()),MathHelper.lerp(delta,entity.lastRenderZ,entity.getZ()));
    }

    /**
     * 功能：保守检查实体包围球与圆形灯面起始的有限圆台光束是否相交；引用 net.minecraft.util.math.Box 扩展飞机外露部件。
     * 端侧：客户端渲染线程；只筛选候选，实际受光与遮挡仍由模型片元决定，不能用包围盒代替实体几何。
     */
    private static boolean intersects(Entity entity,Vec3d source,Vec3d direction){
        Box bounds=entity instanceof HelicopterEntity?entity.getBoundingBox().expand(HELICOPTER_MARGIN):entity.getBoundingBox();
        double radius=new Vec3d(bounds.getLengthX(),bounds.getLengthY(),bounds.getLengthZ()).length()*.5;
        Vec3d relative=bounds.getCenter().subtract(source);double along=relative.dotProduct(direction);
        double coneRadius=SearchlightRenderer.SAMPLE_APERTURE+Math.max(0,along)*SearchlightRenderer.SAMPLE_TANGENT
                +radius*Math.sqrt(1+SearchlightRenderer.SAMPLE_TANGENT*SearchlightRenderer.SAMPLE_TANGENT);
        return along+radius>=0&&along-radius<=SearchlightRenderer.RANGE
                &&relative.lengthSquared()-along*along<=coneRadius*coneRadius;
    }

    /** 功能：判断本帧是否有实体模型；无地形网格时仍允许箱子等独立模型参与照明。 */
    boolean isEmpty(){return batches.values().stream().noneMatch(batch->batch.active);}

    /** 功能：同一模型分别绘入灯头深度和受照面；受照面沿用源图层剔除，阴影保留双面遮挡。 */
    void draw(ShaderProgram shader,boolean receiving){
        for(Batch batch:batches.values())if(batch.active){
            if(receiving&&batch.cull)RenderSystem.enableCull();else RenderSystem.disableCull();
            shader.addSampler("BlockAtlas",batch.texture);
            shader.bind();batch.buffer.bind();batch.buffer.draw();
        }
        RenderSystem.disableCull();
    }

    /** 功能：随灯具关闭、卸载或资源重载释放所有动态缓冲，不持有方块实体引用。 */
    @Override public void close(){for(Batch batch:batches.values())batch.close();batches.clear();}

    /** 将原模型的纹理、顶点色和法线保留到照明格式；原光照和受伤覆盖层不作为材质颜色。 */
    private record ModelVertices(VertexConsumer delegate) implements VertexConsumer {
        /** 功能：开始相对网格锚点的顶点，补齐本合成程序借用的覆盖层和灯光属性。 */
        @Override public VertexConsumer vertex(float x,float y,float z){delegate.vertex(x,y,z).overlay(0,0).light(0,0);return this;}
        /** 功能：保留模型和染色的原始 RGBA，不烘焙环境光。 */
        @Override public VertexConsumer color(int red,int green,int blue,int alpha){delegate.color(red,green,blue,alpha);return this;}
        /** 功能：保留当前源材质的真实 UV，可使用方块图集以外的箱子、床等纹理。 */
        @Override public VertexConsumer texture(float u,float v){delegate.texture(u,v);return this;}
        /** 功能：忽略原受伤覆盖层，防止被误解为地形材质覆盖层 UV。 */
        @Override public VertexConsumer overlay(int u,int v){return this;}
        /** 功能：忽略世界光照坐标，由探照灯程序统一计算亮度。 */
        @Override public VertexConsumer light(int u,int v){return this;}
        /** 功能：保留经模型动画变换后的世界法线，供实际入射方向计算。 */
        @Override public VertexConsumer normal(float x,float y,float z){delegate.normal(x,y,z);return this;}
    }
}
