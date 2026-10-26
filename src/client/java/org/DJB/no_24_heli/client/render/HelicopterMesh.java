/** 文件：直升机静态网格的显存缓存与实体图层绘制；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import org.DJB.no_24_heli.No_24_heli;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30C;

/**
 * 职责：同一部件、涂装和细节级别只上传一次，后续每架每帧仅设置矩阵、光照并绘制。
 * 引用：net.minecraft.client.gl.VertexBuffer 保存静态显存；net.minecraft.client.render.RenderLayer 保留材质状态。
 * 生命周期：客户端入口注册；实体渲染期惰性上传；资源/光影重载及客户端退出时释放。
 * 端侧/线程：仅客户端渲染线程；不读取或改写服务端物理。
 * 排查：光照不随位置变化查 UV2 常量属性；光影材质异常查实体阶段、扩展顶点格式与缓存失效。
 */
@Environment(EnvType.CLIENT)
public final class HelicopterMesh {
    /** 预留扩展实体格式空间，避免首次生成大网格时频繁扩容；单位：字节。 */
    private static final int BUILD_CAPACITY = 4 * 1024 * 1024;
    /** 内饰原有舱灯衰减范围，逐组绘制以保留不同面的最低块光。 */
    private static final int CABIN_LIGHT_MIN = 8, CABIN_LIGHT_MAX = 14;
    /** 所有飞机共享缓存，不按实体 UUID 分配显存。 */
    private static final Map<Key, List<Segment>> CACHE = new HashMap<>();
    /** 原版路径使用支持局部模型矩阵的着色器；Iris 启用光影时继续使用其实体着色器。 */
    private static ShaderProgram shader;
    /** 光影管线对象变化时失效，防止沿用上一个光影包的实体材质编号和顶点格式。 */
    private static Object pipeline;
    /** Photon 与 Kappa 将场景深度存于纹理左下缩放区域；仅光影管线更换时读取实际选项。 */
    private static float depthScale=1;
    /** 可选 Iris 边界只解析一次，不新增运行依赖；方法句柄不参与逐顶点循环。 */
    private static MethodHandle irisActive, irisPipeline, irisBegin, irisEnd, irisShadow;
    /** 跨模组接口不兼容时保留原实体提交路径，避免影响游戏启动。 */
    private static boolean compatible = true;
    /** 网格格式或资源变化时递增，供停机坪共享缓存同步释放旧显存；仅渲染线程使用。 */
    private static int revision;

    /** 同一原型的缓存键；wreck 单独预烘焙暗色，不逐帧修改整片颜色。 */
    private record Key(String part, int paint, boolean wreck, boolean distant) {}
    /** 光照坐标由常量属性逐次更新；内饰分组保留原有最低块光。 */
    static record Segment(VertexBuffer buffer, int lightAttribute, int minimumBlockLight) {}

    /** 私有构造：本类仅管理共享缓存与渲染线程生命周期。 */
    private HelicopterMesh() {}

    /**
     * 功能：注册着色器和显存清理；引用 net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback
     * 随资源重载替换程序，ClientLifecycleEvents 在退出时释放显存；仅客户端初始化调用一次。
     */
    public static void register() {
        CoreShaderRegistrationCallback.EVENT.register(context -> {
            clear();
            context.register(No_24_heli.id("helicopter_mesh"),
                    VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, program -> shader = program);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clear());
        if (FabricLoader.getInstance().isModLoaded("iris")) initializeIris();
    }

    /**
     * 功能：解析可选光影边界；引用 net.fabricmc.loader.api.FabricLoader 仅用于确认模组存在。
     * 端侧：客户端初始化；副作用：缓存 Iris API 与实体阶段句柄；排查：接口变化会记录原因并回退实体路径。
     */
    private static void initializeIris() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = api.getMethod("getInstance").invoke(null);
            irisActive = lookup.unreflect(api.getMethod("isShaderPackInUse")).bindTo(instance);
            irisShadow = lookup.unreflect(api.getMethod("isRenderingShadowPass")).bindTo(instance);
            Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
            irisPipeline = lookup.unreflect(manager.getClass().getMethod("getPipelineNullable"))
                    .bindTo(manager).asType(MethodType.methodType(Object.class));
            Class<?> phases = Class.forName("net.irisshaders.iris.layer.GbufferPrograms");
            irisBegin = lookup.findStatic(phases, "beginEntities", MethodType.methodType(void.class));
            irisEnd = lookup.findStatic(phases, "endEntities", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException exception) {
            compatible = false;
            No_24_heli.LOGGER.warn("Iris 接口与静态网格路径不兼容，继续使用实体顶点提交", exception);
        }
    }

