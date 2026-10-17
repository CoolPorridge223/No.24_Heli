/** 文件：机腹探照灯遮挡采样、地形照明网格与自发光程序；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.joml.Quaternionf;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 职责：用有遮挡的锥形射线收集被照到的真实方块面，按原纹理、群系色和柔边强度重绘受光面。
 * 引用：net.minecraft.world.World#raycast 只读客户端已加载地形；BakedQuad 保留地面纹理及台阶形状。
 * 生命周期：运动时最多每两 tick 重采样，静止时每十 tick 检查；世界合成后、手持物前复用显存绘制。
 * 端侧/线程：仅客户端渲染线程；不放置光源方块、不重建区块或写服务端方块光照。
 * 排查：穿墙查射线首个命中和面中心复核；纹理错位查 BLOCK 顶点步长/模型偏移；重载查显存释放。
 */
@Environment(EnvType.CLIENT)
public final class SearchlightRenderer {
    /** 光源位于球体云台的前镜片，单位：机体局部格数。 */
    private static final Vec3d PIVOT=new Vec3d(0,.44,1.24);
    /** 最大射程 64 格，发光半角十二度，采样额外留两度缓冲；只处理相机 96 格内的灯。 */
    private static final double RANGE=64,VIEW_DISTANCE_SQUARED=96*96;
    private static final double EDGE_COS=Math.cos(Math.toRadians(12)),CORE_COS=Math.cos(Math.toRadians(4));
    private static final double SAMPLE_TANGENT=Math.tan(Math.toRadians(14));
    /** 方块顶点八整数；照明面沿法线外移六毫米防止与原地形深度争夺。 */
    private static final int BLOCK_STRIDE=8,BUILD_CAPACITY=256*1024;
    private static final double SURFACE_OFFSET=.006;
    /** 位置缓存随世界离开、灯具关闭或实体移除而释放，不按每帧分配缓冲。 */
    private static final Map<HelicopterEntity,Projection> CACHE=new HashMap<>();
    /** 原版无定向光局部坐标程序；光影继续使用其既有自发光实体程序。 */
    private static ShaderProgram shader;
    private static int revision=-1;
    /** 主视图矩阵与雾参数在世界阶段保存，稍后在原版清空手持物深度前使用；不沿用阴影相机。 */
    private static Matrix4f worldView,worldProjection;
    private static Vec3d cameraPosition;
    private static Frustum viewFrustum;
    private static float tickDelta,fogStart,fogEnd;
    private static float[] fogColor;
    private static boolean prepared;
    /** 射线命中的唯一方块面，避免同一块地面重复叠加增亮。 */
    private record Surface(BlockPos pos,Direction side){}
    /** 顶点保留真实图集 UV、纹理/群系颜色和光束衰减，兼容路径也复用同一几何。 */
    private record LitVertex(Vec3d pos,Vec3d normal,float u,float v,int color){}
    /** 世界固定锚点避免飞机运动时把地面纹理一并拖走；buffer 在首次绘制时创建。 */
    private static final class Projection {
        final Vec3d origin,source,direction;
        final long tick;
        final List<LitVertex> vertices;
        VertexBuffer buffer;
        /** 功能：保存一次只读采样结果；客户端渲染线程调用，显存惰性创建。 */
        Projection(Vec3d origin,Vec3d source,Vec3d direction,long tick,List<LitVertex> vertices){
            this.origin=origin;this.source=source;this.direction=direction;this.tick=tick;this.vertices=vertices;
        }
        /** 功能：关闭本灯的 GPU 缓冲；客户端渲染线程调用。 */
        void close(){if(buffer!=null)buffer.close();}
    }

    /** 私有构造：灯光缓存由客户端入口统一登记。 */
    private SearchlightRenderer(){}

