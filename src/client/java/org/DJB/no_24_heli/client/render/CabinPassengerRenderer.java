/** 文件：左右舱门乘降动作与第一人称身体；端侧：CLIENT；共用 HelicopterCabin 动画时间线。 */
package org.DJB.no_24_heli.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterCabin;
import org.DJB.no_24_heli.entity.HelicopterEntity;

/**
 * 职责：左右抬脚跨入后坐下、起身迈下与空中跃出，三种镜头复用同一骨骼和位置。
 * 引用 net.minecraft.client.render.entity.model.PlayerEntityModel 保留标准/纤细皮肤 UV，MatrixStack 隔离身体变换。
 * 生命周期：LivingEntityRenderer Mixin 调整第三人称，飞机渲染器补绘第一人称手脚；只在渲染线程运行。
 * 排查：左右动作反向查座号奇偶；衣服不跟随查 copyTransform；第一人称穿头查隐藏 head/hat。
 */
@Environment(EnvType.CLIENT)
public final class CabinPassengerRenderer {
    /** 原有舱内人物缩放与髋高，单位格；第一/第三人称保持同一基准。 */
    public static final float SCALE = .88f, HIP = .70f;
    /** 第一人称只复用两个原版皮肤模型，避免每帧烘焙。 */
    private static PlayerEntityModel<AbstractClientPlayerEntity> regular, slim;

    /** 静态渲染工具，不创建权威实体。 */
    private CabinPassengerRenderer() {}

    /** 只对坐稳的前排显示握杆手臂，后排和乘降期间使用自然双臂。 */
    public static boolean holdsStick(HelicopterEntity heli, LivingEntity rider) {
        int seat = heli.cabin.seat(rider);
        return seat >= 0 && seat < 2 && heli.cabin.motion(rider) == HelicopterCabin.SEATED;
    }

    /** 迈入时朝舱内，走下/跃出时朝舱外；在动作末段平滑转到座椅方向。 */
    public static float yaw(HelicopterEntity heli, LivingEntity rider, float delta) {
        int motion = heli.cabin.motion(rider);
        if (motion == HelicopterCabin.SEATED) return 0;
        float p = heli.cabin.progress(rider, delta);
        float turn = motion == HelicopterCabin.BOARD ? 1 - MathHelper.clamp((p - .55f) / .45f, 0, 1)
                : MathHelper.clamp(p / .45f, 0, 1);
        return (heli.cabin.accessSide(rider) == 0 ? 90 : -90) * turn * (motion == HelicopterCabin.BOARD ? -1 : 1);
    }

    /** 跨门槛时短暂向下看脚步，动作末端归零；不写玩家鼠标俯仰。 */
    public static float nod(HelicopterEntity heli, LivingEntity rider, float delta) {
        return heli.cabin.motion(rider)==HelicopterCabin.SEATED ? 0
                : 24*(float)Math.sin(heli.cabin.progress(rider,delta)*Math.PI);
    }

    /** 眼位叠加在同一脚位上，跨过门框时略低头；net.minecraft.util.math.Vec3d 为机体局部坐标。 */
    public static Vec3d eye(HelicopterEntity heli, LivingEntity rider, float delta) {
        float p = heli.cabin.progress(rider, delta);
        double duck = heli.cabin.motion(rider) == HelicopterCabin.SEATED ? 0 : Math.sin(p * Math.PI) * .30;
        return heli.cabin.position(rider, delta).add(0, 1.58 - duck, .10);
    }

