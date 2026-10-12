package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.client.HeliView;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 乘机时中键切换跟随；界面中和下机后保留原版中键行为。 */
@Mixin(Mouse.class)
public class HeliMouseMixin {
    /** 第三人称乘机滚轮只调相机，不再切换隐藏的快捷栏；界面内仍保留原版滚动。 */
    @Inject(method="onMouseScroll",at=@At("HEAD"),cancellable=true)
    private void zoomCamera(long window,double horizontal,double vertical,CallbackInfo ci){
        var client=MinecraftClient.getInstance();
        if(window==client.getWindow().getHandle()&&client.currentScreen==null&&client.player!=null
                &&client.player.getVehicle() instanceof HelicopterEntity&&!client.options.getPerspective().isFirstPerson()){
            HeliView.zoom(vertical);ci.cancel();
        }
    }
    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void followCamera(long window, int button, int action, int modifiers, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (window != client.getWindow().getHandle() || client.currentScreen != null || client.player == null
                || !(client.player.getVehicle() instanceof HelicopterEntity) || !org.DJB.no_24_heli.client.No_24_heliClient.followMouseMatches(button)) return;
        if (action == GLFW.GLFW_PRESS) HeliView.toggleFollow();
        ci.cancel();
    }
}
