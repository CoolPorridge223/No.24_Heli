/** 文件：服务端关闭原版蹲下直脱离旁路；端侧：COMMON；目标：PlayerEntity.shouldDismount。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.entity.player.PlayerEntity;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：禁止直升机 Shift 下机，不能由原版移动包直接解除乘骑；F 仍经过舱门与确认规则。
 * 引用 net.minecraft.entity.player.PlayerEntity#shouldDismount 是 tickRiding 的自动蹲下脱离判定。
 * 生命周期：乘骑 tick 调用；逻辑两端，不拦截死亡、移除、传送或显式 stopRiding。
 * Fabric 无对应可取消事件，故需 Mixin；排查：只应限制直升机，其他载具保持原版返回值。
 */
@Mixin(PlayerEntity.class)
public abstract class HelicopterDismountMixin {
    /** 关闭自动蹲下旁路；飞机下机改由 CabinDoorPayload 的 F 请求，经服务端校验并播放动画。 */
    @Inject(method = "shouldDismount", at = @At("HEAD"), cancellable = true)
    private void requireCabinExit(CallbackInfoReturnable<Boolean> cir) {
        if (((PlayerEntity) (Object) this).getVehicle() instanceof HelicopterEntity) cir.setReturnValue(false);
    }
}
