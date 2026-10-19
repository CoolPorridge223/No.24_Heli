package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.client.No_24_heliClient;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.client.input.ParachuteClient;
import org.DJB.no_24_heli.entity.HelicopterEntity;


import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Keyboard;
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
        // 舱门优先处理机内/侧门场景，离舱后 F 才交给开伞。
        if(window==client.getWindow().getHandle()&&org.DJB.no_24_heli.client.input.CabinDoorClient.handleKey(key,scan,action)){ci.cancel();return;}
        // 新伞键只在自由落体/开伞状态接管，原版 F 交换副手在其他场景照常使用。
        if(window==client.getWindow().getHandle()&&ParachuteClient.handleKey(key,scan,action)){ci.cancel();return;}
        if(window!=client.getWindow().getHandle()||client.currentScreen!=null||client.player==null
                ||!(client.player.getVehicle() instanceof HelicopterEntity)||client.options.getPerspective().isFirstPerson()
                ||!No_24_heliClient.followKeyMatches(key,scan))return;
        if(action==GLFW.GLFW_PRESS)HeliView.toggleFollow();
        // 放行释放事件，清除登机前同键原版操作的按住状态。
        if(action!=GLFW.GLFW_RELEASE)ci.cancel();
    }
}
