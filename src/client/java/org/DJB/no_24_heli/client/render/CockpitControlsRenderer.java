/** 文件：双人联动摇杆与玩家握杆手臂；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 职责：两根摇杆读取同一驾驶输入，主副驾右手共用对应摇杆的实际握点；只做视觉，不授予副驾控制权限。
 * 引用 net.minecraft.client.model.ModelPart 使用原版皮肤 UV；MatrixStack 把两段手臂连接到肩、肘和握点。
 * 生命周期：飞机渲染阶段，原版乘员右臂由 HeliCrewRendererMixin 隐藏；客户端渲染线程。
 * 排查：手杆分离查 grip/rotation 共用；第一人称不显示查调用位置；左右手错位查本地 +X 为左侧。
 */
@Environment(EnvType.CLIENT)
public final class CockpitControlsRenderer {
    /** 底座下缘 0.635 格嵌入 0.64 格高地板；前后位置与双座统一前移，单位格。 */
    public static final double BASE_Y = .68, BASE_Z = .55 + HelicopterEntity.SEAT_FORWARD_OFFSET;
    /** 握点降至 0.83 格高，单位格；与 HelicopterModel 的独立杆体几何保持一致，手掌保持在仪表下缘之下。 */
    public static final Vec3d GRIP = new Vec3d(0, .15, -.08);
    /** 满杆偏转 14 度，显示以约 0.1 秒响应跟随输入，不修改原飞行参数。 */
    private static final float MAX_ANGLE = (float) Math.toRadians(14);
    /** 以机体为弱键，记录显示轴和上一帧的世界 tick 时间，阴影重复绘制不会加速动画。 */
    private static final Map<HelicopterEntity, StickPose> POSES = new WeakHashMap<>();
    /** 标准/纤细皮肤的两段手臂与袖子几何，仅构造一次。 */
    private static final ModelPart[] REGULAR = armParts(4), SLIM = armParts(3);

    /** 只保存可视动画状态；机体卸载时由弱键释放。 */
    private static final class StickPose {
        private float pitch, roll, time = Float.NaN;
    }

    /** 仅静态工具，所有坐标在机体局部空间。 */
    private CockpitControlsRenderer() {}

    /**
     * 功能：两根杆和四段手臂均使用同一个平滑四元数；本地主驾读取本地按键减小网络往返延迟。
     * 引用 net.minecraft.client.option.KeyBinding 只读按键；其他观察者和自动驾驶读取服务端快照。
     */
    public static Quaternionf rotation(HelicopterEntity heli, float delta) {
        var client = MinecraftClient.getInstance();
        float pitch = heli.flight.stickPitch, roll = heli.flight.stickRoll;
        if (client.player != null && heli.isPilot(client.player) && !heli.flight.autopilot && heli.flight.autoPhase == 0) {
            pitch = client.currentScreen == null ? (client.options.backKey.isPressed() ? 1 : 0)
                    - (client.options.forwardKey.isPressed() ? 1 : 0) : 0;
            roll = client.currentScreen == null ? (client.options.rightKey.isPressed() ? 1 : 0)
                    - (client.options.leftKey.isPressed() ? 1 : 0) : 0;
        }
        if (heli.flight.wreck || heli.getPassengerList().stream().noneMatch(heli::isPilot)) pitch = roll = 0;
        StickPose pose = POSES.computeIfAbsent(heli, ignored -> new StickPose());
        float now = heli.age + delta;
        float elapsed = Float.isNaN(pose.time) ? 1 : MathHelper.clamp(now - pose.time, 0, 2);
        float blend = 1 - (float) Math.exp(-elapsed * .65);
        pose.pitch += (pitch - pose.pitch) * blend;
        pose.roll += (roll - pose.roll) * blend;
        pose.time = now;
        return new Quaternionf().rotationZ(pose.roll * MAX_ANGLE).rotateX(-pose.pitch * MAX_ANGLE);
    }

    /** 把同一杆体的握点变到对应座位，不按手臂位置反向修改杆体。 */
    public static Vec3d grip(double side, Quaternionf rotation) {
        Vector3f point = new Vector3f((float) GRIP.x, (float) GRIP.y, (float) GRIP.z).rotate(rotation);
        return new Vec3d(side + point.x, BASE_Y + point.y, BASE_Z + point.z);
    }

