package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.entity.HelicopterEntity;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：隐藏乘机快捷栏，机内移除准星和原版生存读数，使仪表集中在机舱面板。
 * 引用：net.minecraft.client.gui.hud.InGameHud 管理原版覆盖层，Fabric HUD 事件无法取消这些层，故需 Mixin。
 * 生命周期：各 HUD 方法入口执行；端侧/线程：客户端渲染线程。
 * 排查：离机 HUD 不恢复查乘骑和视角条件；冲突查其他取消相同入口的 HUD 模组。
 */
@Environment(EnvType.CLIENT)
@Mixin(InGameHud.class)
public class HeliHotbarMixin {
    /**
     * 功能：机内取消屏幕飞行区覆盖层；引用 MC API：net.minecraft.client.MinecraftClient 读取玩家及 Perspective。
     * 端侧/线程：渲染线程；参数：ci 为取消回调；返回：无；副作用：取消当前覆盖层，不改玩家数据。
     * 排查：聊天、菜单、消息仍由原版显示，第一人称仪表由世界实体渲染。
     */
    @Inject(method={"renderCrosshair","renderStatusBars","renderMountHealth","renderExperienceBar","renderExperienceLevel",
            "renderHeldItemTooltip","renderStatusEffectOverlay"},at=@At("HEAD"),cancellable=true)
    private void hideCockpitOverlay(CallbackInfo ci){
        var client=MinecraftClient.getInstance();
        if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity
                &&client.options.getPerspective().isFirstPerson())ci.cancel();
    }
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
