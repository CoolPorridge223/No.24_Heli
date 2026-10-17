/** 文件：背包与工作台的皮革修伞配方；端侧：COMMON；配方：no_24_heli:parachute_repair。 */
package org.DJB.no_24_heli.item;

import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.SpecialCraftingRecipe;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.World;
import org.DJB.no_24_heli.registry.ModParachutes;

/**
 * 职责：一只可修复伞包加一至八格皮革，每张恢复 64 耐久，保留全部附魔和折叠状态。
 * 引用：net.minecraft.recipe.SpecialCraftingRecipe 支持 2×2 与 3×3 动态配方。
 * 生命周期：配方管理器匹配、制作；逻辑服务端结算材料，客户端同步预览结果。
 * 排查：附魔丢失查 copy，未叠好却变可穿戴查 CUSTOM_DATA；脱落伞包必须拒绝。
 */
public final class ParachuteRepairRecipe extends SpecialCraftingRecipe {
    /** 原版特殊配方序列化器传入配方书分类。 */
    public ParachuteRepairRecipe(CraftingRecipeCategory category) { super(category); }

    /** 格数不限制背包与工作台；必须至少两格才能放入伞包和材料。 */
    @Override public boolean fits(int width, int height) { return width * height >= 2; }

    /** 每个有皮革的格子只计一张，和原版每格消耗一件材料的制作规则一致。 */
    @Override public boolean matches(CraftingRecipeInput input, World world) {
        int packs = 0, leather = 0;
        for (ItemStack stack : input.getStacks()) {
            if (stack.isEmpty()) continue;
            if (ParachuteItem.isParachute(stack) && !ParachuteItem.isLost(stack) && stack.isDamaged()) packs++;
            else if (stack.isOf(Items.LEATHER)) leather++;
            else return false;
        }
        return packs == 1 && leather > 0;
    }

    /** 复制物品组件保留附魔、名称、已使用状态和永久 ID，修耐久不会顺带叠伞。 */
    @Override public ItemStack craft(CraftingRecipeInput input, RegistryWrapper.WrapperLookup registries) {
        ItemStack result = ItemStack.EMPTY; int leather = 0;
        for (ItemStack stack : input.getStacks()) {
            if (ParachuteItem.isParachute(stack)) result = stack.copyWithCount(1);
            else if (stack.isOf(Items.LEATHER)) leather++;
        }
        if (result.isEmpty() || ParachuteItem.isLost(result)) return ItemStack.EMPTY;
        result.setDamage(Math.max(0, result.getDamage() - leather * ParachuteItem.LEATHER_REPAIR));
        return result;
    }

    /** 返回由 ModParachutes 集中注册的序列化器。 */
    @Override public RecipeSerializer<?> getSerializer() { return ModParachutes.REPAIR_SERIALIZER; }
}
