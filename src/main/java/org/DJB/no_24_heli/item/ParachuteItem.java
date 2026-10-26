/** 文件：胸甲槽伞包；端侧：COMMON；注册名：no_24_heli:parachute。 */
package org.DJB.no_24_heli.item;

import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Equipment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.UseAction;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import java.util.List;
import java.util.UUID;

/**
 * 职责：提供可右键穿戴的伞包，不赋予胸甲防御或鞘翅飞行能力。
 * 引用：net.minecraft.item.Equipment 使原版装备槽和快捷穿戴识别胸部装备。
 * 生命周期：ModParachutes 初始化时注册；库存操作由原版逻辑服务端处理。
 * 排查：无法放入胸槽查 getSlotType；右键换装查 equipPacked，创造穿戴也必须移走原手持物品。
 */
public final class ParachuteItem extends Item implements Equipment {
    /** 耐久 256；开伞耗四点、飞行每五秒耗一点，由原版 ItemStack.damage 应用耐久附魔。 */
    public static final int DURABILITY = 256, DEPLOY_DAMAGE = 4, WEAR_INTERVAL = 100;
    /** 连续使用五秒叠伞；每格一张皮革修复最大耐久的四分之一。 */
    public static final int FOLD_TICKS = 100, LEATHER_REPAIR = DURABILITY / 4;
    /** 状态保存在原版 CUSTOM_DATA，不覆盖其他模组或命令添加的自定义数据。 */
    private static final String PACKED = "ParachutePacked", LOST = "ParachuteWingLost", ID = "ParachuteId";

    /** net.minecraft.item.Item.Settings 提供原版耐久条和不可堆叠属性。 */
    public ParachuteItem() { super(new Item.Settings().maxDamage(DURABILITY)); }

    /** 通过物品类型判断，避免静态注册期间反向加载注册类。 */
    public static boolean isParachute(ItemStack stack) { return stack.getItem() instanceof ParachuteItem; }

    /** 旧物品没有状态键时视为已叠好；已脱落的伞包永远不能再次使用。 */
    public static boolean isPacked(ItemStack stack) {
        NbtCompound nbt = data(stack);
        return isParachute(stack) && !nbt.getBoolean(LOST) && (!nbt.contains(PACKED) || nbt.getBoolean(PACKED));
    }

    /** 永久损坏标志不能被修复配方或经验修补清除。 */
    public static boolean isLost(ItemStack stack) { return isParachute(stack) && data(stack).getBoolean(LOST); }

    /** 原版右键使用仅对未折叠且仍有伞面的伞包启动。 */
    public static boolean canFold(ItemStack stack) { return isParachute(stack) && !isPacked(stack) && !isLost(stack); }

    /** 功能：物品名称与三种手持模型对应；原版 ItemStack 仍保留同一注册名、耐久、附魔和自定义名称。 */
    @Override public Text getName(ItemStack stack){
        if(isLost(stack))return Text.translatable("item.no_24_heli.parachute_broken");
        if(!isPacked(stack))return Text.translatable("item.no_24_heli.parachute_open");
        return super.getName(stack);
    }

    /** 原版 NbtComponent 返回安全副本，读写不会破坏物品组件共享。 */
    private static NbtCompound data(ItemStack stack) {
        return stack.getOrDefault(DataComponentTypes.CUSTOM_DATA, NbtComponent.DEFAULT).copyNbt();
    }