    /**
     * 原版 setAngles 后覆盖乘降骨骼；左门先抬左脚，右门镜像，落座时双腿平行前伸。
     * net.minecraft.client.render.entity.model.BipedEntityModel 的骨骼共享给装备层，不修改玩家状态。
     */
    public static void pose(BipedEntityModel<?> model, HelicopterEntity heli, LivingEntity rider, float delta) {
        int motion = heli.cabin.motion(rider);
        float p = heli.cabin.progress(rider, delta);
        float seated = motion == HelicopterCabin.SEATED ? 1 : motion == HelicopterCabin.BOARD
                ? MathHelper.clamp((p - .60f) / .40f, 0, 1) : 1 - MathHelper.clamp(p / .35f, 0, 1);
        float step = (float) Math.sin(p * Math.PI * 2) * .55f * (1 - seated);
        float lift = (float) Math.sin(p * Math.PI) * .8f * (1 - seated);
        model.body.pitch = motion == HelicopterCabin.SEATED ? 0 : .16f * (float) Math.sin(p * Math.PI);
        model.rightLeg.pitch = -seated * (float) Math.PI / 2 + step;
        model.leftLeg.pitch = -seated * (float) Math.PI / 2 - step;
        if (heli.cabin.accessSide(rider) == 0) model.leftLeg.pitch -= lift;
        else model.rightLeg.pitch -= lift;
        model.rightLeg.yaw = model.leftLeg.yaw = model.rightLeg.roll = model.leftLeg.roll = 0;
        float reach = motion==HelicopterCabin.SEATED ? .25f : .85f*(float)Math.sin(p*Math.PI)+.25f;
        model.rightArm.pitch = -reach - step * .6f; model.leftArm.pitch = -reach + step * .6f;
        model.rightArm.roll = .08f; model.leftArm.roll = -.08f;
        if (motion == HelicopterCabin.JUMP) {
            model.rightArm.pitch = model.leftArm.pitch = -.8f * (float) Math.sin(p * Math.PI);
            model.rightLeg.pitch -= .65f; model.leftLeg.pitch -= .15f;
        }
        if (model instanceof PlayerEntityModel<?> player) {
            player.rightPants.copyTransform(model.rightLeg); player.leftPants.copyTransform(model.leftLeg);
            player.rightSleeve.copyTransform(model.rightArm); player.leftSleeve.copyTransform(model.leftArm);
            player.jacket.copyTransform(model.body);
        }
    }

    /**
     * 第一人称补绘可见手脚和躯干，避免只有镜头移动却看不到抬脚动作。
     * 引用 net.minecraft.client.render.entity.model.EntityModelLayers 按皮肤类型烘焙，绘制后还原矩阵。
     * 飞机模型已处于机体局部坐标；不经 EntityRenderer 递归，不渲染玩家头部或手持物。
     */
    public static void renderFirstPerson(HelicopterEntity heli, float delta, MatrixStack matrices,
            VertexConsumerProvider consumers, int light) {
        MinecraftClient client = MinecraftClient.getInstance();
        var player = client.player;
        if (player == null || player.getVehicle() != heli || !client.options.getPerspective().isFirstPerson()
                || player.isInvisible() || holdsStick(heli, player) || consumers instanceof SearchlightModels) return;
        boolean thin = player.getSkinTextures().model() == SkinTextures.Model.SLIM;
        if (regular == null) regular = new PlayerEntityModel<>(client.getEntityModelLoader().getModelPart(EntityModelLayers.PLAYER), false);
        if (slim == null) slim = new PlayerEntityModel<>(client.getEntityModelLoader().getModelPart(EntityModelLayers.PLAYER_SLIM), true);
        PlayerEntityModel<AbstractClientPlayerEntity> model = thin ? slim : regular;
        model.riding = false; model.sneaking = false; model.handSwingProgress = 0;
        model.setAngles(player, 0, 0, player.age + delta, 0, 0);
        pose(model, heli, player, delta);
        model.head.visible = model.hat.visible = false;
        Vec3d pos = heli.cabin.position(player, delta);
        matrices.push(); matrices.translate(pos.x, pos.y + HIP, pos.z);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(yaw(heli, player, delta)));
        matrices.scale(SCALE, SCALE, SCALE); matrices.translate(0, -HIP, 0);
        matrices.scale(1, -1, -1); matrices.translate(0, -1.501, 0);
        model.render(matrices, consumers.getBuffer(RenderLayer.getEntityTranslucent(player.getSkinTextures().texture())),
                light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }
}
