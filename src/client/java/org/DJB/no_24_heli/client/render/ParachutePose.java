/** 文件：伞翼与人物共用的俯仰、横滚变换；端侧：CLIENT。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.entity.ParachuteEntity;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;

/**
 * 职责：用同一吊点和角度渲染翼面及乘员，避免左右转时人物与伞反向倾斜。
 * 引用：net.minecraft.client.util.math.MatrixStack 保留乘员的原版骨骼、皮肤和装备特征。
 * 生命周期：实体渲染和乘员 setupTransforms 每帧调用；仅渲染线程，不改变相机或物理坐标。
 * 排查：本地 +Z 是伞前方，+X 是乘员左侧；原版人物先转了 180°，故人物模型角度须反号。
 */
@Environment(EnvType.CLIENT)
public final class ParachutePose {
    /** 胸前吊点离脚 1.25 格，两种模型围绕同一世界点旋转。 */
    private static final double HARNESS_HEIGHT = 1.25;

    /** 无实例，全部姿态取自正在渲染的那具伞。 */
    private ParachutePose() {}

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
