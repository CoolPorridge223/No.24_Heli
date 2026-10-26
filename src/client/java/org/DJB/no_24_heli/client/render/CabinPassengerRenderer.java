/** 文件：左右舱门乘降动作与第一人称身体；端侧：CLIENT；共用 HelicopterCabin 动画时间线。 */
package org.DJB.no_24_heli.client.render;

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
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerModelPart;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterCabin;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.CabinPlayerState;

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
    /** 夺座时的上下臂及袖层；保留标准/纤细皮肤，两段骨骼才能表现左肘弯曲。 */
    private static final ModelPart[] REGULAR_ARMS = actionArms(4), SLIM_ARMS = actionArms(3);

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
        if (motion == HelicopterCabin.PULL) return (heli.cabin.accessSide(rider) == 0 ? 90 : -90)
                * HelicopterCabin.pullPhase(heli.cabin.progress(rider, delta), .06f, HelicopterCabin.PULL_LIFT);
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
        if (heli.cabin.motion(rider) == HelicopterCabin.PULL)
            return heli.cabin.position(rider, delta).add(0, 1.50 - .80
                    * HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_HIT, HelicopterCabin.PULL_LANDED), .10);
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
        if (motion == HelicopterCabin.PULL) {
            float liftOut = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_GRAB, HelicopterCabin.PULL_LIFT);
            float strike = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_WINDUP, HelicopterCabin.PULL_HIT);
            float fall = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_HIT, HelicopterCabin.PULL_LANDED);
            model.head.pitch = -.45f * strike * (1 - fall) - .65f * fall; model.head.yaw = model.head.roll = 0;
            model.body.pitch = .16f * strike * (1 - fall); model.body.yaw = model.body.roll = 0;
            model.rightArm.pitch = model.leftArm.pitch = -.25f - .65f * liftOut * (1 - fall);
            model.rightArm.yaw = -.15f; model.leftArm.yaw = .15f;
            model.rightArm.roll = .08f + .27f * fall; model.leftArm.roll = -model.rightArm.roll;
            model.rightLeg.pitch = -(float) Math.PI / 2 * (1 - liftOut) + .06f * fall;
            model.leftLeg.pitch = -(float) Math.PI / 2 * (1 - liftOut) - .08f * fall;
        }
        if (model instanceof PlayerEntityModel<?> player) {
            if (motion == HelicopterCabin.PULL) player.hat.copyTransform(model.head);
            player.rightPants.copyTransform(model.rightLeg); player.leftPants.copyTransform(model.leftLeg);
            player.rightSleeve.copyTransform(model.rightArm); player.leftSleeve.copyTransform(model.leftArm);
            player.jacket.copyTransform(model.body);
        }
    }

    /** 开门单臂、双手拎起、左肘蓄力及击中回收；原版 setAngles 后覆盖，趴地者保持原摊开姿势。 */
    public static void actionPose(PlayerEntityModel<?> model, AbstractClientPlayerEntity player, float delta) {
        CabinPlayerState state = (CabinPlayerState) player;
        HelicopterEntity heli = state.cabinPullTarget();
        if (!state.cabinStunned() && heli == null) return;
        model.head.pitch = state.cabinStunned() ? -.65f : 0; model.head.yaw = model.head.roll = 0;
        model.body.pitch = model.body.yaw = model.body.roll = 0;
        model.rightLeg.pitch = state.cabinStunned() ? .06f : -.18f;
        model.leftLeg.pitch = state.cabinStunned() ? -.08f : .25f;
        model.rightLeg.yaw = model.leftLeg.yaw = model.rightLeg.roll = model.leftLeg.roll = 0;
        float reach = state.cabinStunned() ? -.22f : -1.3f + .55f * heli.cabin.progress(heli.cabin.pulledOccupant(player), delta);
        model.rightArm.pitch = model.leftArm.pitch = reach;
        model.rightArm.yaw = -.15f; model.leftArm.yaw = .15f;
        model.rightArm.roll = state.cabinStunned() ? .35f : .08f;
        model.leftArm.roll = -model.rightArm.roll;
        if (heli != null) pullerPose(model, player, heli, delta);
        model.hat.copyTransform(model.head); model.jacket.copyTransform(model.body);
        model.rightSleeve.copyTransform(model.rightArm); model.leftSleeve.copyTransform(model.leftArm);
        model.rightPants.copyTransform(model.rightLeg); model.leftPants.copyTransform(model.leftLeg);
    }

    /** 拉人者逐段调整肩部及步伐；net.minecraft.client.render.entity.model.PlayerEntityModel 的衣物随后复制姿态。 */
    private static void pullerPose(PlayerEntityModel<?> model, AbstractClientPlayerEntity player,
            HelicopterEntity heli, float delta) {
        float p = heli.cabin.progress(heli.cabin.pulledOccupant(player), delta);
        float grab = HelicopterCabin.pullPhase(p, 0, HelicopterCabin.PULL_GRAB);
        float lift = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_GRAB, HelicopterCabin.PULL_LIFT);
        float windup = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_LIFT, HelicopterCabin.PULL_WINDUP);
        float strike = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_WINDUP, HelicopterCabin.PULL_HIT);
        float release = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_HIT, HelicopterCabin.PULL_LANDED);
        float open = heli.cabin.doorProgress(((CabinPlayerState) player).cabinPullSeat(), delta);
        model.rightArm.pitch = MathHelper.lerp(grab, -1.15f * open, -1.30f) + .35f * lift + .70f * release;
        model.leftArm.pitch = -.15f - 1.15f * grab + .35f * lift + .30f * windup - .85f * strike + 1.1f * release;
        model.rightArm.yaw = -.12f; model.leftArm.yaw = .12f + .95f * windup - 1.45f * strike + .5f * release;
        model.rightArm.roll = .08f; model.leftArm.roll = -.08f - .65f * windup + .20f * strike + .45f * release;
        model.body.pitch = .12f * grab - .25f * lift + .35f * strike - .22f * release;
        model.head.pitch = .10f * grab + .22f * strike * (1 - release);
        model.rightLeg.pitch = -.24f * lift + .16f * strike; model.leftLeg.pitch = .30f * lift - .24f * strike;
    }

    /** 躯干转动先向右蓄力，再带动左肩向前击出，单位为度；只用于本次拉人矩阵。 */
    public static float pullerTwist(float p) {
        return -32 * HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_LIFT, HelicopterCabin.PULL_WINDUP)
                + 76 * HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_WINDUP, HelicopterCabin.PULL_HIT)
                - 44 * HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_HIT, HelicopterCabin.PULL_LANDED);
    }

    /** 原版皮肤左右臂及袖子 UV 各拆成六像素上臂和六像素前臂；几何只烘焙一次。 */
    private static ModelPart[] actionArms(int width) {
        ModelData data = new ModelData();
        ModelPart[] parts = new ModelPart[8];
        for (int i = 0; i < parts.length; i++) {
            boolean left = i >= 4, sleeve = i % 4 >= 2, lower = i % 2 == 1;
            int u = left ? sleeve ? 48 : 32 : 40;
            int v = (left ? 48 : sleeve ? 32 : 16) + (lower ? 6 : 0);
            data.getRoot().addChild("arm" + i, ModelPartBuilder.create().uv(u, v)
                    .cuboid(left ? -1 : 1 - width, lower ? 0 : -2, -2, width, 6, 4,
                            new Dilation(sleeve ? .25f : 0)), ModelTransform.NONE);
        }
        ModelPart root = TexturedModelData.of(data, 64, 64).createModel();
        for (int i = 0; i < parts.length; i++) parts[i] = root.getChild("arm" + i);
        return parts;
    }

    /**
     * 功能：在原版人物模型空间绘制弯肘双臂，左前臂向上折回，使击出的是肘而非整条直臂。
     * 引用 net.minecraft.client.model.ModelPart#rotate 复用肩骨，MatrixStack 在肘点连接两段皮肤和袖层。
     * 端侧/线程：CLIENT 渲染线程；调用方临时隐藏原版双臂并在返回时恢复；隐身玩家不补画。
     * 排查：接缝查肘点四像素与上臂起点负二像素；衣物开关查 PlayerModelPart，不写玩家装备。
     */
    public static void renderActionArms(PlayerEntityModel<?> model, AbstractClientPlayerEntity player, float delta,
            MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        HelicopterEntity heli = ((CabinPlayerState) player).cabinPullTarget();
        if (heli == null || player.isInvisible()) return;
        float p = heli.cabin.progress(heli.cabin.pulledOccupant(player), delta);
        float bend = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_LIFT, HelicopterCabin.PULL_WINDUP)
                * (1 - HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_HIT, HelicopterCabin.PULL_LANDED));
        ModelPart[] parts = player.getSkinTextures().model() == SkinTextures.Model.SLIM ? SLIM_ARMS : REGULAR_ARMS;
        var buffer = consumers.getBuffer(RenderLayer.getEntityTranslucent(player.getSkinTextures().texture()));
        for (int hand = 0; hand < 2; hand++) {
            int offset = hand * 4;
            boolean sleeve = player.isPartVisible(hand == 0 ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);
            matrices.push(); (hand == 0 ? model.rightArm : model.leftArm).rotate(matrices);
            parts[offset].render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
            if (sleeve) parts[offset + 2].render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
            matrices.translate(0, 4f / 16, 0);
            matrices.multiply(RotationAxis.POSITIVE_X.rotation(hand == 0 ? -.35f : -.35f - 1.85f * bend));
            parts[offset + 1].render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
            if (sleeve) parts[offset + 3].render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
            matrices.pop();
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
