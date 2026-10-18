/** 文件：直升机分部位近战选取与投射物射线；端侧：COMMON；目标：ProjectileUtil。 */
package org.DJB.no_24_heli.mixin;

import java.util.function.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.DJB.no_24_heli.entity.HelicopterCollision;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 职责：原版其他实体仍按既有射线检测；直升机从独立候选查询中取最近真实分部面。
 * 引用 net.minecraft.entity.projectile.ProjectileUtil 的两种入口，分别服务客户端准星和服务端投射物。
 * 无 Fabric 射线形状事件，故在原版入口补充；COMMON 主线程，无网络包或临时命中缓存。
 * 排查：空角命中查是否过滤了原版大盒；尾桨选不到查候选扩展；近物被穿透查比较平方距离。
 */
@Mixin(ProjectileUtil.class)
public abstract class HelicopterRaycastMixin {
    /** 原版准星入口描述符，避免命中 getCollision 的其他重载。 */
    private static final String PICK = "raycast(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;D)Lnet/minecraft/util/hit/EntityHitResult;";
    /** 投射物最终入口，六参数重载会委托此七参数方法。 */
    private static final String PROJECTILE = "getEntityCollision(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;F)Lnet/minecraft/util/hit/EntityHitResult;";

    /** net.minecraft.world.World#getOtherEntities 原查询只排除直升机，防止原版大盒产生假命中。 */
    @ModifyArg(method = {PICK, PROJECTILE}, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/World;getOtherEntities(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Box;Ljava/util/function/Predicate;)Ljava/util/List;"), index = 2)
    private static Predicate<Entity> excludeBroadBox(Predicate<Entity> original) {
        return entity -> !(entity instanceof HelicopterEntity) && original.test(entity);
    }

    /** 合并最近真实命中，沿用原版可选谓词和最大距离；COMMON 主线程，不改动其他实体。 */
    private static EntityHitResult closest(World world, Entity source, Vec3d start, Vec3d end, Box area,
            Predicate<Entity> predicate, double maximum, EntityHitResult original) {
        double distance = original == null ? maximum : Math.min(maximum, start.squaredDistanceTo(original.getPos()));
        EntityHitResult result = original;
        for (HelicopterEntity heli : HelicopterCollision.candidates(world, source, area)) {
            if (!predicate.test(heli)) continue;
            var hit = HelicopterCollision.raycast(heli, start, end);
            if (hit != null && hit.distanceSquared() <= distance) {
                distance = hit.distanceSquared(); result = new EntityHitResult(heli, hit.position());
            }
        }
        return result;
    }

    /** 客户端准星使用实际面和原版交互距离，第三人称也不把大盒空角当作机身。 */
    @Inject(method = PICK, at = @At("RETURN"), cancellable = true)
    private static void pick(Entity source, Vec3d start, Vec3d end, Box area, Predicate<Entity> predicate,
            double maximum, CallbackInfoReturnable<EntityHitResult> cir) {
        cir.setReturnValue(closest(source.getWorld(), source, start, end, area, predicate, maximum, cir.getReturnValue()));
    }

    /** 服务端箭矢等投射物命中真实尾桨面；忽略原版额外命中裕量，避免薄桨盘变成厚墙。 */
    @Inject(method = PROJECTILE, at = @At("RETURN"), cancellable = true)
    private static void projectile(World world, Entity source, Vec3d start, Vec3d end, Box area,
            Predicate<Entity> predicate, float margin, CallbackInfoReturnable<EntityHitResult> cir) {
        cir.setReturnValue(closest(world, source, start, end, area, predicate, Double.MAX_VALUE, cir.getReturnValue()));
    }
}
