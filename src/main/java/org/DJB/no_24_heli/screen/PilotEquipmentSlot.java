/** 文件：耳麦/目镜专用槽校验；端侧：COMMON；物品：pilot_headset、pilot_goggles。 */
package org.DJB.no_24_heli.screen;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.DJB.no_24_heli.data.PilotEquipment;

/**
 * 职责：限制每格只容纳对应装备一件；引用 net.minecraft.screen.slot.Slot 复用原版点击和同步。
 * 生命周期：玩家屏幕处理器构造；COMMON，库存主线程；排查：错槽查 index，取下查绑定诅咒。
 */
public final class PilotEquipmentSlot extends Slot {
    /** 真实背包索引，与屏幕索引区分。 */
    private final int equipmentIndex;
    /** net.minecraft.screen.slot.Slot 提供同步；坐标是生存背包人物右侧的两格。 */
    public PilotEquipmentSlot(PlayerInventory inventory, int index, int x, int y) {
        super(inventory, index, x, y);
        equipmentIndex = index;
    }
    /** 只接受对应的耳麦或目镜，拖放、交换与快捷移动均经过此检查。 */
    @Override public boolean canInsert(ItemStack stack) { return PilotEquipment.accepts(equipmentIndex, stack); }
    /** 独立装备槽只放一件。 */
    @Override public int getMaxItemCount() { return 1; }
    /** net.minecraft.enchantment.EnchantmentHelper 的原版绑定规则由共用方法检查。 */
    @Override public boolean canTakeItems(PlayerEntity player) { return PilotEquipment.canRemove(player, getStack()); }
}
