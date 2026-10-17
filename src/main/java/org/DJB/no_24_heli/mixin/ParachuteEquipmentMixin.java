/** 文件：禁止未叠伞包进入胸甲槽；端侧：COMMON；目标：LivingEntity。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import org.DJB.no_24_heli.item.ParachuteItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：原版胸槽拖入、Shift 快速移动均必须遵守折叠条件；已穿着的使用后伞包保留在胸槽。
 * 引用：net.minecraft.entity.LivingEntity#getPreferredEquipmentSlot 是原版装备槽的准入依据。
 * 生命周期：原版查询时调用；两端相同，无库存事件可统一拦住所有准入路径，故采用 Mixin。
 * 排查：只影响本模组未折叠物品，不改变其他装备、现有胸槽取出或玩家其他操作。
 */
@Mixin(LivingEntity.class)
public abstract class ParachuteEquipmentMixin {
    /** 未叠好时作为普通手持物，原版胸甲槽的 canInsert 因槽位不匹配而拒绝。 */
    @Inject(method = "getPreferredEquipmentSlot", at = @At("HEAD"), cancellable = true)
    private void requirePacked(ItemStack stack, CallbackInfoReturnable<EquipmentSlot> result) {
        if (ParachuteItem.isParachute(stack) && !ParachuteItem.isPacked(stack)) result.setReturnValue(EquipmentSlot.MAINHAND);
    }
}
