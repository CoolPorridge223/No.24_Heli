/** 文件：机腹探照灯透视阴影、地形照明网格与自发光程序；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockRenderType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * 职责：保存实体深度，在光影合成后补充地形照明、灯芯和启动仪表的文字背光。
 * 引用：net.minecraft.client.render.model.BakedQuad 保留地形材质；独立灯头透视深度决定逐片元遮挡。
 * 生命周期：分帧收集光锥内几何，灯头移动时更新阴影；世界合成后、手持物前复用显存绘制。
 * 端侧/线程：仅客户端渲染线程；不放置光源方块、不重建区块或写服务端方块光照。
 * 排查：穿透机体查实体阶段深度快照；格子查重复面；灯芯消失查是否误入光影阴影遍历。
 */
@Environment(EnvType.CLIENT)
public final class SearchlightRenderer {
    /** 光源位于球体云台的前镜片，单位：机体局部格数。 */
    private static final Vec3d PIVOT=new Vec3d(0,.44,1.24);
    /** 最大射程 64 格，发光半角十二度，采样额外留两度缓冲；只处理相机 96 格内的灯。 */
    private static final double RANGE=64,VIEW_DISTANCE_SQUARED=96*96;
    private static final double SAMPLE_TANGENT=Math.tan(Math.toRadians(14));
    /** 方块顶点八整数；照明几何只标记表面，不偏移深度或写入光影阴影图。 */
    private static final int BLOCK_STRIDE=8,BUILD_CAPACITY=256*1024;
    /** 每灯 1024² 透视深度；近裁剪 0.05 格，覆盖完整 64 格射程。 */
    private static final int SHADOW_SIZE=1024;
    private static final float SHADOW_NEAR=.05f;
    /** 所有灯共享每帧 2 ms 地形收集预算；每个方块处理完检查，不能中断半个模型。 */
    private static final long BUILD_BUDGET_NS=2_000_000;
    /** 当前主视图的收集截止时间和轮转起点，只由渲染线程访问。 */
    private static long buildDeadline;
    private static int firstLight;
    /** 位置缓存随世界离开、灯具关闭或实体移除而释放，不按每帧分配缓冲。 */
    private static final Map<HelicopterEntity,LightCache> CACHE=new HashMap<>();
    /** 固定合成程序绕过光影实体材质过滤，灯芯与受照面都显式检查深度快照。 */
    private static ShaderProgram shader;
    /** 深度来自实体绘制后，颜色来自光影最终画面；窗口变化时重建，退出时释放。 */
    private static SimpleFramebuffer sceneSnapshot;
    /** 灯芯只排入主视图，保留实际实体矩阵，不参与光影阴影图或后续深度写入。 */
    private static final List<Glow> GLOWS=new ArrayList<>();
    /** 仪表文字只保存当前帧的观察空间矩阵，不受探照灯开关、示廓灯闪烁或昼夜控制。 */
    private static final List<BacklitLabel> LABELS=new ArrayList<>();
    /** 字形缓冲跨帧复用，资源重载、离开世界或退出时释放；字体页仍由原版字体管理器持有。 */
    private static BacklitText backlitText;
    /** 当前光影的像素抖动与低分辨率深度比例；主视图灯具登记时读取，不修改光影选项。 */
    private static final float[] DEPTH_JITTER=new float[2];
    private static int revision=-1;
    /** 主视图矩阵与雾参数在世界阶段保存，稍后在原版清空手持物深度前使用；不沿用阴影相机。 */
    private static Matrix4f worldView,worldProjection,inverseProjection;
    private static Vec3d cameraPosition;
    private static Frustum viewFrustum;
    private static float tickDelta,fogStart,fogEnd;
    /** 合成阶段的雾形状须沿用世界快照；0 为球形、1 为柱形，避免手持物阶段重置雾参数。 */
    private static int fogShape;
    private static boolean prepared;
    /** 原始材质和可选草侧面覆盖层独立于太阳阴影；覆盖层 UV/色借用仅在本合成程序使用的 UV1/UV2。 */
    private record LitVertex(Vec3d pos,Vec3d normal,float u,float v,int color,float overlayU,float overlayV,int overlayColor){}
    /** 重合的底图和着色覆盖层合并为一个面，不能作为两次加法照明提交。 */
    private record LitFace(List<Vec3d> positions,Vec3d normal,BakedQuad base,BakedQuad overlay){}
    /** 机体局部灯芯的固定矩阵与颜色，沿用共享网格；只存活于当前主视图。 */
    private record Glow(HelicopterModel model,String part,int paint,boolean distant,Matrix4f modelView,int color){}
    /** 启动仪表的本地化文字快照；保留样式、姓名和字宽缩放，不重画停机文字。 */
    private record BacklitLabel(Text value,float x,float y,int color,Matrix4f matrix){}

