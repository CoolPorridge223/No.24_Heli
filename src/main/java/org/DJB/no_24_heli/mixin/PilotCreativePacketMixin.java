/** 文件：创造背包新增槽的服务端边界；端侧：SERVER；原版 CreativeInventoryActionC2SPacket。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：原版创造包写槽上限从 45 扩至 47，并校验新增两格的物品类型和数量。
 * 引用 net.minecraft.network.NetworkThreadUtils 确保服务端主线程；没有扩槽网络事件，故使用 Mixin。
 * 生命周期：创造模式库存包；SERVER；排查：拒绝记录查槽位/模式/物品，不放开任意背包索引。
 */
@Mixin(ServerPlayNetworkHandler.class)
public abstract class PilotCreativePacketMixin {
    /** 原版网络连接所属玩家，不接受包内自选玩家身份。 */
    @Shadow public ServerPlayerEntity player;

    /** 原版固定上限仅在本包入口扩展，其他容器编号保持不变。 */
    @ModifyConstant(method = "onCreativeInventoryAction", constant = @Constant(intValue = 45))
    private int extendedLimit(int original) { return PilotEquipment.FIRST_SCREEN_SLOT + 1; }

    /** 新槽属于系统边界：调度主线程后拒绝生存伪包、错误类型、过量堆叠与越界索引。 */
    @Inject(method = "onCreativeInventoryAction", at = @At("HEAD"), cancellable = true)
    private void validateSlots(CreativeInventoryActionC2SPacket packet, CallbackInfo ci) {
        if (packet.slot() < PilotEquipment.FIRST_SCREEN_SLOT) return;
        NetworkThreadUtils.forceMainThread(packet, (ServerPlayNetworkHandler) (Object) this, player.getServerWorld());
        int index = packet.slot() - PilotEquipment.FIRST_SCREEN_SLOT;
        if (!player.isCreative() || index >= 2 || !PilotEquipment.accepts(PilotEquipment.HEADSET + index, packet.stack())) {
            No_24_heli.LOGGER.warn("拒绝玩家 {} 的非法飞行装备创造包：槽 {}", player.getUuid(), packet.slot());
            ci.cancel();
        }
    }
}
