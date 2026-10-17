/** 文件：曲面直升机与实体驾驶舱渲染入口；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.TexturedRenderLayers;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.No_24_heli;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/**
 * 职责：组合实心、玻璃、旋翼和仪表渲染，第一人称保留真实机舱。
 * 引用：net.minecraft.client.render.entity.EntityRenderer 提供实体渲染入口；
 * net.minecraft.client.render.RenderLayer 为玻璃启用透明混合，为机壳保留普通光照。
 * 生命周期：客户端 EntityRendererRegistry 创建，每帧由实体分派器调用。
 * 端侧/线程：仅客户端渲染线程，姿态与动画仅读取已同步状态。
 * 排查：天空变黑查机窗中央是否被 alpha 丢弃；窗缘重复查整窗 UV；部件漂移查 push/pop。
 */
@Environment(EnvType.CLIENT)
public final class HelicopterRenderer extends EntityRenderer<HelicopterEntity> {
    /** 原版纯白纹理用于顶点着色，不依赖附加模型库。 */
    public static final Identifier TEXTURE = Identifier.of("minecraft", "textures/misc/white.png");
    /** 光滑漆面带独立 LabPBR 光滑度图；内饰与旋翼继续使用原哑光材质。 */
    private static final Identifier PAINT_SURFACE = No_24_heli.id("textures/entity/heli_paint.png");
    /** 原哑光微纹理与紫金编号分别用于舱内、旋翼和编号。 */
    private static final Identifier SURFACE = No_24_heli.id("textures/entity/heli_surface.png");
    private static final Identifier NUMBER = No_24_heli.id("textures/entity/heli_number_24.png");
    /** 机窗中心完全透明，仅窗缘保留可见材质，避免无透明实体通道的光影覆盖天空深度与法线。 */
    private static final Identifier GLASS_SPRITE = No_24_heli.id("block/heli_glass_edge");
    /** 网格只在初始化构建；仪表每帧读取当前实体状态。 */
    private final HelicopterModel model = new HelicopterModel();
    private final CockpitDashboardRenderer dashboard = new CockpitDashboardRenderer();
    /** 24 格外不绘制舱内细节和动态表盘，避免远处停机继续占用 CPU。 */
    private static final double CABIN_DETAIL_DISTANCE_SQUARED = 24 * 24;

    /**
     * 功能：初始化阴影；引用 MC API：net.minecraft.client.render.entity.EntityRendererFactory.Context 提供资源。
     * 端侧：客户端初始化；参数：context 为上下文；返回：无；副作用：设置 1.4 格阴影；排查：查客户端注册。
     */
    public HelicopterRenderer(EntityRendererFactory.Context context) { super(context); shadowRadius = 1.4f; }

    /**
     * 功能：将尾梁、旋翼与绳索纳入裁剪；引用 MC API：net.minecraft.client.render.Frustum#isVisible 判断可见性。
     * 端侧：渲染线程；参数：heli/frustum/x/y/z 为实体、视锥、相机相对坐标；返回：是否绘制；副作用：无。
     * 排查：尾翼出屏消失查六格扩张；绳索仍由 RopeRenderer 绘制。
     */
    @Override public boolean shouldRender(HelicopterEntity heli, Frustum frustum, double x, double y, double z) {
        return heli.shouldRender(x, y, z)
                && frustum.isVisible(heli.getBoundingBox().expand(6).stretch(0, -heli.ropeLength, 0));
    }

    /**
     * 功能：按共同姿态绘制机壳、实体仪表及透明玻璃；引用 MC API：
     * net.minecraft.client.util.math.MatrixStack 隔离部件变换，net.minecraft.client.render.RenderLayer 选择深度/混合路径。
     * 端侧：渲染线程；参数：heli/yaw/delta/matrices/consumers/light 为实体及帧绘制环境；返回：无。
     * 副作用：提交顶点；排查：机窗使用 alpha 丢弃，中央不能写入实体深度或光影材质缓冲。
     */
    @Override public void render(HelicopterEntity heli, float yaw, float delta, MatrixStack matrices,
            VertexConsumerProvider consumers, int light) {
        matrices.push(); matrices.multiply(HeliView.bodyRotation(heli, delta));
        MinecraftClient client = MinecraftClient.getInstance();
        Vec3d camera = client.gameRenderer.getCamera().getPos().subtract(heli.getLerpedPos(delta));
        boolean near = camera.lengthSquared() < CABIN_DETAIL_DISTANCE_SQUARED;
        boolean cockpit = client.player != null && client.player.getVehicle() == heli
                && client.options.getPerspective().isFirstPerson();
        var painted = consumers.getBuffer(RenderLayer.getEntitySolid(PAINT_SURFACE));
        model.render("shell", matrices, painted, light, heli.flight.paint, heli.flight.wreck);
        model.renderGear(matrices, painted, light, heli.flight.paint, heli.flight.wreck,
                MathHelper.lerp(delta, heli.previousGearExtension, heli.gearExtension));
        var solid = consumers.getBuffer(RenderLayer.getEntityCutout(SURFACE));
        if (near || cockpit) {
            model.render("interior", matrices, solid, light, heli.flight.paint, heli.flight.wreck);
        }
        float angle = MathHelper.lerp(delta, heli.previousRotorAngle, heli.rotorAngle);
        if (!heli.flight.wreck && heli.flight.rotor > 0) {
            matrices.push(); matrices.translate(0, 2.71, -.17);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(angle));
            model.render("rotor", matrices, solid, light, heli.flight.paint, false); matrices.pop();
        }
        if (!heli.flight.wreck && heli.flight.tail > 0) {
            matrices.push(); matrices.translate(.23, 1.84, -4.94);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(90));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(angle * 2));
            model.render("tail", matrices, solid, light, heli.flight.paint, false); matrices.pop();
        }
        if (heli.flight.paint == 5) model.render("number", matrices,
                consumers.getBuffer(RenderLayer.getEntityCutout(NUMBER)), light, heli.flight.paint, heli.flight.wreck);
        if (near || cockpit) model.render("lamp", matrices, consumers.getBuffer(heli.flight.wreck
                ? RenderLayer.getEntityCutout(TEXTURE) : RenderLayer.getEyes(TEXTURE)),
                heli.flight.wreck ? light : LightmapTextureManager.MAX_LIGHT_COORDINATE, heli.flight.paint, heli.flight.wreck);
        if (near || cockpit) dashboard.render(heli, delta, matrices, consumers, light);
        // 窗缘走原版裁切层，透明中央的片元被丢弃，不依赖光影对半透明实体的混合实现。
        var glassSprite = client.getSpriteAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE).apply(GLASS_SPRITE);
        model.render("glass", matrices, glassSprite.getTextureSpecificVertexConsumer(
                consumers.getBuffer(TexturedRenderLayers.getEntityCutout())),
                light, heli.flight.paint, heli.flight.wreck);
        matrices.pop(); super.render(heli, yaw, delta, matrices, consumers, light);
    }

    /**
     * 功能：返回着色底纹；引用 MC API：net.minecraft.util.Identifier 定位资源。
     * 端侧：渲染线程；参数：heli 为实体；返回：白纹理；副作用：无；排查：紫黑棋盘查资源路径。
     */
    @Override public Identifier getTexture(HelicopterEntity heli) { return TEXTURE; }
}
