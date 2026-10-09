package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import org.DJB.no_24_heli.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 仅驾驶时拦截原版 Q 丢弃和 E 背包，离机后完整保留原版输入。 */
@Mixin(MinecraftClient.class)
public class DrivingInputMixin {
    @Redirect(method="handleInputEvents",at=@At(value="INVOKE",target="Lnet/minecraft/client/option/KeyBinding;wasPressed()Z"))
    private boolean no24$flightKey(KeyBinding key){
        MinecraftClient client=(MinecraftClient)(Object)this;
        if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli&&heli.isPilot(client.player)&&
                ((key==client.options.inventoryKey&&key.matchesKey(69,0))||(key==client.options.dropKey&&key.matchesKey(81,0)))){
            while(key.wasPressed()){}return false;
        }
        return key.wasPressed();
    }
}
