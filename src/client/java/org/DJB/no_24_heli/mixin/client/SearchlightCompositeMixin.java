/** 文件：世界合成后的探照灯照明入口；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.DJB.no_24_heli.client.render.SearchlightRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：在 WorldRenderer.render 返回后、原版清空手持物深度前绘制探照灯，保留世界遮挡。
 * 引用：net.minecraft.client.render.GameRenderer#renderWorld 是世界与手持物之间的唯一稳定入口。
 * 原因：Fabric 世界事件仍在 Iris 的世界合成内部，部分光影实体程序忽略顶点 alpha，无法保留柔边。
 * 生命周期：主视图每帧一次；端侧仅客户端；排查：冲突查其他接管同一世界/手持物边界的模组。
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class SearchlightCompositeMixin {
    /** 功能：在世界与光影程序均返回后执行照明，手持物和 HUD 仍在其后正常绘制。 */
    @Inject(method="renderWorld",at=@At(value="INVOKE",target="Lnet/minecraft/client/render/WorldRenderer;render(Lnet/minecraft/client/render/RenderTickCounter;ZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",shift=At.Shift.AFTER))
    private void searchlightAfterWorld(RenderTickCounter counter,CallbackInfo ci){SearchlightRenderer.composite();}
}
