/** 文件：伞翼与人物共用的俯仰、横滚变换；端侧：CLIENT。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.entity.ParachuteEntity;
import org.DJB.no_24_heli.flight.ParachutePhysics;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

/**
 * 职责：统一人伞倾斜、左右操纵拉手和手臂角度，避免人伞反倾或手柄分离。
 * 引用：net.minecraft.client.util.math.MatrixStack 保留乘员的原版骨骼、皮肤和装备特征。
 * 生命周期：实体渲染和乘员 setupTransforms 每帧调用；仅渲染线程，不改变相机或物理坐标。
 * 排查：本地 +Z 是伞前方，+X 是乘员左侧；原版人物先转了 180°，故人物模型角度须反号。
 */
@Environment(EnvType.CLIENT)
public final class ParachutePose {
    /** 胸前吊点离脚 1.25 格，两种模型围绕同一世界点旋转。 */
    private static final double HARNESS_HEIGHT = 1.25;
    /** 双臂向前上方抬起约 126°，轻微外展；握点置于第一人称视野内，两种视角共用骨骼。 */
    private static final float ARM_PITCH = -2.2f, ARM_ROLL = .08f;
    /** 前后拉柄改变臂角 0.20 rad，转向内侧额外下拉 0.28 rad；沿用已有同步姿态平滑过渡。 */
    private static final float FORE_AFT_PULL = .20f, TURN_PULL = .28f;
    /** net.minecraft.client.render.entity.PlayerEntityRenderer 的模型缩放与 LivingEntityRenderer 的脚位平移。 */
    private static final float PLAYER_MODEL_SCALE = .9375f, MODEL_FEET = 1.501f;

    /** 无实例，全部姿态取自正在渲染的那具伞。 */
    private ParachutePose() {}

    /**
     * 原版 setAngles 完成后固定双臂握绳、双腿自然悬垂，覆盖乘骑坐姿与行走动作。
     * net.minecraft.client.model.ModelPart#resetTransform 保留标准/纤细皮肤的各自肩点，袖层复制最终骨骼。
     */
    public static void holdLines(BipedEntityModel<?> model, ParachuteEntity canopy, float delta) {
        model.rightArm.resetTransform(); model.leftArm.resetTransform();
        model.rightArm.pitch = armPitch(canopy, false, delta);
        model.leftArm.pitch = armPitch(canopy, true, delta);
        model.rightArm.roll = -ARM_ROLL; model.leftArm.roll = ARM_ROLL;
        model.riding = false; model.sneaking = false; model.body.resetTransform();
        model.rightLeg.resetTransform(); model.leftLeg.resetTransform();
        // 全身已跟伞倾斜，腿部以较小的相反偏转保留悬垂感，不能再次叠加整幅倾斜或恢复坐姿。
        float pitch = (float) Math.toRadians(canopy.canopyPitch(delta));
        float bank = (float) Math.toRadians(canopy.bank(delta));
        model.rightLeg.pitch = model.leftLeg.pitch = .06f - pitch * .3f;
        model.rightLeg.roll = .035f + bank * .25f;
        model.leftLeg.roll = -.035f + bank * .25f;
        if (model instanceof PlayerEntityModel<?> playerModel) {
            playerModel.rightSleeve.copyTransform(model.rightArm);
            playerModel.leftSleeve.copyTransform(model.leftArm);
            playerModel.rightPants.copyTransform(model.rightLeg);
            playerModel.leftPants.copyTransform(model.leftLeg);
            playerModel.jacket.copyTransform(model.body);
        }
    }

    /**
     * 在伞的局部矩阵下使用原版玩家骨骼；net.minecraft.client.util.math.MatrixStack 转换 Y/Z 轴及脚位。
     * 第一人称世界手臂和 grip 使用同一缩放与原点，不跟随鼠标或手持物摆动。
     */
    public static void applyPlayerModel(MatrixStack matrices, LivingEntity player) {
        float scale = PLAYER_MODEL_SCALE * player.getScale();
        matrices.scale(scale, -scale, -scale);
        matrices.translate(0, -MODEL_FEET, 0);
    }

