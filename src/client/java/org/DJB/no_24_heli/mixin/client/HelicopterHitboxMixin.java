/** 文件：F3+B 显示直升机真实旋转分部盒；端侧：CLIENT；目标：EntityRenderDispatcher。 */
package org.DJB.no_24_heli.mixin.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterCollision;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：用分部盒替换原版单个轴对齐调试盒，不把候选范围误显示为真正物理形状。
 * 引用 net.minecraft.client.render.WorldRenderer#drawBox 只提交线框；原版无替换调试盒事件，因此需 Mixin。
 * 生命周期：玩家开启 F3+B 后渲染；仅客户端渲染线程，不写物理状态。
 * 排查：旋转错位查与机模共用的插值角；颜色区分机舱、动力、油箱、尾梁、双桨和滑橇。
 */
@Environment(EnvType.CLIENT)
@Mixin(EntityRenderDispatcher.class)
public abstract class HelicopterHitboxMixin {
    /** net.minecraft.client.render.entity.EntityRenderDispatcher 已平移到实体原点，分部中心仍使用相对机体坐标。 */
    @Inject(method = "renderHitbox", at = @At("HEAD"), cancellable = true)
    private static void parts(MatrixStack matrices, VertexConsumer vertices, Entity entity, float delta,
            float red, float green, float blue, CallbackInfo ci) {
        if (!(entity instanceof HelicopterEntity heli)) return;
        Vec3d origin = heli.getLerpedPos(delta);
        var pose = heli.getWorld().isClient ? new HelicopterCollision.Pose(heli.getYaw(delta),
                MathHelper.lerp(delta, heli.previousVisualPitch, heli.visualPitch),
                MathHelper.lerp(delta, heli.previousVisualRoll, heli.visualRoll)) : HelicopterCollision.pose(heli);
        for (var shape : HelicopterCollision.shapes(heli, pose, origin)) {
            int color = switch (shape.part()) {
                case CABIN -> 0xffffff; case ENGINE -> 0xffa030; case FUEL_TANK -> 0xffff30;
                case TAIL_BOOM -> 0x30ddff; case TAIL_ROTOR -> 0xff30ff;
                case MAIN_ROTOR -> 0xff4040; case LANDING_GEAR -> 0x40ff70;
            };
            Vec3d center = shape.center().subtract(origin);
            matrices.push(); matrices.translate(center.x, center.y, center.z); matrices.multiply(shape.rotation());
            WorldRenderer.drawBox(matrices, vertices, new Box(shape.half().negate(), shape.half()),
                    (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f, 1);
            matrices.pop();
        }
        ci.cancel();
    }
}
