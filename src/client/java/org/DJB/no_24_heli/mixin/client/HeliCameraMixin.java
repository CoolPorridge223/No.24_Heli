package org.DJB.no_24_heli.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.client.HeliView;
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
        if (!thirdPerson || !(focus.getVehicle() instanceof HelicopterEntity heli)) return;
        float[] rotation = HeliView.rotation(heli, getYaw(), getPitch(), inverse);
        setRotation(rotation[0], rotation[1]);
        setPos(focus.getCameraPosVec(delta));
        moveBy(-clipToSpace(HeliView.distance()), 0, 0);
    }
}
