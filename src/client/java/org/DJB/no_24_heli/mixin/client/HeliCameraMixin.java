package org.DJB.no_24_heli.mixin.client;

import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.entity.HelicopterEntity;


import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 使用原版遮挡检测拉远相机，防止第三人称穿墙。 */
@Mixin(Camera.class)
public abstract class HeliCameraMixin {
    @Shadow public abstract float getYaw();
    @Shadow public abstract float getPitch();
    @Shadow protected abstract void setPos(Vec3d pos);
    @Shadow protected abstract void setRotation(float yaw, float pitch);
    @Shadow protected abstract void moveBy(float x, float y, float z);
    @Shadow private float clipToSpace(float distance) { throw new AssertionError(); }
    @Inject(method = "update", at = @At("TAIL"))
    private void helicopterCamera(BlockView area, Entity focus, boolean thirdPerson, boolean inverse, float delta, CallbackInfo ci) {
        // 新滑翔伞的第三人称包含头顶翼面，保留原版遮挡裁剪；不改变直升机视角设置。
        if(thirdPerson&&focus.getVehicle() instanceof org.DJB.no_24_heli.entity.ParachuteEntity){
            setPos(focus.getCameraPosVec(delta).add(0,1,0));moveBy(-clipToSpace(8),0,0);return;
        }
        if (!thirdPerson || !(focus.getVehicle() instanceof HelicopterEntity heli)) return;
        float[] rotation = HeliView.rotation(heli, getYaw(), getPitch(), inverse);
        setRotation(rotation[0], rotation[1]);
        setPos(focus.getCameraPosVec(delta));
        moveBy(-clipToSpace(HeliView.distance()), 0, 0);
    }
}
