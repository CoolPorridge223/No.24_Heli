/** 文件：机内驾驶时隐藏原版手持物覆盖层；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：防止原版手臂、地图或物品悬浮在驾驶仪表前方，不改库存与交互。
 * 引用：net.minecraft.client.render.item.HeldItemRenderer 绘制第一人称手持物；Fabric 没有取消此入口的事件，故需 Mixin。
 * 生命周期：原版手持物绘制入口；端侧/线程：仅客户端渲染线程。
 * 排查：下机手臂未恢复查乘骑条件；第三人称乘员隐藏仍由 HeliCrewRendererMixin 管理。
 */
@Environment(EnvType.CLIENT)
@Mixin(HeldItemRenderer.class)
public final class HeliHandRendererMixin {
    /**
     * 功能：机内取消手持覆盖层；引用 MC API：net.minecraft.client.render.item.HeldItemRenderer#renderItem 为原版第一人称入口。
     * 端侧/线程：渲染线程；参数：delta/matrices/vertices/player/light/ci 为原版上下文；返回：无。
     * 副作用：取消当前绘制；排查：必须指定五参数入口，不能拦截实体装备物品的重载。
     */
    @Inject(method="renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
            at=@At("HEAD"),cancellable=true)
    private void hideCockpitHands(float delta, MatrixStack matrices, VertexConsumerProvider.Immediate vertices,
            ClientPlayerEntity player, int light, CallbackInfo ci) {
        if(player.getVehicle() instanceof HelicopterEntity)ci.cancel();
    }
}
