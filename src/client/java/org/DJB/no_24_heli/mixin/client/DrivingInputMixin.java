package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 驾驶时拦截 Q/E；绳上 E 用于入副驾，离开载具后恢复原版输入。 */
@Mixin(MinecraftClient.class)
public class DrivingInputMixin {
    @Redirect(method="handleInputEvents",at=@At(value="INVOKE",target="Lnet/minecraft/client/option/KeyBinding;wasPressed()Z"))
    private boolean no24$flightKey(KeyBinding key){
        MinecraftClient client=(MinecraftClient)(Object)this;
        if(client.player!=null&&client.player.getVehicle() instanceof RopeSeatEntity
                &&key==client.options.inventoryKey&&key.matchesKey(69,0)){
            while(key.wasPressed()){}return false;
        }
        if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli&&heli.isPilot(client.player)&&
                ((key==client.options.inventoryKey&&key.matchesKey(69,0))||(key==client.options.dropKey&&key.matchesKey(81,0)))){
            while(key.wasPressed()){}return false;
        }
        return key.wasPressed();
    }
}