    /** 每次成功开伞设置唯一连接 ID；取下后其他物品不能冒充这具实体的伞包。 */
    public static void markUsed(ItemStack stack, UUID id) {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
            nbt.putBoolean(PACKED, false); nbt.putUuid(ID, id);
        });
    }

    /** 原版 CUSTOM_DATA 保存的伞包 ID，未使用过或旧物品没有 ID。 */
    public static UUID packId(ItemStack stack) {
        NbtCompound nbt = data(stack); return nbt.containsUuid(ID) ? nbt.getUuid(ID) : null;
    }

    /**
     * 第三类故障永久失去伞面。移除 MAX_DAMAGE/DAMAGE 使原版 XP 修补不再选择它，
     * 同时阻止铁砧把这个失去伞面的物品当作可修复装备；附魔和其他数据保留。
     */
    public static void loseWing(ItemStack stack) {
        NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
            nbt.putBoolean(LOST, true); nbt.putBoolean(PACKED, false);
        });
        stack.remove(DataComponentTypes.MAX_DAMAGE); stack.remove(DataComponentTypes.DAMAGE);
    }

    /** 原版 net.minecraft.item.Equipment 将物品限定在胸甲槽。 */
    @Override public EquipmentSlot getSlotType() { return EquipmentSlot.CHEST; }

    /** 未折叠时开始叠伞；已折叠时复用原版槽位、绑定诅咒检查及穿戴同步，并消除创造复制。 */
    @Override public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (canFold(stack)) {
            player.setCurrentHand(hand); return TypedActionResult.consume(stack);
        }
        return isPacked(stack) ? equipPacked(world, player, hand) : TypedActionResult.fail(stack);
    }

    /**
     * 功能：穿戴原手持伞包，胸槽占用时把原装备交换到同一手；创造模式也只保留一件原伞包。
     * 引用 net.minecraft.item.Equipment#equipAndSwap 保留槽位许可、绑定诅咒、物品统计和两端预测。
     * 端侧：沿用原版客户端预测及服务端权威；仅成功后清空原版创造分支留下的手持源对象。
     * 排查：空胸槽必须返回 EMPTY，交换必须返回旧胸甲，不能把已穿戴的新伞包再次返回手中。
     */
    private TypedActionResult<ItemStack> equipPacked(World world, PlayerEntity player, Hand hand) {
        ItemStack held = player.getStackInHand(hand);
        TypedActionResult<ItemStack> result = equipAndSwap(this, world, player, hand);
        if (result.getResult().isAccepted() && player.isCreative()) {
            // 原版创造分支复制到胸槽但不消耗来源；成功后消费来源，保留完整组件的胸槽副本。
            held.setCount(0);
            if (result.getValue() == held) return TypedActionResult.success(ItemStack.EMPTY, world.isClient);
        }
        return result;
    }

    /** 原版连续使用计时；松开右键会丢弃本次进度，无累计到物品的计时数据。 */
    @Override public int getMaxUseTime(ItemStack stack, LivingEntity user) { return FOLD_TICKS; }

    /** 叠伞没有饮食/拉弓动作，手持装备由圆圈 HUD 表现使用进度。 */
    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.NONE; }

    /**
     * 服务端叠好后仅在胸槽为空时自动穿戴并返回空手；占用时留在原手持栏，不交换或复制其他装备。
     * net.minecraft.entity.LivingEntity 的使用结束流程将返回值写回当前手，避免胸槽和手上同时留一件。
     */
    @Override public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
        if (!world.isClient && canFold(stack)) {
            NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putBoolean(PACKED, true));
            if (user instanceof PlayerEntity player && player.getEquippedStack(EquipmentSlot.CHEST).isEmpty())
                return equipPacked(world, player, player.getActiveHand()).getValue();
        }
        return stack;
    }

    /** 原版附魔台获得耐久系列；经验修补按原版规则通过附魔书和铁砧添加。 */
    @Override public int getEnchantability() { return 10; }

    /** 永久脱落伞包不能再附魔，正常物品沿用原版单件、可损耗及已有附魔判定。 */
    @Override public boolean isEnchantable(ItemStack stack) { return !isLost(stack) && super.isEnchantable(stack); }

    /** 原版铁砧材料修复也使用皮革；永久脱落不接受修复。 */
    @Override public boolean canRepair(ItemStack stack, ItemStack ingredient) {
        return !isLost(stack) && ingredient.isOf(Items.LEATHER);
    }

    /** 在原版耐久条之外说明折叠和永久损坏状态，玩家可判断为什么不能穿戴。 */
    @Override public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> lines, TooltipType type) {
        if (isLost(stack)) lines.add(Text.translatable("item.no_24_heli.parachute_lost").formatted(Formatting.RED));
        else if (!isPacked(stack)) lines.add(Text.translatable("item.no_24_heli.parachute_unpacked").formatted(Formatting.GOLD));
    }
}