    /**
     * 职责：复用 net.minecraft.client.font.TextRenderer 的字体选择和字形顶点，合成时按字体页分批绘制。
     * 端侧/线程：客户端渲染线程；只使用原字体层绑定图集，不调用其受光程序绘字。
     * 排查：中文黑块检查单通道字形模式；文字穿模检查场景深度；缓存由 reset 释放。
     */
    private static final class BacklitText implements VertexConsumerProvider,AutoCloseable {
        /** 字体使用灯光程序的实体顶点格式，补齐字体原本不提供的覆盖层和法线。 */
        private final BufferAllocator allocator=new BufferAllocator(BUILD_CAPACITY);
        private final VertexBuffer buffer=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        private BufferBuilder builder;
        private RenderLayer currentLayer;
        private VertexConsumer consumer;
        private int texture,mode;

        /** 功能：在字体页变化时提交上一批；引用 RenderLayer 取得实际图集，并识别单通道字体。 */
        @Override public VertexConsumer getBuffer(RenderLayer layer){
            if(layer!=currentLayer){
                flush();layer.startDrawing();
                try{
                    texture=RenderSystem.getShaderTexture(0);
                    mode=RenderSystem.getShader()==GameRenderer.getRenderTypeTextIntensityProgram()?3:2;
                }finally{layer.endDrawing();}
                currentLayer=layer;
                builder=new BufferBuilder(allocator,VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
                consumer=new FontVertices(builder);
            }
            return consumer;
        }

        /** 功能：上传字形并沿用主视图深度遮挡；不写深度，不经过光影的普通文字受光阶段。 */
        private void flush(){
            if(builder==null)return;
            BuiltBuffer built=builder.endNullable();builder=null;currentLayer=null;
            if(built==null)return;
            buffer.bind();buffer.upload(built);
            uniforms(new Matrix4f(),mode);shader.addSampler("FontAtlas",texture);
            RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();
            RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();
            shader.bind();buffer.draw();VertexBuffer.unbind();shader.unbind();
        }

        /** 功能：释放本客户端拥有的字体临时缓冲；原版字体页不归本类所有，不能关闭。 */
        @Override public void close(){buffer.close();allocator.close();}
    }

    /** 将原版字形的 POSITION/COLOR/UV/LIGHT 提交适配为合成程序的完整实体格式；仅渲染线程使用。 */
    private record FontVertices(VertexConsumer delegate) implements VertexConsumer {
        /** 功能：开始一个观察空间顶点并补齐不参与字体着色的属性。 */
        @Override public VertexConsumer vertex(float x,float y,float z){delegate.vertex(x,y,z).overlay(0,0).normal(0,0,1);return this;}
        /** 功能：保留字形及文本样式的原始颜色。 */
        @Override public VertexConsumer color(int red,int green,int blue,int alpha){delegate.color(red,green,blue,alpha);return this;}
        /** 功能：保留原版字体页中的字形 UV。 */
        @Override public VertexConsumer texture(float u,float v){delegate.texture(u,v);return this;}
        /** 功能：转交覆盖层属性；标准字形不主动使用此属性。 */
        @Override public VertexConsumer overlay(int u,int v){delegate.overlay(u,v);return this;}
        /** 功能：补齐原字形的采光坐标；背光合成不读取该采光值。 */
        @Override public VertexConsumer light(int u,int v){delegate.light(u,v);return this;}
        /** 功能：转交法线；标准字形不主动使用此属性。 */
        @Override public VertexConsumer normal(float x,float y,float z){delegate.normal(x,y,z);return this;}
    }
    /** 世界固定锚点避免飞机运动时把地面纹理一并拖走；buffer 在首次绘制时创建。 */
    private static final class Projection {
        final Vec3d origin,source,direction;
        final long tick;
        final List<LitVertex> vertices;
        VertexBuffer buffer;
        /** 阴影附件随本灯网格复用，矩阵与上次姿态用于判定是否需要重画；不占用世界阴影图。 */
        SimpleFramebuffer shadow;
        Matrix4f shadowMatrix;
        Vec3d shadowSource,shadowDirection;
        /** 功能：保存一次完整地形收集结果；客户端渲染线程调用，显存惰性创建。 */
        Projection(Vec3d origin,Vec3d source,Vec3d direction,long tick,List<LitVertex> vertices){
            this.origin=origin;this.source=source;this.direction=direction;this.tick=tick;this.vertices=vertices;
        }
        /** 功能：关闭本灯的 GPU 缓冲；客户端渲染线程调用。 */
        void close(){if(buffer!=null)buffer.close();if(shadow!=null)shadow.delete();}
    }

