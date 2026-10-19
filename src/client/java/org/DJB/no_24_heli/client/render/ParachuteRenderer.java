/** 文件：充压滑翔伞布面、气室和吊绳；端侧：CLIENT；实体：no_24_heli:parachute。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.ParachuteEntity;
import org.DJB.no_24_heli.flight.ParachuteCanopy;
import org.DJB.no_24_heli.flight.ParachuteSuspension;


import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.OutlineVertexConsumerProvider;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexConsumers;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

/**
 * 职责：绘制有厚度和十二个气室的弧形滑翔翼，不使用固定圆形伞模型。
 * 引用：net.minecraft.client.render.VertexConsumer 提交双面织物；MatrixStack 保持坐标变换隔离。
 * 生命周期：Fabric 实体渲染注册创建，弱键缓存每具伞自己的布面，实体释放后可回收。
 * 端侧/线程：客户端渲染线程；玩家和背包由原版/玩家特征绘制，帧率不影响玩家物理。
 * 排查：吊绳错位查局部节点和同一横滚矩阵，伞翼出屏消失查扩展后的裁剪包围盒。
 */
@Environment(EnvType.CLIENT)
public final class ParachuteRenderer extends EntityRenderer<ParachuteEntity> {
    /** 每具伞独立的视觉布面，无实体强引用，避免退出世界后缓存残留。 */
    private final Map<ParachuteEntity, ParachuteCloth> cloth = new WeakHashMap<>();
    /** 单根吊绳厚 0.0125 格，十二根落地绳逐段使用服务端柔性节点，主绳和拉手按比例加粗。 */
    private final ModelPart cord;
    /** 织物资源为细格防撕裂布，无自发光，接受环境和光影光照。 */
    private static final Identifier FABRIC = No_24_heli.id("textures/entity/parachute_fabric.png");
    /**
     * 织物显式提交三角面，保留平滑法线及光影切线；上下层封闭后统一剔除背面。
     * net.minecraft.client.render.RenderLayer 沿用原版实体裁切程序、光照、覆盖层和描边。
     */
    private static final RenderLayer FABRIC_LAYER = RenderLayer.of("no_24_heli_parachute_fabric",
            VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL, VertexFormat.DrawMode.TRIANGLES,
            RenderLayer.DEFAULT_BUFFER_SIZE, true, false, RenderLayer.MultiPhaseParameters.builder()
                    .program(RenderPhase.ENTITY_CUTOUT_PROGRAM)
                    .texture(new RenderPhase.Texture(FABRIC, false, false))
                    .lightmap(RenderPhase.ENABLE_LIGHTMAP).overlay(RenderPhase.ENABLE_OVERLAY_COLOR).build(false));
    /** 原版自动描边固定用四边形，织物需匹配三角形拓扑，避免发光实体描边错连顶点。 */
    private static final RenderLayer FABRIC_OUTLINE_LAYER = RenderLayer.of("no_24_heli_parachute_outline",
            VertexFormats.POSITION_TEXTURE_COLOR, VertexFormat.DrawMode.TRIANGLES, RenderLayer.DEFAULT_BUFFER_SIZE,
            RenderLayer.MultiPhaseParameters.builder().program(RenderPhase.OUTLINE_PROGRAM)
                    .texture(new RenderPhase.Texture(FABRIC, false, false)).depthTest(RenderPhase.ALWAYS_DEPTH_TEST)
                    .target(RenderPhase.OUTLINE_TARGET).build(RenderLayer.OutlineMode.IS_OUTLINE));
    /** 细分翻倍后织纹仍横向重复六次、弦向两次，保持原来的防撕裂布纹尺寸。 */
    private static final float SPAN_UV = 6f / ParachuteCloth.SPAN, CHORD_UV = 2f / ParachuteCloth.CHORD;
    /** 左右翼尖统一为紫色，橙色主翼和米白中心条保持原宽度。 */
    private static final int TIP_COLOR = 0xff8547b8;
    /** 操纵柄使用暖黄色，区别于承重绳；半宽 15.5cm，让握柄两端露在原版方块手掌之外。 */
    private static final int HANDLE_COLOR = 0xffefb847;
    private static final double HANDLE_HALF_WIDTH = .155;

