package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.input.Input;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.DJB.no_24_heli.*;
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
        if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity){
            if(pressed&&!no24$shiftHeld&&client.currentScreen==null)
                ClientPlayNetworking.send(new ControlPayload(0,0,0,0,ControlPayload.DISMOUNT));
            sneaking=false;
        }
        no24$shiftHeld=pressed;
    }
}
