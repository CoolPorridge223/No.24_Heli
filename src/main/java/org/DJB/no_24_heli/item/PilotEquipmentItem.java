/** 文件：耳麦和目镜的专用槽位穿戴；端侧：COMMON；注册名：pilot_headset、pilot_goggles。 */
package org.DJB.no_24_heli.item;

import java.util.List;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.DJB.no_24_heli.data.PilotEquipment;

/**
 * 职责：两种装备右键装入各自专用槽，仍允许放入旧头盔槽以兼容已穿戴存档。
 * 引用：net.minecraft.entity.player.PlayerInventory 保存物品；net.minecraft.item.Equipment 兼容原头槽。
 * 生命周期：ModItems 注册；COMMON，服务端执行库存交换，客户端仅预测使用结果。
 * 排查：错槽查 inventorySlot；取下失败查绑定诅咒；不添加护甲属性或耐久消耗。
 */
public final class PilotEquipmentItem extends Item implements Equipment {
    /** 原版背包索引 41 为耳麦、42 为目镜；不是屏幕槽位编号。 */
    private final int inventorySlot;

    /** net.minecraft.item.Item.Settings 禁止堆叠；slot 由注册表指定。 */
    public PilotEquipmentItem(int slot) {
        super(new Item.Settings().maxCount(1));
        inventorySlot = slot;
    }

    /** 返回专用背包索引，供 net.minecraft.screen.slot.Slot 校验。 */
    public int inventorySlot() { return inventorySlot; }

    /** 保留 net.minecraft.item.Equipment 的头槽识别，旧存档不会丢失已戴耳麦。 */
    @Override public EquipmentSlot getSlotType() { return EquipmentSlot.HEAD; }

    /**
     * 功能：右键与专用槽交换，原版网络使用包调度到服务端线程，不接收客户端任意物品数据。
     * 引用 net.minecraft.entity.player.PlayerEntity#setStackInHand 同步手持物；绑定诅咒规则保持原版。
     * 返回原版成功/失败结果；只有服务端改变库存，创造模式也交换而不额外复制旧装备。
     */
    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);
        ItemStack old = player.getInventory().getStack(inventorySlot);
        if (!PilotEquipment.canRemove(player, old) || ItemStack.areEqual(held, old))
            return TypedActionResult.fail(held);
        if (!world.isClient) {
            player.getInventory().setStack(inventorySlot, held.copyWithCount(1));
            held.decrement(1);
            player.setStackInHand(hand, old);
            player.getInventory().markDirty();
        }
        return TypedActionResult.success(player.getStackInHand(hand), world.isClient);
    }

    /** net.minecraft.text.Text 只追加本地化穿戴及功能说明，不修改物品组件。 */
    @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        String key = inventorySlot == PilotEquipment.GOGGLES ? "pilot_goggles" : "pilot_headset";
        tooltip.add(Text.translatable("tooltip.no_24_heli." + key).formatted(Formatting.GRAY));
    }
}
