/** 文件：将原版移动接入直升机有向分部碰撞；端侧：COMMON；目标：Entity。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterCollision;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：只替换碰撞位移裁剪，仍由 net.minecraft.entity.Entity#move 更新位置、接地和方块回调。
 * 原版无有向盒裁剪事件，且私有 adjustMovementForCollisions 无法重写，因此需要窄入口 Mixin。
 * 生命周期：实体移动时；COMMON 主线程，直升机物理仅服务端触发；其他实体保留原版方块裁剪。
 * 排查：隐形墙查是否仍将大包围盒作为实体碰撞；不落地查返回位移，不能直接 setPos 绕过 move。
 */
@Mixin(Entity.class)
public abstract class HelicopterMovementMixin {
    /** 直升机使用连续分部扫掠，不让原版轴对齐大盒提前挡住尾梁旁的空处。 */
    @Inject(method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At("HEAD"), cancellable = true)
    private void aircraftMovement(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
        if ((Object) this instanceof HelicopterEntity heli)
            cir.setReturnValue(HelicopterCollision.adjustMovement(heli, movement));
    }

    /** 其他实体先保留 net.minecraft.entity.Entity 的原版结果，再与真实机体盒裁剪，不影响同机乘员。 */
    @Inject(method = "adjustMovementForCollisions(Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/Vec3d;",
            at = @At("RETURN"), cancellable = true)
    private void actorMovement(Vec3d movement, CallbackInfoReturnable<Vec3d> cir) {
        Entity entity = (Entity) (Object) this;
        if (!(entity instanceof HelicopterEntity)) cir.setReturnValue(HelicopterCollision.clipActor(entity, cir.getReturnValue()));
    }
}
