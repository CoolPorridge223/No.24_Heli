/** 文件：直升机乘员固定座位与机体姿态；端侧：CLIENT；目标：LivingEntityRenderer。 */
package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.client.render.CabinPassengerRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.BipedEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 职责：恢复主副驾人物，髋部固定在对应座垫，身体跟飞机而非鼠标转动。
 * 引用：net.minecraft.client.render.entity.LivingEntityRenderer 保留坐姿、皮肤和装备；
 * net.minecraft.client.util.math.MatrixStack 仅修正视觉座位及倾斜，不回写玩家视角或物理。
 * 生命周期：每次乘员渲染成对压栈；端侧/线程：仅客户端渲染线程。
 * 无 Fabric 事件可以修改原版身体变换，故使用 Mixin；可能与其他载具身体姿态 Mixin 冲突。
 * 排查：坐错座查 isPilot；身体扭动查 setupTransforms；离座错位查矩阵 push/pop。
 */
@Environment(EnvType.CLIENT)
@Mixin(LivingEntityRenderer.class)
public abstract class HeliCrewRendererMixin {
    /** 自定义握杆臂替代玩家原右臂；每次绘制结束恢复，避免污染离机人物及其他渲染。 */
    @org.spongepowered.asm.mixin.Unique private boolean heli$hiddenArm, heli$armVisible, heli$sleeveVisible;
    /** 完整描述符避免同时命中 net.minecraft.client.render.entity.LivingEntityRenderer 的桥接方法。 */
    private static final String RENDER = "render(Lnet/minecraft/entity/LivingEntity;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;I)V";
    /** 复用 net.minecraft.client.render.entity.LivingEntityRenderer#model，在原版坐姿完成后调整腿部位置。 */
    @Shadow protected EntityModel<?> model;
    /** 舱内人物按座椅尺寸缩至 88%，围绕脚位上方 0.70 格的髋部缩放，头顶和头盔不穿过圆弧舱顶。 */
    private static final float SEATED_SCALE = .88f, HIP_HEIGHT = .70f;

