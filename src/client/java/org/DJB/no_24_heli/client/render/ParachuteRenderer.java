/** 文件：充压滑翔伞布面、气室和吊绳；端侧：CLIENT；实体：no_24_heli:parachute。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.ParachuteEntity;
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
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
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
    /** 吊绳厚 0.0125 格，八根落地吊绳逐段使用服务端柔性节点，不能画成两根直杆。 */
    private final ModelPart cord;
    /** 织物资源为细格防撕裂布，无自发光，接受环境和光影光照。 */
    private static final Identifier FABRIC = No_24_heli.id("textures/entity/parachute_fabric.png");

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
        if (loose) mesh.prepareLoose(entity, delta); else mesh.update(entity);
        double opening = loose ? 0 : entity.inflation(delta);
        matrices.push();
        if (!loose) {
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-entity.canopyYaw(delta)));
            ParachutePose.apply(matrices, entity, delta, false);
        }
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(FABRIC));
        for (int x = 0; x < ParachuteCloth.SPAN; x++) for (int z = 0; z < ParachuteCloth.CHORD; z++) {
            if (!hasCell(entity, x, z)) continue;
            Vec3d a = mesh.point(x, z, delta), b = mesh.point(x + 1, z, delta);
            Vec3d c = mesh.point(x + 1, z + 1, delta), d = mesh.point(x, z + 1, delta);
            double front = thickness(loose, opening, z), back = thickness(loose, opening, z + 1);
            // 落地节点作为底层，所有厚度向上增加，不能把下层画进地面；空中节点仍是上层。
            Vec3d lowA = loose ? a : a.add(0, -front, 0), lowB = loose ? b : b.add(0, -front, 0);
            Vec3d lowC = loose ? c : c.add(0, -back, 0), lowD = loose ? d : d.add(0, -back, 0);
            if (loose) {
                a = a.add(0, front, 0); b = b.add(0, front, 0);
                c = c.add(0, back, 0); d = d.add(0, back, 0);
            }
            // 深蓝翼尖、橙色主翼和米白中心条；顶底层之间保留气室厚度。
            int color = x < 3 || x >= 21 ? 0xff28465d : x >= 10 && x < 14 ? 0xfff2ead3 : 0xffe99539;
            // 世界局部 +Y 朝上；顶底绕序相反，法线必须朝向布面外侧以接受正确光照。
            surface(matrices, buffer, d, c, b, a, color, light, x, z);
            surface(matrices, buffer, lowA, lowB, lowC, lowD, 0xffe4e2d6, light, x, z);
            // 四周和破口都封边，斜视时仍有实体厚度，不是两张互相悬空的面。
            if (!hasCell(entity, x, z - 1)) surface(matrices, buffer, a, b, lowB, lowA, color, light, x, z);
            if (!hasCell(entity, x + 1, z)) surface(matrices, buffer, b, c, lowC, lowB, color, light, x, z);
            if (!hasCell(entity, x, z + 1)) surface(matrices, buffer, c, d, lowD, lowC, color, light, x, z);
            if (!hasCell(entity, x - 1, z)) surface(matrices, buffer, d, a, lowA, lowD, color, light, x, z);
        }
        for (int x = 0; x <= ParachuteCloth.SPAN; x += 2) {
            Vec3d leading = mesh.point(x, ParachuteCloth.CHORD, delta);
            // 前缘暗色进气口和侧向肋片形成冲压翼气室，而非一张没有厚度的板。
            if (!loose && x < ParachuteCloth.SPAN) {
                Vec3d next = mesh.point(x + 2, ParachuteCloth.CHORD, delta);
                surface(matrices, buffer, leading.add(0, -.13 * opening, -.04),
                        next.add(0, -.13 * opening, -.04), next, leading, 0xff5d5341, light, x, 0);
            }
            if (!loose && x % 4 == 0) for (int z : new int[]{1, 6}) {
                Vec3d harness = new Vec3d(
                        x < ParachuteCloth.SPAN / 2 ? -.25 : .25, 1.25, z == 1 ? -.12 : .12);
                drawCord(matrices, buffer, harness, mesh.point(x, z, delta), light);
            }
        }
        if (entity.mode() == ParachuteEntity.TRAILING && entity.hasLines())
            drawTrailingLines(entity, mesh, matrices, buffer, delta, light);
        matrices.pop(); super.render(entity, yaw, delta, matrices, consumers, light);
    }

    /** 空中气室最厚约 0.36 格；落地仍保留 0.09–0.20 格的软厚度，不因失压变成零厚度。 */
    private static double thickness(boolean loose, double opening, int z) {
        double profile = Math.sin(z / (double) ParachuteCloth.CHORD * Math.PI);
        return loose ? .09 + .11 * profile : .04 + .32 * opening * profile;
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
                        ? mesh.point(ParachuteSuspension.attachmentX(line) * 3,
                                ParachuteSuspension.attachmentZ(line) * 2, delta)
                        : org.DJB.no_24_heli.flight.ParachuteCanopy.surfacePoint(entity,
                                entity.linePoint(line, node, delta).add(origin), .025).subtract(origin);
                drawCord(matrices, buffer, previous, next, light); previous = next;
            }
        }
    }

    /** 两处不规则撕口同时裁掉上下两层，避免用深色贴片冒充破洞。 */
    private static boolean torn(int x, int z) {
        return (x >= 5 && x <= 8 && z >= (x == 5 ? 3 : 2) && z <= (x == 8 ? 4 : 5))
                || (x >= 16 && x <= 19 && z >= (x == 19 ? 2 : 1) && z <= (x == 16 ? 3 : 4));
    }

    /** 一片织物按实际形状计算法线，net.minecraft.client.render.VertexConsumer 保留环境光与纹理。 */
    private static void surface(MatrixStack matrices, VertexConsumer buffer, Vec3d a, Vec3d b,
            Vec3d c, Vec3d d, int color, int light, int x, int z) {
        Vec3d normal = b.subtract(a).crossProduct(d.subtract(a)).normalize();
        vertex(matrices, buffer, a, normal, color, light, x / 4f, z / 4f);
        vertex(matrices, buffer, b, normal, color, light, (x + 1) / 4f, z / 4f);
        vertex(matrices, buffer, c, normal, color, light, (x + 1) / 4f, (z + 1) / 4f);
        vertex(matrices, buffer, d, normal, color, light, x / 4f, (z + 1) / 4f);
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
        Vec3d offset = end.subtract(start); float length = (float) offset.length();
        if (length < .001f) return;
        matrices.push(); matrices.translate(start.x, start.y, start.z);
        matrices.multiply(new Quaternionf().rotationTo(0, 1, 0,
                (float) (offset.x / length), (float) (offset.y / length), (float) (offset.z / length)));
        matrices.scale(1, length, 1); cord.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV, 0xff40464b);
        matrices.pop();
    }

    /** net.minecraft.client.render.entity.EntityRenderer 要求的纹理访问。 */
    @Override public Identifier getTexture(ParachuteEntity entity) { return FABRIC; }
}