    /** 客户端单灯缓存；新网格完成前保留旧显存，避免分帧更新时光斑闪灭；关闭时一起释放。 */
    private static final class LightCache {
        Projection projection;
        MeshBuild pending;
        /** 功能：丢弃未完成的 CPU 收集并释放已上传网格；不持有或修改服务端世界。 */
        void close(){if(projection!=null)projection.close();pending=null;}
    }

    /**
     * 职责：分帧遍历光锥包围盒，收集所有外露模型面，顶面和侧面一视同仁。
     * 引用 net.minecraft.client.world.ClientWorld 的已加载方块；只在渲染线程推进，不跨线程访问世界。
     * 生命周期：缓存过期时创建，遍历完成后替换 Projection；黑面先查完整面收集，再查灯头深度。
     */
    private static final class MeshBuild {
        final Vec3d origin,source,direction;
        final int minX,minY,minZ,maxX,maxY,maxZ;
        final List<LitVertex> vertices=new ArrayList<>();
        final Random random=Random.create(0);
        /** 区段只在这次构建内缓存；空区段整行跳过，避免每次扫描光锥内的大量空气格。 */
        final Map<Long,ChunkSection> sections=new HashMap<>();
        long sectionKey=Long.MIN_VALUE;
        ChunkSection section;
        int x,y,z;

        /** 功能：用灯头位置和光锥末端圆盘构建带两格余量的包围盒，允许缓存期间的轻微运动。 */
        MeshBuild(Vec3d origin,Vec3d source,Vec3d direction){
            this.origin=origin;this.source=source;this.direction=direction;
            Vec3d end=source.add(direction.multiply(RANGE));double radius=RANGE*SAMPLE_TANGENT;
            double rx=radius*Math.sqrt(Math.max(0,1-direction.x*direction.x));
            double ry=radius*Math.sqrt(Math.max(0,1-direction.y*direction.y));
            double rz=radius*Math.sqrt(Math.max(0,1-direction.z*direction.z));
            minX=MathHelper.floor(Math.min(source.x,end.x-rx)-2);maxX=MathHelper.floor(Math.max(source.x,end.x+rx)+2);
            minY=MathHelper.floor(Math.min(source.y,end.y-ry)-2);maxY=MathHelper.floor(Math.max(source.y,end.y+ry)+2);
            minZ=MathHelper.floor(Math.min(source.z,end.z-rz)-2);maxZ=MathHelper.floor(Math.max(source.z,end.z+rz)+2);
            x=minX;y=minY;z=minZ;
        }

        /** 功能：按全局时间预算推进；仅查询已加载区块，光锥外不取模型，完全取消逐面射线。 */
        boolean advance(HelicopterEntity heli){
            while(z<=maxZ&&System.nanoTime()<buildDeadline){
                long key=BlockPos.asLong(x>>4,y>>4,z>>4);
                if(key!=sectionKey){sectionKey=key;section=section(heli,key);}
                if(section==null||section.isEmpty())x=Math.min(maxX,x|15);
                else{
                    double dx=x+.5-source.x,dy=y+.5-source.y,dz=z+.5-source.z;
                    double along=dx*direction.x+dy*direction.y+dz*direction.z;
                    double radius=Math.max(0,along)*SAMPLE_TANGENT+2;
                    if(along>=-2&&along<=RANGE+2&&dx*dx+dy*dy+dz*dz-along*along<=radius*radius){
                        BlockState state=section.getBlockState(x&15,y&15,z&15);
                        if(state.getRenderType()==BlockRenderType.MODEL)bakeBlock(heli,origin,new BlockPos(x,y,z),state,random,vertices);
                    }
                }
                if(++x>maxX){x=minX;if(++y>maxY){y=minY;z++;}}
            }
            return z>maxZ;
        }

        /** 功能：只取已存在的 net.minecraft.world.chunk.ChunkSection；不请求加载区块，越界或未加载缓存为空。 */
        private ChunkSection section(HelicopterEntity heli,long key){
            if(sections.containsKey(key))return sections.get(key);
            var world=heli.getWorld();ChunkSection result=null;
            if(y>=world.getBottomY()&&y<world.getTopY()){
                var chunk=world.getChunk(x>>4,z>>4,ChunkStatus.FULL,false);
                if(chunk!=null)result=chunk.getSection(world.getSectionIndex(y));
            }
            sections.put(key,result);return result;
        }
    }