    /**
     * 功能：进入本架飞机的静态绘制阶段；端侧：渲染线程；返回：是否可使用 GPU 缓存。
     * 引用 MC API：RenderSystem 检查线程；副作用：检测光影切换并设置实体阶段，必须与 end 成对。
     */
    static boolean begin() {
        if (!prepare()) return false;
        try {
            if (pipeline != null) irisBegin.invokeExact();
            return true;
        } catch (Throwable exception) {
            throw new IllegalStateException("无法进入直升机光影实体绘制阶段", exception);
        }
    }

    /** 功能：检查资源与可选光影格式；客户端渲染线程调用，不改变当前实体或方块实体阶段。 */
    static boolean prepare() {
        RenderSystem.assertOnRenderThread();
        if (!compatible) return false;
        try {
            Object current = irisActive != null && (boolean) irisActive.invokeExact()
                    ? (Object) irisPipeline.invokeExact() : null;
            if (pipeline != current) { clear(); pipeline = current; depthScale=readDepthScale(); }
            return true;
        } catch (Throwable exception) {
            throw new IllegalStateException("无法进入直升机光影实体绘制阶段", exception);
        }
    }

    /** 功能：读取共享网格资源代数；客户端渲染线程调用，用于失效其他静态显存缓存。 */
    static int revision() { return revision; }

    /** 功能：返回光影实际的深度分辨率比例；客户端渲染线程调用，原版为 1。 */
    static float depthScale(){return depthScale;}

    /**
     * 功能：读取 Photon 的 TAAU/TAAU_RENDER_SCALE 或 Kappa 的 ResolutionScale，匹配低分辨率深度区域。
     * 引用 Iris 的可选 OptionValues 接口；仅管线重载调用，不读写用户配置或新增依赖。
     * 排查：其他光影没有该选项时使用原尺寸；接口不兼容记录原因并保留原尺寸。
     */
    private static float readDepthScale(){
        if(pipeline==null)return 1;
        try{
            var pack=(java.util.Optional<?>)Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack").invoke(null);
            if(pack.isEmpty())return 1;
            Object options=pack.get().getClass().getMethod("getShaderPackOptions").invoke(pack.get());
            Object optionSet=options.getClass().getMethod("getOptionSet").invoke(options);
            var strings=(java.util.Map<?,?>)optionSet.getClass().getMethod("getStringOptions").invoke(optionSet);
            Object values=options.getClass().getMethod("getOptionValues").invoke(options);
            Class<?> valueType=Class.forName("net.irisshaders.iris.shaderpack.option.values.OptionValues");
            String scale;
            if(strings.containsKey("ResolutionScale")){
                // Kappa 即使未修改选项也默认以 0.75 渲染；独立于 TAA 开关，不能退回全尺寸深度。
                scale=(String)valueType.getMethod("getStringValueOrDefault",String.class).invoke(values,"ResolutionScale");
            }else{
                if(!(boolean)optionSet.getClass().getMethod("isBooleanOption",String.class).invoke(optionSet,"TAAU")
                        ||!strings.containsKey("TAAU_RENDER_SCALE"))return 1;
                if((boolean)optionSet.getClass().getMethod("isBooleanOption",String.class).invoke(optionSet,"TAA")
                        &&!(boolean)valueType.getMethod("getBooleanValueOrDefault",String.class).invoke(values,"TAA"))return 1;
                if(!(boolean)valueType.getMethod("getBooleanValueOrDefault",String.class).invoke(values,"TAAU"))return 1;
                scale=(String)valueType.getMethod("getStringValueOrDefault",String.class).invoke(values,"TAAU_RENDER_SCALE");
            }
            float parsed=Float.parseFloat(scale);
            return Float.isFinite(parsed)?net.minecraft.util.math.MathHelper.clamp(parsed,.25f,1):1;
        }catch(ReflectiveOperationException|NumberFormatException exception){
            No_24_heli.LOGGER.warn("无法读取光影深度比例，使用原尺寸深度",exception);return 1;
        }
    }

    /** 功能：还原可选光影实体阶段；仅渲染线程，与 begin 成对；不改变后续玻璃和仪表的实体图层。 */
    static void end() {
        if (pipeline != null) {
            try { irisEnd.invokeExact(); }
            catch (Throwable exception) { throw new IllegalStateException("无法结束直升机光影实体绘制阶段", exception); }
        }
    }

