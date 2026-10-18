/** 文件：目镜日间减光先于 HUD 绘制；端侧：CLIENT。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.DJB.no_24_heli.client.render.PilotEquipmentClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：在世界与 HUD 之间绘制减光，Fabric HudRenderCallback 位于 HUD 之后，无法满足此顺序。
 * 引用 net.minecraft.client.gui.hud.InGameHud#render；客户端渲染线程；排查：HUD 变暗查注入点必须 HEAD。
 */
@Environment(EnvType.CLIENT)
@Mixin(InGameHud.class)
public abstract class PilotHudMixin {
    /** 不取消 HUD，只绘制目镜的日间滤光。 */
    @Inject(method = "render", at = @At("HEAD"))
    private void filterWorld(DrawContext context, RenderTickCounter counter, CallbackInfo ci) {
        PilotEquipmentClient.drawDayFilter(context);
    }
}
