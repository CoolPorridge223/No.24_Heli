package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.entity.HelicopterEntity;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 仅隐藏乘机期间的快捷物品栏，不修改背包数据，也不隐藏飞行仪表。 */
@Mixin(InGameHud.class)
public class HeliHotbarMixin {
    /** 乘机时Tab专用于电台圆盘，拦截原版玩家列表；下机后原版Tab立即恢复。 */
    @Inject(method="renderPlayerList",at=@At("HEAD"),cancellable=true)
    private void hidePlayerList(DrawContext context,RenderTickCounter counter,CallbackInfo ci){
        var player=MinecraftClient.getInstance().player;
        if(player!=null&&player.getVehicle() instanceof HelicopterEntity)ci.cancel();
    }
    @Inject(method="renderHotbar",at=@At("HEAD"),cancellable=true)
    private void hideHotbar(DrawContext context,RenderTickCounter counter,CallbackInfo ci){
        var player=MinecraftClient.getInstance().player;
        if(player!=null&&player.getVehicle() instanceof HelicopterEntity)ci.cancel();
    }
}
