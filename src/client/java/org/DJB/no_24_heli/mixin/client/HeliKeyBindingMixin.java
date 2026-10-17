package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;

/** 视角键由乘机输入事件单独消费，不进入原版单键映射，防止覆盖选取方块等绑定。 */
@Mixin(KeyBinding.class)
public class HeliKeyBindingMixin {
    @Shadow @Final private static Map<String,KeyBinding> KEYS_BY_ID;
    @Shadow @Final private static Map<InputUtil.Key,KeyBinding> KEY_TO_BINDINGS;
    @Shadow private InputUtil.Key boundKey;
    @Inject(method="updateKeysByCode",at=@At("TAIL"))
    private static void restoreSharedKey(CallbackInfo ci){no24$restore();}
    @Inject(method="<init>(Ljava/lang/String;Lnet/minecraft/client/util/InputUtil$Type;ILjava/lang/String;)V",at=@At("TAIL"))
    private void registerSharedKey(String name,InputUtil.Type type,int code,String category,CallbackInfo ci){no24$restore();}
    @Unique private static void no24$restore(){
        no24$restoreBinding("key.no_24_heli.follow");
        no24$restoreBinding("key.no_24_heli.parachute");
    }
    /** 按键在各自作用域由 Keyboard 回调消费，不能占据原版 F/中键的全局查找表。 */
    @Unique private static void no24$restoreBinding(String id){
        var follow=KEYS_BY_ID.get(id);if(follow==null)return;
        var key=((HeliKeyBindingMixin)(Object)follow).boundKey;
        if(KEY_TO_BINDINGS.get(key)!=follow)return;
        KEY_TO_BINDINGS.remove(key);
        for(var binding:KEYS_BY_ID.values())if(binding!=follow
                &&!binding.getTranslationKey().equals("key.no_24_heli.follow")
                &&!binding.getTranslationKey().equals("key.no_24_heli.parachute")
                &&((HeliKeyBindingMixin)(Object)binding).boundKey.equals(key))
            KEY_TO_BINDINGS.put(key,binding);
    }
}
