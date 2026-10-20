/** 文件：原版相机的机内眼位及机外避障补充；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.client.render.ParachutePose;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.ParachuteEntity;


import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：机外保留避障，机内使用真实座舱眼位与机体旋转。
 * 引用：net.minecraft.client.render.Camera 维护位置、旋转和投影基；无 Fabric 事件可替换其眼位，因此需 Mixin。
 * 生命周期：Camera.update 尾部每帧执行；端侧/线程：仅客户端渲染线程。
 * 排查：视野倾斜不一致查三条投影基是否同步；冲突查其他修改 Camera.update 的模组。
 */
@Environment(EnvType.CLIENT)
@Mixin(Camera.class)
public abstract class HeliCameraMixin {
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void moveBy(float x, float y, float z);
    /**
     * 功能：获取原版相机四元数供机内横滚覆盖；引用 MC API：net.minecraft.client.render.Camera#getRotation。
     * 端侧：渲染线程；参数：无；返回：可修改旋转；副作用：由调用者更新；排查：修改后需同步三条投影基。
     */
    @Shadow public abstract Quaternionf getRotation();
    /**
     * 功能：获取相机前向投影基；引用 MC API：net.minecraft.client.render.Camera#getHorizontalPlane。
     * 端侧：渲染线程；参数：无；返回：前向向量；副作用：由调用者更新；排查：原版局部基为 (0,0,-1)。
     */
    @Shadow public abstract Vector3f getHorizontalPlane();
    /**
     * 功能：获取相机上向投影基；引用 MC API：net.minecraft.client.render.Camera#getVerticalPlane。
     * 端侧：渲染线程；参数：无；返回：上向向量；副作用：由调用者更新；排查：原版局部基为 (0,1,0)。
     */
    @Shadow public abstract Vector3f getVerticalPlane();
    /**
     * 功能：获取相机侧向投影基；引用 MC API：net.minecraft.client.render.Camera#getDiagonalPlane。
     * 端侧：渲染线程；参数：无；返回：侧向向量；副作用：由调用者更新；排查：原版局部基为 (-1,0,0)。
     */
    @Shadow public abstract Vector3f getDiagonalPlane();
    @Shadow private float clipToSpace(float distance) { throw new AssertionError(); }
    /**
     * 功能：调整载具镜头；引用 MC API：net.minecraft.client.render.Camera#setPos/setRotation 设置眼位与姿态。
     * 端侧/线程：渲染线程；参数：area/focus/thirdPerson/inverse/delta/ci 为原版相机上下文；返回：无。
     * 副作用：修改相机，绝不修改实体；排查：第一人称按机体位置插值，不使用原版站立眼高。
     */
    @Inject(method = "update", at = @At("TAIL"))
    private void helicopterCamera(BlockView area, Entity focus, boolean thirdPerson, boolean inverse, float delta, CallbackInfo ci) {
        // 新滑翔伞的第三人称包含头顶翼面，保留原版遮挡裁剪；不改变直升机视角设置。
        if (focus.getVehicle() instanceof ParachuteEntity canopy) {
            if (thirdPerson) { setPos(focus.getCameraPosVec(delta).add(0, 1, 0)); moveBy(-clipToSpace(8), 0, 0); }
            else setPos(ParachutePose.eyePosition(canopy, focus, delta));
            return;
        }
        if (!(focus.getVehicle() instanceof HelicopterEntity heli)) return;
        if(!thirdPerson){
            setPos(HeliView.cockpitPosition(heli,focus,delta));
            Quaternionf rotation=HeliView.cockpitRotation(heli,focus,delta);
            Vector3f forward=new Vector3f(0,0,-1).rotate(rotation);
            setRotation((float)Math.toDegrees(Math.atan2(-forward.x,forward.z)),
                    (float)Math.toDegrees(-Math.asin(Math.clamp(forward.y,-1,1))));
            getRotation().set(rotation);
            // HACK(1.21.1): setRotation 只支持偏航/俯仰；补齐横滚后必须同时更新投影基。
            // 当原版提供包含横滚并刷新投影的相机接口时可删除这三行。
            getHorizontalPlane().set(0,0,-1).rotate(rotation);
            getVerticalPlane().set(0,1,0).rotate(rotation);
            getDiagonalPlane().set(-1,0,0).rotate(rotation);
            return;
        }
        float[] rotation = HeliView.rotation(heli, getYaw(), getPitch(), inverse,delta);
        setRotation(rotation[0], rotation[1]);
        setPos(focus.getCameraPosVec(delta));
        moveBy(-clipToSpace(HeliView.distance()), 0, 0);
    }
}