    /** 私有构造：灯光缓存由客户端入口统一登记。 */
    private SearchlightRenderer(){}

    /** 功能：登记程序、重载和退出清理；引用 Fabric 客户端事件，不新增运行依赖。 */
    public static void register(){
        CoreShaderRegistrationCallback.EVENT.register(context->{
            reset();context.register(No_24_heli.id("searchlight"),VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                    program->shader=program);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->reset());
        WorldRenderEvents.START.register(context->{
            HelicopterMesh.prepare();if(HelicopterMesh.isShadowPass())return;
            GLOWS.clear();LABELS.clear();prepared=false;
            DEPTH_JITTER[0]=0;DEPTH_JITTER[1]=0;
        });
        // 独立于机身视锥；这里只保存主视图，实际光斑须在光影完成世界合成后绘制。
        WorldRenderEvents.AFTER_ENTITIES.register(context->{
            HelicopterMesh.prepare();if(HelicopterMesh.isShadowPass())return;
            boolean active=!GLOWS.isEmpty()||!LABELS.isEmpty();
            for(var entity:context.world().getEntities())if(entity instanceof HelicopterEntity heli&&heli.flight.searchlight&&!heli.flight.wreck
                    &&context.camera().getPos().squaredDistanceTo(heli.getPos())<VIEW_DISTANCE_SQUARED){active=true;break;}
            if(!active)return;
            if(revision!=HelicopterMesh.revision()){clear();revision=HelicopterMesh.revision();}
            worldView=new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.matrixStack().peek().getPositionMatrix());
            worldProjection=new Matrix4f(RenderSystem.getProjectionMatrix());
            inverseProjection=new Matrix4f(worldProjection).invert();
            cameraPosition=context.camera().getPos();viewFrustum=context.frustum();
            tickDelta=context.tickCounter().getTickDelta(false);fogStart=RenderSystem.getShaderFogStart();fogEnd=RenderSystem.getShaderFogEnd();
            fogShape=RenderSystem.getShaderFogShape().getId();
            captureDepth();prepared=true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->CACHE.entrySet().removeIf(entry->{
            var heli=entry.getKey();
            boolean discard=heli.isRemoved()||heli.getWorld()!=client.world||!heli.flight.searchlight||heli.flight.wreck;
            if(discard)entry.getValue().close();return discard;
        }));
    }

    /** 功能：读取同步世界时间控制夜间一秒一闪、每次亮四分之一秒；固定时间维度和残骸关闭。 */
    static boolean navigationLightsOn(HelicopterEntity heli,float delta){
        if(heli.flight.wreck||heli.getWorld().getDimension().hasFixedTime())return false;
        long time=Math.floorMod(heli.getWorld().getTimeOfDay(),24000);
        return time>=13000&&time<23000&&Math.floorMod(heli.getWorld().getTime(),20)+delta<5;
    }

    /** 功能：登记当前实体灯芯；客户端渲染线程调用，排除阴影遍历并保存实际相机与机体矩阵。 */
    static void queueGlow(HelicopterModel model,String part,HelicopterEntity heli,boolean distant,MatrixStack matrices,int color){
        if(HelicopterMesh.isShadowPass())return;
        captureJitter();
        GLOWS.add(new Glow(model,part,heli.flight.paint,distant,
                new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrices.peek().getPositionMatrix()),color));
    }

    /** 功能：登记已启动飞机的文字背光；保存实际观察矩阵，避开阴影遍历，不改变字体布局。 */
    static void queueText(Text value,float x,float y,int color,Matrix4f matrix){
        if(HelicopterMesh.isShadowPass())return;
        captureJitter();
        LABELS.add(new BacklitLabel(value,x,y,color,new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrix)));
    }

    /** 功能：首次登记可见发光内容时读取当前实体程序的 TAA 抖动，兼容仅有仪表背光的帧。 */
    private static void captureJitter(){
        if(GLOWS.isEmpty()&&LABELS.isEmpty()){
            int program=RenderSystem.getShader().getGlRef();
            int location=GL20C.glGetUniformLocation(program,"taa_offset");
            if(location>=0)GL20C.glGetUniformfv(program,location,DEPTH_JITTER);
        }
    }