    /**
     * 功能：主副驾各绘制一条右臂，第一人称仍使用世界坐标，因此转头与机体晃动不会让手脱离摇杆。
     * 引用 net.minecraft.client.network.AbstractClientPlayerEntity#getSkinTextures 保留玩家皮肤与纤细手臂。
     * 端侧：渲染线程；副作用：提交实体顶点，不绘制手持物，不修改库存；隐身乘员跳过。
     */
    public static void renderArms(HelicopterEntity heli, float delta, MatrixStack matrices,
            VertexConsumerProvider consumers, int light) {
        Quaternionf rotation = rotation(heli, delta);
        for (var passenger : heli.getPassengerList()) {
            if (!(passenger instanceof AbstractClientPlayerEntity player) || player.isInvisible() || !player.isAlive()) continue;
            double side = heli.isPilot(player) ? .38 : -.38;
            Vec3d shoulder = new Vec3d(side - .25, 1.22, -.12 + HelicopterEntity.SEAT_FORWARD_OFFSET);
            Vec3d elbow = new Vec3d(side - .29, .86, .18 + HelicopterEntity.SEAT_FORWARD_OFFSET);
            Vector3f palmOffset = new Vector3f(-.085f, 0, 0).rotate(rotation);
            Vec3d grip = grip(side, rotation).add(palmOffset.x, palmOffset.y, palmOffset.z);
            // 手掌末端越过握点四厘米，使杆柄落在手掌体积内部，而不是贴着指尖悬空。
            Vec3d palmEnd = grip.add(grip.subtract(elbow).normalize().multiply(.04));
            ModelPart[] parts = player.getSkinTextures().model() == SkinTextures.Model.SLIM ? SLIM : REGULAR;
            RenderLayer layer = RenderLayer.getEntityTranslucent(player.getSkinTextures().texture());
            drawArmSegment(parts[0], shoulder, elbow, 0, matrices, consumers, layer, light);
            drawArmSegment(parts[1], elbow, palmEnd, (float) Math.PI / 2, matrices, consumers, layer, light);
            if (player.isPartVisible(PlayerModelPart.RIGHT_SLEEVE)) {
                drawArmSegment(parts[2], shoulder, elbow, 0, matrices, consumers, layer, light);
                drawArmSegment(parts[3], elbow, palmEnd, (float) Math.PI / 2, matrices, consumers, layer, light);
            }
        }
    }

    /** 原版右臂 UV 分成上臂和前臂，袖层使用右袖区域；宽度分别支持 Steve/Alex。 */
    private static ModelPart[] armParts(int width) {
        ModelData data = new ModelData();
        for (int i = 0; i < 4; i++) {
            int v = (i < 2 ? 16 : 32) + (i % 2) * 6;
            data.getRoot().addChild("arm" + i, ModelPartBuilder.create().uv(40, v)
                    .cuboid(-width / 2f, 0, -2, width, 6, 4, new Dilation(i < 2 ? 0 : .20f)), ModelTransform.NONE);
        }
        ModelPart root = TexturedModelData.of(data, 64, 64).createModel();
        return new ModelPart[]{root.getChild("arm0"), root.getChild("arm1"), root.getChild("arm2"), root.getChild("arm3")};
    }

    /** net.minecraft.client.model.ModelPart 按端点缩放；前臂绕长轴转九十度，手掌从杆柄侧面包住摇杆。 */
    private static void drawArmSegment(ModelPart part, Vec3d start, Vec3d end, float twist, MatrixStack matrices,
            VertexConsumerProvider consumers, RenderLayer layer, int light) {
        Vec3d direction = end.subtract(start);
        matrices.push();
        matrices.translate(start.x, start.y, start.z);
        matrices.multiply(new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) direction.x, (float) direction.y, (float) direction.z).normalize()).rotateY(twist));
        matrices.scale(.88f, (float) direction.length() / (6f / 16), .88f);
        part.render(matrices, consumers.getBuffer(layer), light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }
}