    /** 功能：识别可选光影的阴影遍历；仅渲染线程；阴影仍绘制机体，但不更新被机舱遮挡的动态仪表。 */
    static boolean isShadowPass() {
        if (pipeline == null) return false;
        try { return (boolean) irisShadow.invokeExact(); }
        catch (Throwable exception) { throw new IllegalStateException("无法读取光影阴影阶段", exception); }
    }

    /**
     * 功能：绘制显存中的部件；引用 net.minecraft.client.render.RenderLayer 成对启停材质，VertexBuffer 绘制。
     * 端侧：渲染线程；参数：model/part/paint/wreck/distant 为原型及外观，其余为矩阵、图层和动态光照。
     * 副作用：首次上传网格，后续不遍历模型顶点；排查：UV2 禁用数组后必须每次设置当前环境光。
     */
    static void draw(HelicopterModel model, String part, int paint, boolean wreck, boolean distant,
            MatrixStack matrices, RenderLayer layer, int light) {
        draw(mesh(model,part,paint,wreck,distant), matrices, layer, light, true);
    }

    /** 功能：返回同外观部件的共享显存分组；仅渲染线程，第一次可见时上传。 */
    static List<Segment> mesh(HelicopterModel model,String part,int paint,boolean wreck,boolean distant){
        Key key = new Key(part, paint, wreck, distant);
        List<Segment> segments = CACHE.get(key);
        if (segments == null) {
            segments = upload(model, part, paint, wreck);
            CACHE.put(key, segments);
        }
        return segments;
    }

    /**
     * 功能：绘制静态缓冲分组；供飞机和停机坪共用相同矩阵、动态光照与资源状态处理。
     * 端侧：客户端渲染线程；directional 为受光实体，false 沿用图层原程序（夜间信标）。
     * 引用 MC API：RenderLayer 成对启停，VertexBuffer 不重传顶点；不改变 Iris 当前绘制阶段。
     */
    static void draw(List<Segment> segments, MatrixStack matrices, RenderLayer layer, int light, boolean directional) {
        layer.startDrawing();
        try {
            ShaderProgram program = pipeline == null && directional ? shader : RenderSystem.getShader();
            Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(matrices.peek().getPositionMatrix());
            program.initializeUniforms(VertexFormat.DrawMode.QUADS, modelView, RenderSystem.getProjectionMatrix(),
                    net.minecraft.client.MinecraftClient.getInstance().getWindow());
            if (pipeline == null && directional) program.getUniform("NormalMat").set(matrices.peek().getNormalMatrix());
            program.bind();
            try {
                for (Segment segment : segments) {
                    segment.buffer.bind();
                    if (segment.lightAttribute >= 0) GL30C.glVertexAttribI2i(segment.lightAttribute,
                            Math.max(light & 0xffff, segment.minimumBlockLight << 4), light >>> 16);
                    segment.buffer.draw();
                }
            } finally {
                VertexBuffer.unbind();
                program.unbind();
            }
        } finally {
            layer.endDrawing();
        }
    }

    /**
     * 功能：按原顶点格式生成静态显存；引用 net.minecraft.client.render.BufferBuilder 让 Iris 正常补齐切线与材质。
     * 端侧：首次可见时的渲染线程；返回：部件光照分组；副作用：创建 VBO，临时分配器立即关闭。
     * 排查：禁止写死扩展格式步长或绕过 BufferBuilder，否则 PBR 切线和实体编号会缺失。
     */
    private static List<Segment> upload(HelicopterModel model, String part, int paint, boolean wreck) {
        List<Segment> result = new ArrayList<>();
        boolean cabin = part.endsWith("interior") && !wreck;
        for (int floor = cabin ? CABIN_LIGHT_MIN : -1; floor <= (cabin ? CABIN_LIGHT_MAX : -1); floor++) {
            try (BufferAllocator allocator = new BufferAllocator(BUILD_CAPACITY)) {
                BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS,
                        VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
                model.bake(part, builder, paint, wreck, floor);
                BuiltBuffer built = builder.endNullable();
                if (built == null) continue;
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                try {
                    buffer.upload(built);
                    int attribute = buffer.getVertexFormat().getElements().indexOf(VertexFormatElement.UV_2);
                    GlStateManager._disableVertexAttribArray(attribute);
                    result.add(new Segment(buffer, attribute, Math.max(0, floor)));
                } finally {
                    VertexBuffer.unbind();
                }
            }
        }
        return result;
    }

    /** 功能：释放所有共享显存；引用 net.minecraft.client.gl.VertexBuffer#close 删除缓冲；仅渲染线程，不持有世界引用。 */
    private static void clear() {
        for (List<Segment> segments : CACHE.values()) for (Segment segment : segments) segment.buffer.close();
        CACHE.clear();
        revision++;
    }
}
