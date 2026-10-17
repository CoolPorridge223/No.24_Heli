/** 文件：座舱滚轮视场角；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：仅乘机第一人称替换投影基准，保留原版水下等 FOV 修饰；离机不改变用户选项。
 * 引用：net.minecraft.client.render.GameRenderer#getFov 无对应 Fabric 替换事件，因此使用返回值 Mixin。
 * 生命周期：渲染线程每帧调用；排查：第三人称受影响查透视条件，缩放失效查鼠标 GUI 作用域。
 */
@Environment(EnvType.CLIENT)
@Mixin(GameRenderer.class)
public abstract class HeliFovMixin {
    /** 功能：按座舱角与原版基准的比例缩放；客户端调用，不影响手持物投影或全局设置。 */
    @Inject(method="getFov",at=@At("RETURN"),cancellable=true)
    private void cockpitFov(Camera camera,float delta,boolean changingFov,CallbackInfoReturnable<Double> cir){
        var client=MinecraftClient.getInstance();
        if(changingFov&&client.options.getPerspective().isFirstPerson()&&client.player!=null
                &&client.player.getVehicle() instanceof HelicopterEntity)
            cir.setReturnValue(cir.getReturnValueD()*HeliView.cockpitFov()/client.options.getFov().getValue());
    }
}
