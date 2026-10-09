package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import org.DJB.no_24_heli.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 隐藏机舱内乘员模型，离座与绳降时立即恢复正常渲染。 */
@Mixin(LivingEntityRenderer.class)
public class HeliCrewRendererMixin {
    @Inject(method = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V", at = @At("HEAD"), cancellable = true)
    private void hideCrew(LivingEntity entity, float yaw, float delta, MatrixStack matrices, VertexConsumerProvider vertices, int light, CallbackInfo ci) {
        if (entity.getVehicle() instanceof HelicopterEntity) ci.cancel();
    }
}