    /**
     * 功能：复制当前实体阶段的真实深度，不依赖光影最终帧缓冲是否保留机体深度。
     * 引用 net.minecraft.client.gl.SimpleFramebuffer 复用纹理；深度复制允许不同内部精度，避免深度 blit 格式冲突。
     * 端侧：客户端渲染线程；恢复读写目标、纹理和视口，不能把临时帧缓冲留给 Iris 后续阶段。
     */
    private static void captureDepth(){
        int read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),draw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int texture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);int[] viewport=new int[4];
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);
        try{
            if(sceneSnapshot==null||sceneSnapshot.textureWidth!=viewport[2]||sceneSnapshot.textureHeight!=viewport[3]){
                if(sceneSnapshot!=null)sceneSnapshot.delete();
                sceneSnapshot=new SimpleFramebuffer(viewport[2],viewport[3],true,MinecraftClient.IS_SYSTEM_MAC);
            }
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,draw);
            RenderSystem.bindTexture(sceneSnapshot.getDepthAttachment());
            GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D,0,0,0,viewport[0],viewport[1],viewport[2],viewport[3]);
        }finally{
            RenderSystem.bindTexture(texture);GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,draw);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
        }
    }

    /** 功能：取得机身局部云台旋转，与模型和光束共用；角度为服务器状态的客户端插值。 */
    static Quaternionf gimbal(HelicopterEntity heli,float delta){
        return new Quaternionf().rotationY((float)Math.toRadians(-MathHelper.lerp(delta,heli.previousLightYaw,heli.visualLightYaw)))
                .rotateX((float)Math.toRadians(-MathHelper.lerp(delta,heli.previousLightPitch,heli.visualLightPitch)));
    }

    /**
     * 功能：在世界合成后补足材质目标亮度并绘制灯芯，用先前快照显式遮挡机体和墙面。
     * 引用：MinecraftClient#getFramebuffer 取得光影最终颜色；只补正差值，不向阴影图提交光斑。
     * 端侧：客户端渲染线程，由 SearchlightCompositeMixin 调用；完成后还原混合和深度状态。
     */
    public static void composite(){
        if(!prepared)return;prepared=false;var client=MinecraftClient.getInstance();
        if(client.world==null)return;
        int read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,client.getFramebuffer().fbo);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,sceneSnapshot.fbo);
        GL30C.glBlitFramebuffer(0,0,client.getFramebuffer().textureWidth,client.getFramebuffer().textureHeight,
                0,0,sceneSnapshot.textureWidth,sceneSnapshot.textureHeight,GL11C.GL_COLOR_BUFFER_BIT,GL11C.GL_NEAREST);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);
        client.getFramebuffer().beginWrite(false);
        RenderSystem.disableDepthTest();RenderSystem.depthMask(false);RenderSystem.disableCull();RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11C.GL_ONE,GL11C.GL_ONE);
        try{
            List<HelicopterEntity> lights=new ArrayList<>();
            for(var entity:client.world.getEntities())if(entity instanceof HelicopterEntity heli&&heli.flight.searchlight&&!heli.flight.wreck)
                lights.add(heli);
            buildDeadline=System.nanoTime()+BUILD_BUDGET_NS;
            // 轮转起点使多灯共享预算，避免列表首灯运动时一直饿死其他灯。
            for(int i=0;i<lights.size();i++)render(lights.get(Math.floorMod(firstLight+i,lights.size())),tickDelta,viewFrustum);
            firstLight++;
            for(var glow:GLOWS){
                uniforms(glow.modelView,1);
                shader.getUniform("LampColor").set((glow.color>>16&255)/255f,(glow.color>>8&255)/255f,(glow.color&255)/255f,1);
                shader.bind();
                for(var segment:HelicopterMesh.mesh(glow.model,glow.part,glow.paint,false,glow.distant)){
                    segment.buffer().bind();segment.buffer().draw();
                }
            }
            if(!LABELS.isEmpty()){
                if(backlitText==null)backlitText=new BacklitText();
                var font=client.textRenderer;
                for(var label:LABELS)font.draw(label.value,label.x,label.y,label.color,false,label.matrix,
                        backlitText,TextRenderer.TextLayerType.NORMAL,0,LightmapTextureManager.MAX_LIGHT_COORDINATE);
                backlitText.flush();
            }
        }finally{
            VertexBuffer.unbind();shader.unbind();RenderSystem.defaultBlendFunc();RenderSystem.disableBlend();
            RenderSystem.enableCull();RenderSystem.depthMask(true);RenderSystem.enableDepthTest();GLOWS.clear();LABELS.clear();
        }
    }

    /** 功能：设置主视图快照；模式 0 地表、1 灯芯、2/3 字形、4 灯头深度；后者单独覆盖投影矩阵。 */
    private static void uniforms(Matrix4f modelView,int mode){
        var client=MinecraftClient.getInstance();
        shader.initializeUniforms(VertexFormat.DrawMode.QUADS,modelView,worldProjection,client.getWindow());
        shader.addSampler("SceneDepth",sceneSnapshot.getDepthAttachment());shader.addSampler("SceneColor",sceneSnapshot.getColorAttachment());
        shader.addSampler("LightDepth",sceneSnapshot.getDepthAttachment());
        shader.addSampler("BlockAtlas",client.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE).getGlId());
        shader.getUniform("InverseProjection").set(inverseProjection);shader.getUniform("LightMode").set(mode);
        shader.getUniform("ScreenSize").set((float)client.getFramebuffer().textureWidth,(float)client.getFramebuffer().textureHeight);
        float scale=HelicopterMesh.depthScale();
        shader.getUniform("DepthScale").set(scale);
        shader.getUniform("DepthJitter").set(DEPTH_JITTER[0]*(scale<1?.5f:.33f),DEPTH_JITTER[1]*(scale<1?.5f:.33f));
        shader.getUniform("FogStart").set(fogStart);shader.getUniform("FogEnd").set(fogEnd);
        shader.getUniform("FogShape").set(fogShape);
    }

    /** 功能：读取云台姿态并更新单灯缓存；客户端渲染线程，不改变 Iris 实体阶段。 */
    private static void render(HelicopterEntity heli,float delta,Frustum frustum){
        var client=MinecraftClient.getInstance();Vec3d origin=heli.getLerpedPos(delta);
        if(!heli.flight.searchlight||heli.flight.wreck||client.gameRenderer.getCamera().getPos().squaredDistanceTo(origin)>VIEW_DISTANCE_SQUARED)return;
        Quaternionf body=HeliView.bodyRotation(heli,delta),aim=new Quaternionf(body).mul(gimbal(heli,delta));
        Vector3f forward=new Vector3f(0,0,1).rotate(aim),pivot=new Vector3f((float)PIVOT.x,(float)PIVOT.y,(float)PIVOT.z).rotate(body);
        Vec3d direction=new Vec3d(forward.x,forward.y,forward.z).normalize();
        Vec3d source=origin.add(pivot.x,pivot.y,pivot.z).add(direction.multiply(.215));
        if(frustum!=null&&!frustum.isVisible(new Box(source,source.add(direction.multiply(RANGE))).expand(RANGE*SAMPLE_TANGENT)))return;
        long tick=heli.getWorld().getTime();LightCache cache=CACHE.computeIfAbsent(heli,key->new LightCache());
        Projection projection=cache.projection;
        // 半格或约一度开始刷新；两格和约两度为后面的缓存安全上限，单位分别为格平方与方向余弦。
        if(cache.pending==null&&(projection==null||tick-projection.tick>=10||tick-projection.tick>=2&&
                (source.squaredDistanceTo(projection.source)>.25||direction.dotProduct(projection.direction)<.99985)))
            cache.pending=new MeshBuild(origin,source,direction);
        if(cache.pending!=null&&cache.pending.advance(heli)){
            MeshBuild built=cache.pending;
            projection=new Projection(built.origin,built.source,built.direction,tick,List.copyOf(built.vertices));
            if(cache.projection!=null){
                // 阴影附件和顶点缓冲跨刷新复用；新几何必须重新上传并重画深度。
                projection.shadow=cache.projection.shadow;projection.buffer=cache.projection.buffer;
                cache.projection.shadow=null;cache.projection.buffer=null;cache.projection.close();
                if(projection.buffer!=null&&!projection.vertices.isEmpty())upload(projection);
            }
            cache.projection=projection;cache.pending=null;
        }
        if(projection==null||projection.vertices.isEmpty())return;
        if(projection.buffer==null)upload(projection);
        // 快速移动超过缓存余量时短暂等待新网格，不能用缺失遮挡物的旧网格制造穿墙光。
        if(source.squaredDistanceTo(projection.source)>4||direction.dotProduct(projection.direction)<.9994)return;
        updateShadow(projection,source,direction);
        Vec3d offset=projection.origin.subtract(cameraPosition),localSource=source.subtract(projection.origin);
        uniforms(new Matrix4f(worldView).translate((float)offset.x,(float)offset.y,(float)offset.z),0);
        shader.addSampler("LightDepth",projection.shadow.getDepthAttachment());
        shader.getUniform("LightMatrix").set(projection.shadowMatrix);
        shader.getUniform("LightOrigin").set((float)localSource.x,(float)localSource.y,(float)localSource.z);
        shader.getUniform("LightDirection").set((float)direction.x,(float)direction.y,(float)direction.z);
        shader.bind();projection.buffer.bind();projection.buffer.draw();
    }

    /**
     * 功能：从灯头实际位置沿云台方向绘制透视深度；不使用太阳方向或竖直投影。
     * 引用 net.minecraft.client.gl.SimpleFramebuffer 保存独立阴影；重用地表网格并按材质透明度裁切。
     * 端侧：渲染线程；结束时恢复世界帧缓冲、视口和合成状态，模式 4 不读取自身深度附件。
     */
    private static void updateShadow(Projection projection,Vec3d source,Vec3d direction){
        if(source.equals(projection.shadowSource)&&direction.equals(projection.shadowDirection))return;
        int read=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),draw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int depthFunction=GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC);
        int[] viewport=new int[4];GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);
        try{
            if(projection.shadow==null)projection.shadow=new SimpleFramebuffer(SHADOW_SIZE,SHADOW_SIZE,true,MinecraftClient.IS_SYSTEM_MAC);
            Vec3d local=source.subtract(projection.origin);
            // 灯头接近竖直时切换参考上方向，避免 lookAt 的叉积退化。
            Vec3d up=Math.abs(direction.y)>.98?new Vec3d(0,0,1):new Vec3d(0,1,0);
            Matrix4f lightView=new Matrix4f().lookAt((float)local.x,(float)local.y,(float)local.z,
                    (float)(local.x+direction.x),(float)(local.y+direction.y),(float)(local.z+direction.z),
                    (float)up.x,(float)up.y,(float)up.z);
            Matrix4f lightProjection=new Matrix4f().perspective((float)Math.toRadians(28),1,SHADOW_NEAR,(float)RANGE);
            projection.shadowMatrix=new Matrix4f(lightProjection).mul(lightView);
            projection.shadow.beginWrite(true);
            RenderSystem.disableBlend();RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11C.GL_LEQUAL);
            RenderSystem.depthMask(true);RenderSystem.colorMask(false,false,false,false);RenderSystem.disableCull();
            RenderSystem.clearDepth(1);RenderSystem.clear(GL11C.GL_DEPTH_BUFFER_BIT,MinecraftClient.IS_SYSTEM_MAC);
            uniforms(lightView,4);
            shader.getUniform("ProjMat").set(lightProjection);
            // 所有采样器都指向其他纹理，不能把当前写入的深度纹理绑定为输入。
            shader.addSampler("LightDepth",sceneSnapshot.getDepthAttachment());
            shader.bind();projection.buffer.bind();projection.buffer.draw();shader.unbind();
            projection.shadowSource=source;projection.shadowDirection=direction;
        }finally{
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,read);GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,draw);
            RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.colorMask(true,true,true,true);RenderSystem.depthMask(false);RenderSystem.disableDepthTest();
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.enableBlend();RenderSystem.blendFunc(GL11C.GL_ONE,GL11C.GL_ONE);
        }
    }

    /**
     * 功能：收集每个方块的全部外露面；侧面不依赖顶面射线命中，台阶等无方向模型面也只收集一次。
     * 引用 net.minecraft.block.Block#shouldDrawSide 沿用区块邻面剔除，BakedQuad 合并草侧面覆盖材质。
     */
    private static void bakeBlock(HelicopterEntity heli,Vec3d origin,BlockPos pos,BlockState state,
            Random random,List<LitVertex> vertices){
        var model=MinecraftClient.getInstance().getBlockRenderManager().getModel(state);
        long seed=state.getRenderingSeed(pos);Map<List<Vec3d>,LitFace> uniqueFaces=new HashMap<>();
        for(Direction side:Direction.values()){
            if(!net.minecraft.block.Block.shouldDrawSide(state,heli.getWorld(),pos,side,pos.offset(side)))continue;
            random.setSeed(seed);
            for(var quad:model.getQuads(state,side,random))bakeQuad(heli,pos,state,quad,uniqueFaces);
        }
        random.setSeed(seed);
        for(var quad:model.getQuads(state,null,random))bakeQuad(heli,pos,state,quad,uniqueFaces);
        for(var face:uniqueFaces.values()){
            int color=tint(state,pos,face.base),overlayColor=face.overlay==null?0:tint(state,pos,face.overlay);
            int[] data=face.base.getVertexData(),overlay=face.overlay==null?data:face.overlay.getVertexData();
            for(int i=0;i<4;i++){
                int base=i*BLOCK_STRIDE;
                vertices.add(new LitVertex(face.positions.get(i).subtract(origin),face.normal,
                        Float.intBitsToFloat(data[base+4]),Float.intBitsToFloat(data[base+5]),color,
                        Float.intBitsToFloat(overlay[base+4]),Float.intBitsToFloat(overlay[base+5]),overlayColor));
            }
        }
    }

    /** 功能：保留实际模型顶点并合并同面底图和覆盖层；光源遮挡交给透视深度，CPU 不再发射射线。 */
    private static void bakeQuad(HelicopterEntity heli,BlockPos pos,
            BlockState state,BakedQuad quad,Map<List<Vec3d>,LitFace> uniqueFaces){
        int[] data=quad.getVertexData();Vec3d modelOffset=state.getModelOffset(heli.getWorld(),pos);
        Vec3d[] positions=new Vec3d[4];
        for(int i=0;i<4;i++){
            int base=i*BLOCK_STRIDE;positions[i]=Vec3d.of(pos).add(modelOffset).add(
                    Float.intBitsToFloat(data[base]),Float.intBitsToFloat(data[base+1]),Float.intBitsToFloat(data[base+2]));
        }
        Vec3d normal=Vec3d.of(quad.getFace().getVector());
        List<Vec3d> key=List.of(positions);LitFace previous=uniqueFaces.get(key);
        if(previous!=null){
            if(quad.hasColor())uniqueFaces.put(key,new LitFace(key,normal,previous.base,quad));
            else if(previous.base.hasColor())uniqueFaces.put(key,new LitFace(key,normal,quad,previous.base));
            return;
        }
        uniqueFaces.put(key,new LitFace(key,normal,quad,null));
    }

    /** 功能：读取 net.minecraft.client.color.block.BlockColors 的原版群系着色；不包含环境光、AO 或阴影。 */
    private static int tint(BlockState state,BlockPos position,BakedQuad quad){
        var client=MinecraftClient.getInstance();
        return 0xff000000|(quad.hasColor()?client.getBlockColors().getColor(state,client.world,position,quad.getColorIndex()):0xffffff);
    }

    /** 功能：上传当前灯光缓存；BufferBuilder 自动兼容光影扩展格式，不逐帧重传顶点。 */
    private static void upload(Projection projection){
        try(BufferAllocator allocator=new BufferAllocator(BUILD_CAPACITY)){
            BufferBuilder builder=new BufferBuilder(allocator,VertexFormat.DrawMode.QUADS,VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
            MatrixStack matrices=new MatrixStack();for(var vertex:projection.vertices)emit(builder,matrices,vertex);
            BuiltBuffer built=builder.end();
            if(projection.buffer==null)projection.buffer=new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
            projection.buffer.bind();
            try{projection.buffer.upload(built);}finally{VertexBuffer.unbind();}
        }
    }

    /**
     * 功能：提交原始材质顶点；引用 VertexConsumer 保留实体格式，兼容 Iris 缓冲扩展。
     * UV1 为覆盖层图集坐标的 32767 定点数，UV2 为覆盖层 ARGB 的两个十六位分量；不采样光照贴图。
     */
    private static void emit(VertexConsumer buffer,MatrixStack matrices,LitVertex vertex){
        buffer.vertex(matrices.peek().getPositionMatrix(),(float)vertex.pos.x,(float)vertex.pos.y,(float)vertex.pos.z)
                .color(vertex.color).texture(vertex.u,vertex.v).overlay(Math.round(vertex.overlayU*32767),Math.round(vertex.overlayV*32767))
                .light(vertex.overlayColor&65535,vertex.overlayColor>>>16)
                .normal(matrices.peek(),(float)vertex.normal.x,(float)vertex.normal.y,(float)vertex.normal.z);
    }

    /** 功能：释放光斑网格显存；客户端资源/光影重载、断开和退出时调用。 */
    private static void clear(){for(var projection:CACHE.values())projection.close();CACHE.clear();prepared=false;}

    /** 功能：释放跨帧几何与屏幕快照；仅资源重载、断线和退出调用，不影响正常帧中的灯芯队列。 */
    private static void reset(){
        clear();GLOWS.clear();LABELS.clear();
        if(backlitText!=null){backlitText.close();backlitText=null;}
        if(sceneSnapshot!=null){sceneSnapshot.delete();sceneSnapshot=null;}
    }
}
