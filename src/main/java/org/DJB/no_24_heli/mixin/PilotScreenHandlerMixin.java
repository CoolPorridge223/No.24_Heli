/** 文件：生存背包增加耳麦和目镜槽；端侧：COMMON；槽位 46、47。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.DJB.no_24_heli.item.PilotEquipmentItem;
import org.DJB.no_24_heli.screen.PilotEquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：补充原版玩家容器的两个槽与专用 Shift 移动；原版无追加槽事件，故使用 Mixin。
 * 引用 net.minecraft.screen.ScreenHandler#insertItem 使用原版计数和槽校验；生命周期：容器构造/点击。
 * COMMON，服务端权威；排查：客户端与服务端必须都有相同追加槽，旧槽编号保持原样。
 */
@Mixin(PlayerScreenHandler.class)
public abstract class PilotScreenHandlerMixin extends ScreenHandler {
    /** 仅满足父类构造签名，不单独实例化 Mixin。 */
    protected PilotScreenHandlerMixin(ScreenHandlerType<?> type, int syncId) { super(type, syncId); }

    /** 原版已有 46 格之后追加；人物右边、原副手槽上方，避开合成区域。 */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void appendSlots(PlayerInventory inventory, boolean onServer, PlayerEntity owner, CallbackInfo ci) {
        addSlot(new PilotEquipmentSlot(inventory, PilotEquipment.HEADSET, 77, 8));
        addSlot(new PilotEquipmentSlot(inventory, PilotEquipment.GOGGLES, 77, 26));
    }

    /**
     * 功能：新增槽 Shift 取回主背包；背包里的耳麦/目镜优先进入对应空槽。
     * net.minecraft.screen.slot.Slot#onTakeItem 保持原版回调，合成结果不拦截以免漏扣材料。
     */
    @Inject(method = "quickMove", at = @At("HEAD"), cancellable = true)
    private void quickMoveEquipment(PlayerEntity player, int index, CallbackInfoReturnable<ItemStack> cir) {
        Slot source = slots.get(index);
        if (!source.hasStack() || !source.canTakeItems(player)) return;
        ItemStack stack = source.getStack(), original = stack.copy();
        boolean moved;
        if (index >= PilotEquipment.FIRST_SCREEN_SLOT && index < PilotEquipment.FIRST_SCREEN_SLOT + 2) {
            moved = insertItem(stack, 9, 45, false);
        } else if (index >= 9 && index < 46 && stack.getItem() instanceof PilotEquipmentItem item) {
            int destination = PilotEquipment.FIRST_SCREEN_SLOT + item.inventorySlot() - PilotEquipment.HEADSET;
            if (slots.get(destination).hasStack()) return;
            moved = insertItem(stack, destination, destination + 1, false);
        } else return;
        if (!moved) { cir.setReturnValue(ItemStack.EMPTY); return; }
        if (stack.isEmpty()) source.setStack(ItemStack.EMPTY); else source.markDirty();
        source.onTakeItem(player, stack);
        cir.setReturnValue(original);
    }
}