    /**
     * 把手掌中心变到伞的局部坐标，吊绳束从这里经过；左右手与身体共享同一俯仰/横滚。
     * net.minecraft.client.network.AbstractClientPlayerEntity#getSkinTextures 区分标准/纤细手臂，
     * net.minecraft.entity.LivingEntity#getScale 保留玩家尺寸；只读渲染数据，不修改乘员位置。
     */
    public static Vec3d grip(ParachuteEntity canopy, boolean left, float delta) {
        boolean slim = canopy.getFirstPassenger() instanceof AbstractClientPlayerEntity player
                && player.getSkinTextures().model() == SkinTextures.Model.SLIM;
        float side = left ? 1 : -1;
        // 原版手掌中心距肩轴 8.5 像素，横向偏移为标准 1 像素、纤细 0.5 像素。
        Vector3f palm = new Vector3f(side * (slim ? .5f : 1), 8.5f, 0)
                .rotateX(armPitch(canopy, left, delta)).rotateZ(side * ARM_ROLL)
                .add(side * 5, slim ? 2.5f : 2, 0).mul(1f / 16);
        double scale = PLAYER_MODEL_SCALE * (canopy.getFirstPassenger() instanceof LivingEntity rider ? rider.getScale() : 1);
        // 原版人物的 Y/Z 轴经模型翻转及 180° 身体朝向转换；伞局部 +X 为人物左侧。
        return new Vec3d(palm.x, MODEL_FEET - palm.y, -palm.z).multiply(scale);
    }

    /** 前倾时双手前拉，后倾时后拉；左转下拉左柄、右转下拉右柄，手掌与实际柄位共用此角度。 */
    private static float armPitch(ParachuteEntity canopy, boolean left, float delta) {
        float forward = (float) Math.clamp(canopy.canopyPitch(delta) / ParachutePhysics.MAX_PITCH, -1, 1);
        float turn = Math.clamp(canopy.bank(delta) / 28f, -1, 1);
        float inside = Math.max(0, left ? -turn : turn);
        return ARM_PITCH + forward * FORE_AFT_PULL + inside * TURN_PULL;
    }

    /** 三组承力绳在肩部上方汇合；汇合处固定在伞/背包坐标系，不能随手部操纵上下移动。 */
    public static Vec3d bundle(ParachuteEntity canopy, boolean left) {
        double scale = canopy.getFirstPassenger() instanceof LivingEntity rider ? rider.getScale() : 1;
        return new Vec3d(left ? .42 : -.42, 1.98, .18).multiply(scale);
    }

    /**
     * 第一人称眼位随人物整体倾斜到头部，视线旋转仍由原版鼠标控制。
     * net.minecraft.entity.Entity#getCameraPosVec 提供平滑眼高；与伞共用 Y(-yaw)·Z(bank)·X(pitch) 变换。
     */
    public static Vec3d eyePosition(ParachuteEntity canopy, Entity rider, float delta) {
        double eyeHeight = rider.getCameraPosVec(delta).y - rider.getLerpedPos(delta).y;
        Vector3f eye = new Vector3f(0, (float) (eyeHeight - HARNESS_HEIGHT), 0)
                .rotateX((float) Math.toRadians(canopy.canopyPitch(delta)))
                .rotateZ((float) Math.toRadians(canopy.bank(delta))).add(0, (float) HARNESS_HEIGHT, 0)
                .rotateY((float) -Math.toRadians(canopy.canopyYaw(delta)));
        return canopy.getLerpedPos(delta).add(eye.x, eye.y, eye.z);
    }

    /**
     * 先横滚再俯仰；正横滚抬高左翼、压低右翼，正俯仰压低 +Z 前缘。
     * net.minecraft.util.math.RotationAxis 作用于局部坐标；人物已有 Y=180° 变换，须共轭反号。
     * 参数 reversedModel 指原版人物坐标系；调用方负责 MatrixStack 的 push/pop。
     */
    public static void apply(MatrixStack matrices, ParachuteEntity canopy, float delta, boolean reversedModel) {
        float sign = reversedModel ? -1 : 1;
        matrices.translate(0, HARNESS_HEIGHT, 0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(sign * canopy.bank(delta)));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(sign * canopy.canopyPitch(delta)));
        matrices.translate(0, -HARNESS_HEIGHT, 0);
    }
}
