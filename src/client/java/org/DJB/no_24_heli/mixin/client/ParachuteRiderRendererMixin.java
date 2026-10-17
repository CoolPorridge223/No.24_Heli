/** 文件：乘伞人物的整体航向及倾斜；端侧：CLIENT；目标：LivingEntityRenderer。 */
package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.client.render.ParachutePose;
import org.DJB.no_24_heli.entity.ParachuteEntity;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：人物全身、背包和手持物跟随伞，视角仍可独立观察。
 * 引用：net.minecraft.client.render.entity.LivingEntityRenderer#setupTransforms 为全身骨骼前变换；
 * Fabric 特征事件只能添加模型，不能旋转原有人物，故此处必须使用 Mixin。
 * 生命周期：仅真实乘坐 ParachuteEntity 的活乘员渲染时生效，离伞立即恢复原版。
 * 排查：左右反倾查 ParachutePose 的人物共轭；身体航向偏移查原版 180-bodyYaw 的补偿。
 */
@Environment(EnvType.CLIENT)
@Mixin(LivingEntityRenderer.class)
public abstract class ParachuteRiderRendererMixin {
    /** 原版完成死亡/晃动等基本变换后，统一身体航向及吊点倾斜；不回写玩家视角字段。 */
    @Inject(method = "setupTransforms", at = @At("TAIL"))
    private void followCanopy(LivingEntity player, MatrixStack matrices, float animation, float bodyYaw,
            float delta, float scale, CallbackInfo ci) {
        if (!(player.getVehicle() instanceof ParachuteEntity canopy) || !player.isAlive()) return;
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bodyYaw - canopy.canopyYaw(delta)));
        ParachutePose.apply(matrices, canopy, delta, true);
    }

    /** 以伞的身体方向计算头部自由观察角，避免身体转向后仍使用旧身体的相对头角。 */
    @ModifyArgs(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Lnet/minecraft/entity/Entity;FFFFF)V"))
    private void relativeHead(Args args) {
        LivingEntity player = args.get(0);
        if (!(player.getVehicle() instanceof ParachuteEntity canopy)) return;
        // 第四个浮点参数是 netHeadYaw；身体跟伞，头部最多左右观察六十度。
        args.set(4, MathHelper.clamp(MathHelper.wrapDegrees(player.getHeadYaw() - canopy.canopyYaw(1)), -60f, 60f));
    }
}
