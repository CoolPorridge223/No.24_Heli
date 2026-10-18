/** 文件：目镜夜视强度与原版药水合并；端侧：CLIENT。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import org.DJB.no_24_heli.client.render.PilotEquipmentClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：只为夜间目镜提供视觉强度，不添加或删除 net.minecraft.entity.effect.StatusEffectInstance。
 * 原版无夜视强度事件，故注入 net.minecraft.client.render.GameRenderer；客户端渲染线程。
 * 排查：药水被削弱查取 max；目镜取下后原药水完全保留，光影包是否采纳此值由其实现决定。
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class PilotVisionMixin {
    /** 没有原版药水时避免原方法取空效果；有药水则保留其计算并在返回值合并。 */
    @Inject(method = "getNightVisionStrength", at = @At("HEAD"), cancellable = true)
    private static void gogglesOnly(LivingEntity entity, float delta, CallbackInfoReturnable<Float> cir) {
        if (entity instanceof PlayerEntity player && !entity.hasStatusEffect(StatusEffects.NIGHT_VISION)) {
            float strength = PilotEquipmentClient.nightStrength(player);
            if (strength > 0) cir.setReturnValue(strength);
        }
    }
    /** 原版药水与目镜叠加取较强值，绝不覆盖药水时长及属性。 */
    @Inject(method = "getNightVisionStrength", at = @At("RETURN"), cancellable = true)
    private static void combineVision(LivingEntity entity, float delta, CallbackInfoReturnable<Float> cir) {
        if (entity instanceof PlayerEntity player)
            cir.setReturnValue(Math.max(cir.getReturnValue(), PilotEquipmentClient.nightStrength(player)));
    }
}