    /**
     * 功能：将人物脚位变换到倾斜后的座椅；引用 MC API：net.minecraft.entity.Entity#getLerpedPos 消除 tick 跳动。
     * 端侧：渲染线程；参数：原版人物及绘制参数；返回：无；副作用：乘机时压栈并平移。
     * 排查：服务器碰撞位置不含视觉俯仰/横滚，不能直接当作座椅位置。
     */
    @Inject(method = RENDER, at = @At("HEAD"))
    private void seatPosition(LivingEntity entity, float yaw, float delta, MatrixStack matrices,
            VertexConsumerProvider vertices, int light, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof HelicopterEntity heli)) return;
        Vec3d local = heli.cabin.position(entity, delta);
        Vector3f seat = new Vector3f((float)local.x, (float)local.y, (float)local.z)
                .rotate(HeliView.bodyRotation(heli, delta));
        Vec3d correction = heli.getLerpedPos(delta).add(seat.x, seat.y, seat.z).subtract(entity.getLerpedPos(delta));
        matrices.push(); matrices.translate(correction.x, correction.y, correction.z);
    }

    /**
     * 功能：还原座位矩阵；引用 MC API：net.minecraft.client.util.math.MatrixStack#pop 与入口配对。
     * 端侧：渲染线程；参数：原版绘制参数；返回：无；副作用：恢复矩阵；排查：只对乘机人物出栈。
     */
    @Inject(method = RENDER, at = @At("RETURN"))
    private void restorePosition(LivingEntity entity, float yaw, float delta, MatrixStack matrices,
            VertexConsumerProvider vertices, int light, CallbackInfo ci) {
        if (heli$hiddenArm && model instanceof net.minecraft.client.render.entity.model.PlayerEntityModel<?> playerModel) {
            playerModel.rightArm.visible = heli$armVisible;
            playerModel.rightSleeve.visible = heli$sleeveVisible;
            heli$hiddenArm = false;
        }
        if (entity.getVehicle() instanceof HelicopterEntity) matrices.pop();
    }

    /**
     * 功能：身体锁定飞机方向及倾斜；引用 MC API：net.minecraft.client.render.entity.LivingEntityRenderer#setupTransforms。
     * 端侧：渲染线程；参数：原版变换参数；返回：无；副作用：仅改矩阵，不修改玩家观察输入。
     * 排查：原版人物正面带 180° 补偿，不能直接叠加飞机四元数。
     */
    @Inject(method = "setupTransforms", at = @At("TAIL"))
    private void fixBody(LivingEntity entity, MatrixStack matrices, float animation, float bodyYaw,
            float delta, float scale, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof HelicopterEntity heli) || !entity.isAlive()) return;
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(bodyYaw - 180));
        matrices.multiply(HeliView.bodyRotation(heli, delta));
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180 + CabinPassengerRenderer.yaw(heli, entity, delta)));
        matrices.translate(0, HIP_HEIGHT, 0);
        matrices.scale(SEATED_SCALE, SEATED_SCALE, SEATED_SCALE);
        matrices.translate(0, -HIP_HEIGHT, 0);
    }

    /**
     * 功能：停用走路摆臂，头部保留有限观察；引用 MC API：net.minecraft.client.render.entity.model.EntityModel#setAngles。
     * 端侧：渲染线程；参数：args 为骨骼参数；返回：无；副作用：只改本帧姿态。
     * 排查：头转过度查相对机头 ±60° 钳制；身体应完全跟随 fixBody。
     */
    @ModifyArgs(method = RENDER, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Lnet/minecraft/entity/Entity;FFFFF)V"))
    private void seatedPose(Args args) {
        LivingEntity entity = args.get(0);
        if (!(entity.getVehicle() instanceof HelicopterEntity heli)) return;
        args.set(1, 0f); args.set(2, 0f);
        args.set(4, MathHelper.clamp(MathHelper.wrapDegrees(entity.getHeadYaw() - heli.getYaw()), -60f, 60f));
    }

    /**
     * 功能：将双腿平行前伸至脚踏位置，避免原版外张的乘骑脚跟穿过机腹。
     * 引用 MC API：net.minecraft.client.render.entity.model.BipedEntityModel 的腿部骨骼在 setAngles 之后修正，装备层继续复用。
     * 端侧：渲染线程；参数：原版 render 参数；返回：无；副作用：只改乘机人形模型的本帧腿角。
     * 排查：腿部外露查俯仰是否为 -π/2、偏航和横滚是否归零，以及座垫对应脚位高度。
     */
    @Inject(method = RENDER, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Lnet/minecraft/entity/Entity;FFFFF)V",
            shift = At.Shift.AFTER))
    private void alignLegs(LivingEntity entity, float yaw, float delta, MatrixStack matrices,
            VertexConsumerProvider vertices, int light, CallbackInfo ci) {
        if (!(entity.getVehicle() instanceof HelicopterEntity heli) || !(model instanceof BipedEntityModel<?> biped)) return;
        CabinPassengerRenderer.pose(biped, heli, entity, delta);
        if (CabinPassengerRenderer.holdsStick(heli,entity) && model instanceof net.minecraft.client.render.entity.model.PlayerEntityModel<?> playerModel) {
            heli$armVisible = playerModel.rightArm.visible;
            heli$sleeveVisible = playerModel.rightSleeve.visible;
            playerModel.rightArm.visible = playerModel.rightSleeve.visible = false;
            heli$hiddenArm = true;
        }
    }

    /**
     * 功能：人物接受机内舱灯，保留环境天空光；引用 MC API：net.minecraft.client.render.LightmapTextureManager#pack 打包块光。
     * 端侧：渲染线程；参数：light 为原光照，后续参数为目标 render 的完整参数；返回：人物局部光照。
     * 副作用：无；排查：只修改乘机人物，残骸灯熄灭。
     */
    @ModifyVariable(method = RENDER, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int cabinLight(int light, LivingEntity entity, float yaw, float delta, MatrixStack matrices,
            VertexConsumerProvider vertices, int originalLight) {
        return entity.getVehicle() instanceof HelicopterEntity heli && !heli.flight.wreck
                ? (light & 0xffff0000) | Math.max(light & 0xffff, LightmapTextureManager.pack(11, 0)) : light;
    }
}
