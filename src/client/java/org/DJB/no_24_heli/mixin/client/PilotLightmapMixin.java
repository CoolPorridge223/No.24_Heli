/** 文件：让目镜进入原版夜视光照分支；端侧：CLIENT。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import org.DJB.no_24_heli.client.render.PilotEquipmentClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 职责：只扩展 net.minecraft.client.render.LightmapTextureManager 的夜视判断，无对应 Fabric 事件。
 * 生命周期：光照图更新；客户端渲染线程；排查：潮涌能量等其他效果必须继续调用原方法。
 */
@Environment(EnvType.CLIENT)
@Mixin(LightmapTextureManager.class)
public abstract class PilotLightmapMixin {
    /** 仅 NIGHT_VISION 查询允许目镜通过，原效果仍独立存在。 */
    @Redirect(method = "update", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/network/ClientPlayerEntity;hasStatusEffect(Lnet/minecraft/registry/entry/RegistryEntry;)Z"))
    private boolean hasVision(ClientPlayerEntity player, RegistryEntry<StatusEffect> effect) {
        return player.hasStatusEffect(effect) || effect == StatusEffects.NIGHT_VISION
                && PilotEquipmentClient.nightStrength(player) > 0;
    }
}