    /** 功能：登记程序、重载和退出清理；引用 Fabric 客户端事件，不新增运行依赖。 */
    public static void register(){
        CoreShaderRegistrationCallback.EVENT.register(context->{
            clear();context.register(No_24_heli.id("searchlight"),VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL,
                    program->shader=program);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->clear());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client->clear());
        // 独立于机身视锥；这里只保存主视图，实际光斑须在光影完成世界合成后绘制。
        WorldRenderEvents.AFTER_ENTITIES.register(context->{
            HelicopterMesh.prepare();if(HelicopterMesh.isShadowPass())return;
            worldView=new Matrix4f(RenderSystem.getModelViewMatrix()).mul(context.matrixStack().peek().getPositionMatrix());
            worldProjection=new Matrix4f(context.projectionMatrix());cameraPosition=context.camera().getPos();viewFrustum=context.frustum();
            tickDelta=context.tickCounter().getTickDelta(false);fogStart=RenderSystem.getShaderFogStart();fogEnd=RenderSystem.getShaderFogEnd();
            fogColor=RenderSystem.getShaderFogColor().clone();prepared=true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client->CACHE.entrySet().removeIf(entry->{
            var heli=entry.getKey();
            boolean discard=heli.isRemoved()||heli.getWorld()!=client.world||!heli.flight.searchlight||heli.flight.wreck;
            if(discard)entry.getValue().close();return discard;
        }));
    }

    /** 功能：返回无定向光程序，供小灯与探照灯共用正确雾距；仅客户端渲染线程。 */
    static ShaderProgram shader(){return shader;}

    /** 功能：取得机身局部云台旋转，与模型和光束共用；角度为服务器状态的客户端插值。 */
    static Quaternionf gimbal(HelicopterEntity heli,float delta){
        return new Quaternionf().rotationY((float)Math.toRadians(-MathHelper.lerp(delta,heli.previousLightYaw,heli.visualLightYaw)))
                .rotateX((float)Math.toRadians(-MathHelper.lerp(delta,heli.previousLightPitch,heli.visualLightPitch)));
    }

    /**
     * 功能：在世界合成后、手持物深度清空前叠加照明，保留实际世界深度遮挡。
     * 引用：MinecraftClient#getFramebuffer 取得最终画面；固定自有程序，避免部分光影忽略实体顶点 alpha。
     * 端侧：客户端渲染线程，由 SearchlightCompositeMixin 调用；不改光影包、不复制全屏深度或重绘区块。
     */
    public static void composite(){
        if(!prepared)return;prepared=false;var client=MinecraftClient.getInstance();
        if(client.world==null)return;
        if(revision!=HelicopterMesh.revision()){clear();revision=HelicopterMesh.revision();}
        client.getFramebuffer().beginWrite(false);
        for(var entity:client.world.getEntities())if(entity instanceof HelicopterEntity heli&&heli.flight.searchlight&&!heli.flight.wreck)
            render(heli,tickDelta,viewFrustum);
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
        long tick=heli.getWorld().getTime();Projection projection=CACHE.get(heli);
        if(projection==null||tick-projection.tick>=10||tick-projection.tick>=2&&
                (source.squaredDistanceTo(projection.source)>.0025||direction.dotProduct(projection.direction)<.99999)){
            if(projection!=null)projection.close();
            projection=bake(heli,origin,source,direction,tick);CACHE.put(heli,projection);
        }
        if(projection.vertices.isEmpty())return;
        var layer=RenderLayer.getEntityTranslucentEmissive(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        if(projection.buffer==null)upload(projection,layer);
        layer.startDrawing();RenderSystem.depthMask(false);
        RenderSystem.enablePolygonOffset();RenderSystem.polygonOffset(-1,-1);
        try{
            Vec3d offset=projection.origin.subtract(cameraPosition);
            shader.initializeUniforms(VertexFormat.DrawMode.QUADS,new Matrix4f(worldView).translate((float)offset.x,(float)offset.y,(float)offset.z),worldProjection,client.getWindow());
            shader.getUniform("FogStart").set(fogStart);shader.getUniform("FogEnd").set(fogEnd);
            shader.getUniform("FogColor").set(fogColor[0],fogColor[1],fogColor[2],fogColor[3]);
            shader.bind();projection.buffer.bind();projection.buffer.draw();
        }finally{
            VertexBuffer.unbind();shader.unbind();RenderSystem.disablePolygonOffset();RenderSystem.depthMask(true);layer.endDrawing();
        }
    }

    /** 功能：沿锥形光束收集首个命中面；客户端只读射线，不读取或重建服务端地形。 */
    private static Projection bake(HelicopterEntity heli,Vec3d origin,Vec3d source,Vec3d direction,long tick){
        var center=ray(heli,source,source.add(direction.multiply(RANGE)));
        double distance=center.getType()==HitResult.Type.MISS?RANGE:center.getPos().distanceTo(source);
        // 根据实际照射距离自适应采样，一格以内取一个候选，最高 32×32；完整方块面填补采样间隙。
        int steps=Math.clamp((int)Math.ceil(distance*SAMPLE_TANGENT),3,16);
        Vec3d right=direction.crossProduct(new Vec3d(0,1,0)).normalize(),up=right.crossProduct(direction).normalize();
        Set<Surface> surfaces=new HashSet<>();
        for(int x=-steps;x<=steps;x++)for(int y=-steps;y<=steps;y++){
            if(x*x+y*y>steps*steps)continue;
            Vec3d cast=direction.add(right.multiply(x*SAMPLE_TANGENT/steps)).add(up.multiply(y*SAMPLE_TANGENT/steps)).normalize();
            var hit=ray(heli,source,source.add(cast.multiply(RANGE)));
            if(hit.getType()==HitResult.Type.BLOCK)surfaces.add(new Surface(hit.getBlockPos().toImmutable(),hit.getSide()));
        }
        // 沿命中面的平面补邻格，再逐面复核遮挡，避免斜向采样恰好漏掉光斑中央的一格。
        for(var surface:List.copyOf(surfaces))for(var adjacent:Direction.values())
            if(adjacent.getAxis()!=surface.side.getAxis())surfaces.add(new Surface(surface.pos.offset(adjacent),surface.side));
        List<LitVertex> vertices=new ArrayList<>();var client=MinecraftClient.getInstance();Random random=Random.create(0);
        for(var surface:surfaces){
            BlockState state=heli.getWorld().getBlockState(surface.pos);var model=client.getBlockRenderManager().getModel(state);
            random.setSeed(0);List<BakedQuad> quads=new ArrayList<>(model.getQuads(state,surface.side,random));
            random.setSeed(0);for(var quad:model.getQuads(state,null,random))if(quad.getFace()==surface.side)quads.add(quad);
            for(var quad:quads)bakeQuad(heli,origin,source,direction,surface,state,quad,vertices);
        }
        return new Projection(origin,source,direction,tick,List.copyOf(vertices));
    }

    /** 功能：用实际碰撞表面检查遮挡；引用 RaycastContext 的首个方块命中，流体不作为地面投影。 */
    private static BlockHitResult ray(HelicopterEntity heli,Vec3d start,Vec3d end){
        return heli.getWorld().raycast(new RaycastContext(start,end,RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,heli));
    }

    /**
     * 功能：保留方块面 UV、群系色与方向明暗，在四顶点写入光束柔边和距离衰减。
     * 引用 BakedQuad 的 BLOCK 格式；面中心再次检查遮挡，避免台阶背面或墙后的面被一起照亮。
     */
    private static void bakeQuad(HelicopterEntity heli,Vec3d origin,Vec3d source,Vec3d direction,Surface surface,
            BlockState state,BakedQuad quad,List<LitVertex> output){
        int[] data=quad.getVertexData();Vec3d modelOffset=state.getModelOffset(heli.getWorld(),surface.pos);
        Vec3d[] positions=new Vec3d[4];Vec3d center=Vec3d.ZERO;
        for(int i=0;i<4;i++){
            int base=i*BLOCK_STRIDE;positions[i]=Vec3d.of(surface.pos).add(modelOffset).add(
                    Float.intBitsToFloat(data[base]),Float.intBitsToFloat(data[base+1]),Float.intBitsToFloat(data[base+2]));
            center=center.add(positions[i].multiply(.25));
        }
        Vec3d normal=Vec3d.of(quad.getFace().getVector());
        var check=ray(heli,source,center.subtract(normal.multiply(.02)));
        if(check.getType()!=HitResult.Type.BLOCK||!check.getBlockPos().equals(surface.pos)||check.getPos().squaredDistanceTo(center)>.01)return;
        int tint=quad.hasColor()?MinecraftClient.getInstance().getBlockColors().getColor(state,heli.getWorld(),surface.pos,quad.getColorIndex()):0xffffff;
        float shade=heli.getWorld().getBrightness(quad.getFace(),quad.hasShade());
        // 近处光斑可能小于一格，四个原始角点会全落在光束外；按光斑尺度细分，不移动地面纹理。
        double size=Math.max(positions[0].distanceTo(positions[1]),positions[0].distanceTo(positions[3]));
        int divisions=Math.clamp((int)Math.ceil(size/Math.max(.08,source.distanceTo(center)*Math.tan(Math.toRadians(12))/4)),1,12);
        LitVertex[][] grid=new LitVertex[divisions+1][divisions+1];
        for(int row=0;row<=divisions;row++)for(int column=0;column<=divisions;column++){
            double u=column/(double)divisions,v=row/(double)divisions;
            double[] weights={(1-u)*(1-v),u*(1-v),u*v,(1-u)*v};
            Vec3d point=Vec3d.ZERO;float texU=0,texV=0,red=0,green=0,blue=0;
            for(int i=0;i<4;i++){
                point=point.add(positions[i].multiply(weights[i]));int base=i*BLOCK_STRIDE,original=data[base+3];
                texU+=(float)(Float.intBitsToFloat(data[base+4])*weights[i]);texV+=(float)(Float.intBitsToFloat(data[base+5])*weights[i]);
                red+=(original&255)*weights[i];green+=(original>>8&255)*weights[i];blue+=(original>>16&255)*weights[i];
            }
            Vec3d relative=point.subtract(source);double length=relative.length();
            double blend=MathHelper.clamp((relative.dotProduct(direction)/Math.max(.001,length)-EDGE_COS)/(CORE_COS-EDGE_COS),0,1);
            double alpha=blend*blend*(3-2*blend)*Math.max(0,1-length*length/(RANGE*RANGE))*.95;
            int color=(int)(alpha*255)<<24|(int)(red*(tint>>16&255)/255*shade)<<16|
                    (int)(green*(tint>>8&255)/255*shade*.97f)<<8|(int)(blue*(tint&255)/255*shade*.85f);
            grid[row][column]=new LitVertex(point.add(normal.multiply(SURFACE_OFFSET)).subtract(origin),normal,texU,texV,color);
        }
        for(int row=0;row<divisions;row++)for(int column=0;column<divisions;column++){
            var a=grid[row][column];var b=grid[row][column+1];var c=grid[row+1][column+1];var d=grid[row+1][column];
            if((a.color>>>24)<=2&&(b.color>>>24)<=2&&(c.color>>>24)<=2&&(d.color>>>24)<=2)continue;
            output.add(a);output.add(b);output.add(c);output.add(d);
        }
    }

    /** 功能：上传当前灯光缓存；BufferBuilder 自动兼容光影扩展格式，不逐帧重传顶点。 */
    private static void upload(Projection projection,RenderLayer layer){
        try(BufferAllocator allocator=new BufferAllocator(BUILD_CAPACITY)){
            BufferBuilder builder=new BufferBuilder(allocator,VertexFormat.DrawMode.QUADS,layer.getVertexFormat());
            MatrixStack matrices=new MatrixStack();for(var vertex:projection.vertices)emit(builder,matrices,vertex);
            BuiltBuffer built=builder.end();projection.buffer=new VertexBuffer(VertexBuffer.Usage.STATIC);projection.buffer.bind();
            try{projection.buffer.upload(built);}finally{VertexBuffer.unbind();}
        }
    }

    /** 功能：提交一个带光斑强度的地形顶点；客户端调用，兼容路径与显存上传共用，不漏 overlay/light。 */
    private static void emit(VertexConsumer buffer,MatrixStack matrices,LitVertex vertex){
        buffer.vertex(matrices.peek().getPositionMatrix(),(float)vertex.pos.x,(float)vertex.pos.y,(float)vertex.pos.z)
                .color(vertex.color).texture(vertex.u,vertex.v).overlay(OverlayTexture.DEFAULT_UV)
                .light(LightmapTextureManager.MAX_LIGHT_COORDINATE)
                .normal(matrices.peek(),(float)vertex.normal.x,(float)vertex.normal.y,(float)vertex.normal.z);
    }

    /** 功能：释放全部灯光显存；客户端资源/光影重载、断开和退出时调用。 */
    private static void clear(){for(var projection:CACHE.values())projection.close();CACHE.clear();prepared=false;}
}
