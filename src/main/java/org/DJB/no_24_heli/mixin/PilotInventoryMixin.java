/** 文件：为原版玩家背包追加两个专用装备槽；端侧：COMMON；存档 Inventory.Slot=200/201。 */
package org.DJB.no_24_heli.mixin;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.collection.DefaultedList;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：扩展背包组合列表，复用原版 get/set/remove、clone、clear、dropAll，避免另存一套易丢失的库存。
 * 引用：net.minecraft.entity.player.PlayerInventory 无扩槽事件，因此使用窄范围 Mixin。
 * 生命周期：背包构造与 NBT 读写；COMMON，世界主线程；不改变 main/armor/offHand 的长度。
 * 排查：旧槽错位查只在末尾追加；死亡复制查 size；存档非法内容拒绝并记录日志。
 */
@Mixin(PlayerInventory.class)
public abstract class PilotInventoryMixin {
    /** 原版组合列表控制索引寻址、掉落与清空；只替换列表容器，不替换原有子列表。 */
    @Shadow @Final @Mutable private List<DefaultedList<ItemStack>> combinedInventory;
    /** 原版所属玩家提供动态物品注册表。 */
    @Shadow @Final public PlayerEntity player;
    /** 两个新增槽的真实物品存储，跨重生由原版 clone(size) 复制。 */
    @Unique private DefaultedList<ItemStack> heli$equipment;

    /** 构造完成后追加，原版 0–40 所有索引保持不变。 */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void appendSlots(PlayerEntity owner, CallbackInfo ci) {
        heli$equipment = DefaultedList.ofSize(2, ItemStack.EMPTY);
        combinedInventory = new ArrayList<>(combinedInventory);
        combinedInventory.add(heli$equipment);
    }

    /** net.minecraft.entity.player.PlayerInventory#size 显式加总原三组，因此只补两格。 */
    @Inject(method = "size", at = @At("RETURN"), cancellable = true)
    private void extendedSize(CallbackInfoReturnable<Integer> cir) { cir.setReturnValue(cir.getReturnValue() + 2); }

    /** 原版 NBT 只遍历原三组，额外写入稳定的 200/201 编号，完整保留物品组件。 */
    @Inject(method = "writeNbt", at = @At("RETURN"))
    private void saveEquipment(NbtList list, CallbackInfoReturnable<NbtList> cir) {
        for (int i = 0; i < 2; i++) if (!heli$equipment.get(i).isEmpty()) {
            NbtCompound entry = new NbtCompound();
            entry.putByte("Slot", (byte) (PilotEquipment.NBT_SLOT + i));
            list.add(heli$equipment.get(i).encode(player.getRegistryManager(), entry));
        }
    }

    /** NBT 属于系统边界；旧存档默认空槽，错误类型和堆叠数量记录后丢弃。 */
    @Inject(method = "readNbt", at = @At("TAIL"))
    private void loadEquipment(NbtList list, CallbackInfo ci) {
        heli$equipment.clear();
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            int index = (entry.getByte("Slot") & 255) - PilotEquipment.NBT_SLOT;
            if (index < 0 || index >= 2) continue;
            ItemStack stack = ItemStack.fromNbtOrEmpty(player.getRegistryManager(), entry);
            if (PilotEquipment.accepts(PilotEquipment.HEADSET + index, stack)) heli$equipment.set(index, stack);
            else No_24_heli.LOGGER.warn("拒绝玩家 {} 专用头槽 {} 的非法物品", player.getUuid(), index);
        }
    }
}
