/** 文件：阻止原版双物品修复洗掉叠伞状态；端侧：COMMON；目标：RepairItemRecipe。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.recipe.RepairItemRecipe;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.world.World;
import org.DJB.no_24_heli.item.ParachuteItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：伞包只能走保留组件的皮革配方，防止原版双物品合成新建物品绕过折叠/永久损坏。
 * 引用：net.minecraft.recipe.RepairItemRecipe 会新建没有 CUSTOM_DATA 的结果；
 * 没有配方事件可从中排除单个物品，因此仅对含伞包的该配方注入，不改变其他物品修复。
 * 生命周期：两端配方匹配调用；排查修复入口应使用 no_24_heli:parachute_repair。
 */
@Mixin(RepairItemRecipe.class)
public abstract class ParachuteVanillaRepairMixin {
    /** 原版所有非伞包修复配方照常匹配。 */
    @Inject(method = "matches(Lnet/minecraft/recipe/input/CraftingRecipeInput;Lnet/minecraft/world/World;)Z",
            at = @At("HEAD"), cancellable = true)
    private void preservePackState(CraftingRecipeInput input, World world, CallbackInfoReturnable<Boolean> result) {
        if (input.getStacks().stream().anyMatch(ParachuteItem::isParachute)) result.setReturnValue(false);
    }
}
