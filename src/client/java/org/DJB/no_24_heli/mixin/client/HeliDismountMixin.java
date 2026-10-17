package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;
import org.DJB.no_24_heli.network.ControlPayload;


import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.input.Input;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在原版玩家消费输入前截住蹲下，仅按键上升沿请求下机，确认计时由服务器维护。 */
@Mixin(KeyboardInput.class)
public class HeliDismountMixin extends Input {
    @Unique private boolean no24$shiftHeld;
    @Inject(method="tick",at=@At("TAIL"))
    private void confirmExit(boolean slowDown,float factor,CallbackInfo ci){
        var client=MinecraftClient.getInstance();boolean pressed=sneaking;
        // 滑翔伞自动着陆收起；空中蹲下不作为未经确认的切伞，避免误触直接坠落。
        if(client.player!=null&&client.player.getVehicle() instanceof org.DJB.no_24_heli.entity.ParachuteEntity){
            sneaking=false;jumping=false;client.player.getAbilities().flying=false;
        }
        if(client.player!=null&&(client.player.getVehicle() instanceof HelicopterEntity||client.player.getVehicle() instanceof RopeSeatEntity)){
            if(pressed&&!no24$shiftHeld&&client.currentScreen==null)
                ClientPlayNetworking.send(new ControlPayload(0,0,0,0,ControlPayload.DISMOUNT));
            sneaking=false;
            // 原版双击跳跃在消费 Input 前被阻止；直升机总距直接读取 jumpKey，不受影响。
            jumping=false;
            client.player.getAbilities().flying=false;
        }
        no24$shiftHeld=pressed;
    }
}
