package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Keyboard;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.client.*;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 键盘重绑和鼠标重绑具有相同的乘机作用域；重复事件不会反复切换相机。 */
@Mixin(Keyboard.class)
public class HeliKeyboardMixin {
    @Inject(method="onKey",at=@At("HEAD"),cancellable=true)
    private void followCamera(long window,int key,int scan,int action,int modifiers,CallbackInfo ci){
        var client=MinecraftClient.getInstance();
        if(window!=client.getWindow().getHandle()||client.currentScreen!=null||client.player==null
                ||!(client.player.getVehicle() instanceof HelicopterEntity)||!No_24_heliClient.followKeyMatches(key,scan))return;
        if(action==GLFW.GLFW_PRESS)HeliView.toggleFollow();
        // 放行释放事件，清除登机前同键原版操作的按住状态。
        if(action!=GLFW.GLFW_RELEASE)ci.cancel();
    }
}
