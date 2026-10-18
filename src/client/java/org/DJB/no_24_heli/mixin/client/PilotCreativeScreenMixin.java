/** 文件：创造模式生存物品栏的两件飞行装备布局；端侧：CLIENT。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 职责：原版创造背包会包装玩家全部槽，但按原 46 格计算位置；只纠正新增槽坐标。
 * 引用 net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen，无布局扩槽事件故需 Mixin。
 * 生命周期：切到生存物品栏页签；客户端线程；排查：不得改动原护甲、副手和快捷栏坐标。
 */
@Environment(EnvType.CLIENT)
@Mixin(CreativeInventoryScreen.class)
public abstract class PilotCreativeScreenMixin {
    /** 新槽在人物右边第二列，避开原护甲槽 x108 和销毁槽 x173。 */
    @ModifyArgs(method = "setSelectedTab", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/screen/ingame/CreativeInventoryScreen$CreativeSlot;<init>(Lnet/minecraft/screen/slot/Slot;III)V"))
    private void positionEquipment(Args args) {
        int index = args.get(1);
        if (index >= PilotEquipment.FIRST_SCREEN_SLOT && index < PilotEquipment.FIRST_SCREEN_SLOT + 2) {
            args.set(2, 134);
            args.set(3, 6 + (index - PilotEquipment.FIRST_SCREEN_SLOT) * 27);
        }
    }
}
