/** 文件：拉人双臂及趴地眩晕的人物姿态；端侧：CLIENT；目标：PlayerEntityRenderer。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import org.DJB.no_24_heli.client.camera.CabinActionView;
import org.DJB.no_24_heli.entity.CabinPlayerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：趴地采用完整身体水平变换，拉人者双手前伸后拉；衣服与装备复用原版骨骼。
 * 引用 net.minecraft.client.render.entity.PlayerEntityRenderer#setupTransforms 替换游泳旋转，避免矮碰撞箱画成游泳。
 * 生命周期：仅动作参与者渲染时执行；端侧/线程：CLIENT 渲染线程，不改玩家物理或相机。
 * 排查：身体埋地查 .16 格垫高；袖腿不同步查 copyTransform；动作结束由原版 setAngles 恢复。
 */
@Environment(EnvType.CLIENT)
@Mixin(PlayerEntityRenderer.class)
public abstract class CabinActionRendererMixin {
    /** 趴地替换原版游泳变换，脚位上垫 0.16 格并朝外水平放平；结束后原版自动恢复。 */
    @Inject(method = "setupTransforms(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/util/math/MatrixStack;FFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void cabin$prone(AbstractClientPlayerEntity player, MatrixStack matrices, float animation,
            float bodyYaw, float delta, float scale, CallbackInfo ci) {
        CabinPlayerState state = (CabinPlayerState) player;
        if (!state.cabinStunned()) return;
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 - state.cabinStunYaw()));
        matrices.translate(0, .16, 0);
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-90));
        ci.cancel();
    }

    /** 拉人者始终朝向被拖者，略微后仰；不使用鼠标头部角度决定身体方向。 */
    @Inject(method = "setupTransforms(Lnet/minecraft/client/network/AbstractClientPlayerEntity;Lnet/minecraft/client/util/math/MatrixStack;FFFF)V",
            at = @At("TAIL"))
    private void cabin$pullerTransform(AbstractClientPlayerEntity player, MatrixStack matrices, float animation,
            float bodyYaw, float delta, float scale, CallbackInfo ci) {
        if (((CabinPlayerState) player).cabinPullTarget() == null) return;
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bodyYaw - CabinActionView.facing(player, delta)));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-12));
    }

}