    /** net.minecraft.client.model.ModelPart 构建吊绳棱柱，展开翼面由动态节点构建。 */
    public ParachuteRenderer(EntityRendererFactory.Context context) {
        super(context); ModelData data = new ModelData();
        data.getRoot().addChild("cord", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-.1f, 0, -.1f, .2f, 16, .2f), ModelTransform.NONE);
        cord = TexturedModelData.of(data, 32, 32).createModel().getChild("cord");
    }

    /** net.minecraft.client.render.Frustum 包含头顶翼面及最长拖行吊绳，人物出屏不提前裁掉布面。 */
    @Override public boolean shouldRender(ParachuteEntity entity, Frustum frustum, double x, double y, double z) {
        return entity.shouldRender(x, y, z) && frustum.isVisible(entity.getBoundingBox().expand(7, 6, 7));
    }

    /** 活翼共用人物旋转，失压翼使用世界节点；封闭表面和吊绳的矩阵成对恢复。 */
    @Override public void render(ParachuteEntity entity, float yaw, float delta, MatrixStack matrices,
            VertexConsumerProvider consumers, int light) {
        boolean loose = entity.mode() != ParachuteEntity.ACTIVE;
        ParachuteCloth mesh = cloth.computeIfAbsent(entity, ignored -> new ParachuteCloth());
        mesh.prepare(entity, delta);
        double opening = loose ? 0 : entity.inflation(delta);
        matrices.push();
        if (!loose) {
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entity.canopyYaw(delta)));
            ParachutePose.apply(matrices, entity, delta, false);
        }
        VertexConsumer buffer = consumers.getBuffer(FABRIC_LAYER);
        if (consumers instanceof OutlineVertexConsumerProvider)
            buffer = VertexConsumers.union(consumers.getBuffer(FABRIC_OUTLINE_LAYER), buffer);
        for (int x = 0; x < ParachuteCloth.SPAN; x++) for (int z = 0; z < ParachuteCloth.CHORD; z++) {
            if (!hasCell(entity, x, z)) continue;
            Vec3d a = mesh.surfacePoint(x, z, false), b = mesh.surfacePoint(x + 1, z, false);
            Vec3d c = mesh.surfacePoint(x + 1, z + 1, false), d = mesh.surfacePoint(x, z + 1, false);
            Vec3d lowA = mesh.surfacePoint(x, z, true), lowB = mesh.surfacePoint(x + 1, z, true);
            Vec3d lowC = mesh.surfacePoint(x + 1, z + 1, true), lowD = mesh.surfacePoint(x, z + 1, true);
            int stripe = x * 24 / ParachuteCloth.SPAN;
            int color = stripe < 3 || stripe >= 21 ? TIP_COLOR : stripe >= 10 && stripe < 14 ? 0xfff2ead3 : 0xffe99539;
            // 世界局部 +Y 朝上；顶底绕序相反，法线必须朝向布面外侧以接受正确光照。
            fabricSurface(matrices, buffer, mesh, a, b, c, d, false, color, light, x, z);
            fabricSurface(matrices, buffer, mesh, lowA, lowB, lowC, lowD, true, 0xffe4e2d6, light, x, z);
            // 四周和破口都封边，斜视时仍有实体厚度，不是两张互相悬空的面。
            if (!hasCell(entity, x, z - 1)) surface(matrices, buffer, a, b, lowB, lowA, color, light, x, z);
            if (!hasCell(entity, x + 1, z)) surface(matrices, buffer, b, c, lowC, lowB, color, light, x, z);
            if (!hasCell(entity, x, z + 1)) surface(matrices, buffer, c, d, lowD, lowC, color, light, x, z);
            if (!hasCell(entity, x - 1, z)) surface(matrices, buffer, d, a, lowA, lowD, color, light, x, z);
        }
        int chamber = ParachuteCloth.SPAN / 12;
        for (int x = 0; x <= ParachuteCloth.SPAN; x += chamber) {
            Vec3d leading = mesh.point(x, ParachuteCloth.CHORD);
            // 前缘暗色进气口和侧向肋片形成冲压翼气室，而非一张没有厚度的板。
            if (!loose && x < ParachuteCloth.SPAN) {
                Vec3d next = mesh.point(x + chamber, ParachuteCloth.CHORD);
                surface(matrices, buffer, leading.add(0, -.13 * opening, -.04),
                        next.add(0, -.13 * opening, -.04), next, leading, 0xff5d5341, light, x, 0);
            }
        }
        // ModelPart 提交四边形；整张布绘制完后再切换图层，不能混入三角形缓冲。
        VertexConsumer cordBuffer = consumers.getBuffer(RenderLayer.getEntityCutout(FABRIC));
        if (!loose) drawFlightRig(entity, mesh, matrices, cordBuffer, delta, light);
        if (entity.mode() == ParachuteEntity.TRAILING && entity.hasLines())
            drawTrailingLines(entity, mesh, matrices, cordBuffer, delta, light);
        if (!loose) ParachuteHandsRenderer.render(entity, delta, matrices, consumers, light);
        matrices.pop(); super.render(entity, yaw, delta, matrices, consumers, light);
    }

    /**
     * 每侧三组双绳先汇合，再由粗主绳绕肩进入背包；操纵柄由汇合点下垂，独立于承力主绳。
     * net.minecraft.client.model.ModelPart 沿实际节点绘制，所有节点共用外层人伞倾斜矩阵。
     */
    private void drawFlightRig(ParachuteEntity entity, ParachuteCloth mesh, MatrixStack matrices,
            VertexConsumer buffer, float delta, int light) {
        for (boolean left : new boolean[]{false, true}) {
            double side = left ? 1 : -1;
            Vec3d bundle = ParachutePose.bundle(entity, left);
            for (int group = 0; group < ParachuteSuspension.GROUPS_PER_SIDE; group++) {
                // 三个短分束接头相距 4.5cm，让每组前后两根吊绳在汇入主绳之前仍清晰可辨。
                Vec3d junction = bundle.add((group - 1) * .045, .065, 0);
                drawCord(matrices, buffer, bundle, junction, light, 1.4f, 0xff40464b);
                for (int branch = 0; branch < ParachuteSuspension.LINES_PER_GROUP; branch++) {
                    int line = (left ? ParachuteSuspension.LINES / 2 : 0)
                            + group * ParachuteSuspension.LINES_PER_GROUP + branch;
                    int x = ParachuteSuspension.attachmentX(line) * ParachuteCloth.SPAN / ParachuteCanopy.SPAN;
                    int z = ParachuteSuspension.attachmentZ(line) * ParachuteCloth.CHORD / ParachuteCanopy.CHORD;
                    drawCord(matrices, buffer, junction, mesh.point(x, z), light);
                }
            }
            // 主绳不经过手掌，沿肩部外侧接入背包内部，操纵时不能跟着手柄上下拉动整束承力绳。
            Vec3d shoulder = new Vec3d(side * .25, 1.42, -.02);
            drawCord(matrices, buffer, bundle, shoulder, light, 3, 0xff34383c);
            drawCord(matrices, buffer, shoulder, ParachuteSuspension.packAnchor(left), light, 3, 0xff34383c);
            Vec3d grip = ParachutePose.grip(entity, left, delta);
            Vec3d a = grip.add(-HANDLE_HALF_WIDTH, 0, 0), b = grip.add(HANDLE_HALF_WIDTH, 0, 0);
            // 两边下垂带与横向握杆组成闭合三角拉手，掌心位于握杆中央。
            drawCord(matrices, buffer, bundle, a, light, 1.25f, HANDLE_COLOR);
            drawCord(matrices, buffer, bundle, b, light, 1.25f, HANDLE_COLOR);
            drawCord(matrices, buffer, a, b, light, 3.5f, HANDLE_COLOR);
        }
    }

    /** 统一判断外边界与破洞，让上下表面、封边共享同一个裁切形状。 */
    private static boolean hasCell(ParachuteEntity entity, int x, int z) {
        return x >= 0 && x < ParachuteCloth.SPAN && z >= 0 && z < ParachuteCloth.CHORD
                && !(entity.failure() == ParachuteEntity.TORN && torn(x, z));
    }

    /** 每根吊绳都按真实柔性节点绘制，松绳可落地、折弯，受力端点与布面吊点一致。 */
    private void drawTrailingLines(ParachuteEntity entity, ParachuteCloth mesh, MatrixStack matrices, VertexConsumer buffer,
            float delta, int light) {
        Vec3d origin = entity.getLerpedPos(delta);
        for (int line = 0; line < ParachuteSuspension.LINES; line++) {
            Vec3d previous = entity.linePoint(line, 0, delta);
            for (int node = 1; node <= ParachuteSuspension.SEGMENTS; node++) {
                Vec3d next = node == ParachuteSuspension.SEGMENTS
                        ? mesh.point(ParachuteSuspension.attachmentX(line) * ParachuteCloth.SPAN / ParachuteCanopy.SPAN,
                                ParachuteSuspension.attachmentZ(line) * ParachuteCloth.CHORD / ParachuteCanopy.CHORD)
                        : ParachuteCanopy.surfacePoint(entity,
                                entity.linePoint(line, node, delta).add(origin), .025).subtract(origin);
                drawCord(matrices, buffer, previous, next, light); previous = next;
            }
        }
    }

    /** 两处不规则撕口同时裁掉上下两层，避免用深色贴片冒充破洞。 */
    private static boolean torn(int x, int z) {
        x = x * 24 / ParachuteCloth.SPAN; z = z * 8 / ParachuteCloth.CHORD;
        return (x >= 5 && x <= 8 && z >= (x == 5 ? 3 : 2) && z <= (x == 8 ? 4 : 5))
                || (x >= 16 && x <= 19 && z >= (x == 19 ? 2 : 1) && z <= (x == 16 ? 3 : 4));
    }

    /** 上下层固定使用 a-c 对角线且绕序相反，避免扭曲四边形因拆分不同而互相穿插。 */
    private static void fabricSurface(MatrixStack matrices, VertexConsumer buffer, ParachuteCloth mesh,
            Vec3d a, Vec3d b, Vec3d c, Vec3d d, boolean underside, int color, int light, int x, int z) {
        Vec3d na = mesh.normal(x, z, underside), nb = mesh.normal(x + 1, z, underside);
        Vec3d nc = mesh.normal(x + 1, z + 1, underside), nd = mesh.normal(x, z + 1, underside);
        vertex(matrices, buffer, a, na, color, light, x * SPAN_UV, z * CHORD_UV);
        if (underside) {
            vertex(matrices, buffer, b, nb, color, light, (x + 1) * SPAN_UV, z * CHORD_UV);
            vertex(matrices, buffer, c, nc, color, light, (x + 1) * SPAN_UV, (z + 1) * CHORD_UV);
            vertex(matrices, buffer, a, na, color, light, x * SPAN_UV, z * CHORD_UV);
            vertex(matrices, buffer, c, nc, color, light, (x + 1) * SPAN_UV, (z + 1) * CHORD_UV);
            vertex(matrices, buffer, d, nd, color, light, x * SPAN_UV, (z + 1) * CHORD_UV);
        } else {
            vertex(matrices, buffer, d, nd, color, light, x * SPAN_UV, (z + 1) * CHORD_UV);
            vertex(matrices, buffer, c, nc, color, light, (x + 1) * SPAN_UV, (z + 1) * CHORD_UV);
            vertex(matrices, buffer, a, na, color, light, x * SPAN_UV, z * CHORD_UV);
            vertex(matrices, buffer, c, nc, color, light, (x + 1) * SPAN_UV, (z + 1) * CHORD_UV);
            vertex(matrices, buffer, b, nb, color, light, (x + 1) * SPAN_UV, z * CHORD_UV);
        }
    }

    /** 一片织物按实际形状计算法线，net.minecraft.client.render.VertexConsumer 保留环境光与纹理。 */
    private static void surface(MatrixStack matrices, VertexConsumer buffer, Vec3d a, Vec3d b,
            Vec3d c, Vec3d d, int color, int light, int x, int z) {
        Vec3d normal = b.subtract(a).crossProduct(d.subtract(a)).normalize();
        vertex(matrices, buffer, a, normal, color, light, x * SPAN_UV, z * CHORD_UV);
        vertex(matrices, buffer, b, normal, color, light, (x + 1) * SPAN_UV, z * CHORD_UV);
        vertex(matrices, buffer, c, normal, color, light, (x + 1) * SPAN_UV, (z + 1) * CHORD_UV);
        vertex(matrices, buffer, a, normal, color, light, x * SPAN_UV, z * CHORD_UV);
        vertex(matrices, buffer, c, normal, color, light, (x + 1) * SPAN_UV, (z + 1) * CHORD_UV);
        vertex(matrices, buffer, d, normal, color, light, x * SPAN_UV, (z + 1) * CHORD_UV);
    }

    /** 提交带织纹、覆盖层、光照和变换后法线的顶点，不使用立即模式或自发光渲染层。 */
    private static void vertex(MatrixStack matrices, VertexConsumer buffer, Vec3d point, Vec3d normal,
            int color, int light, float u, float v) {
        buffer.vertex(matrices.peek(), (float) point.x, (float) point.y, (float) point.z).color(color)
                .texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal(matrices.peek(), (float) normal.x, (float) normal.y, (float) normal.z);
    }

    /** net.minecraft.client.model.ModelPart 按真实吊点向量定向伸长，吊绳末端跟随同一布面。 */
    private void drawCord(MatrixStack matrices, VertexConsumer buffer, Vec3d start, Vec3d end, int light) {
        drawCord(matrices, buffer, start, end, light, 1, 0xff40464b);
    }

    /** 同一绳截面按宽度区分细吊绳、承力主绳与操纵柄；net.minecraft.client.model.ModelPart 保持端点精确。 */
    private void drawCord(MatrixStack matrices, VertexConsumer buffer, Vec3d start, Vec3d end,
            int light, float width, int color) {
        Vec3d offset = end.subtract(start); float length = (float) offset.length();
        if (length < .001f) return;
        matrices.push(); matrices.translate(start.x, start.y, start.z);
        matrices.multiply(new Quaternionf().rotationTo(0, 1, 0,
                (float) (offset.x / length), (float) (offset.y / length), (float) (offset.z / length)));
        matrices.scale(width, length, width); cord.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV, color);
        matrices.pop();
    }

    /** net.minecraft.client.render.entity.EntityRenderer 要求的纹理访问。 */
    @Override public Identifier getTexture(ParachuteEntity entity) { return FABRIC; }
}
