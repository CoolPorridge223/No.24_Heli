package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import org.DJB.no_24_heli.HelicopterEntity;

/** 登机视角生命周期与独立于飞控输入的平滑跟随。 */
public final class HeliView {
    private static Perspective previous;
    private static HelicopterEntity mounted;
    private static boolean following;
    private static boolean initialized;
    private static float yaw, pitch;
    private static long frameTime;
    private HeliView() {}

    /** 登机切换后视第三人称，下机或断线恢复原视角。 */
    public static void tick(MinecraftClient client) {
        HelicopterEntity current = client.player != null && client.player.getVehicle() instanceof HelicopterEntity h ? h : null;
        if (current == mounted) return;
        if (mounted != null && previous != null) client.options.setPerspective(previous);
        mounted = current;
        following = false;
        initialized = false;
        if (current != null) {
            previous = client.options.getPerspective();
            client.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        } else previous = null;
    }

    /** 中键只切换相机跟随，不改玩家朝向，以免生成虚假的操纵输入。 */
    public static void toggleFollow() { following = !following; initialized = false; }
    public static boolean isFollowing() { return following; }
    public static float[] rotation(HelicopterEntity heli, float cameraYaw, float cameraPitch, boolean inverse) {
        if (!following) return new float[]{cameraYaw, cameraPitch};
        long now = Util.getMeasuringTimeMs();
        if (!initialized) { yaw = cameraYaw; pitch = cameraPitch; frameTime = now; initialized = true; }
        float blend = (float)(1 - Math.exp(-MathHelper.clamp(now - frameTime, 0, 100) / 350.0));
        frameTime = now;
        float targetYaw = heli.getYaw() + (inverse ? 180 : 0);
        float targetPitch = (float)MathHelper.clamp(12 - heli.flight.pitch * .35, -35, 55) * (inverse ? -1 : 1);
        yaw += MathHelper.wrapDegrees(targetYaw - yaw) * blend;
        pitch += (targetPitch - pitch) * blend;
        return new float[]{yaw, pitch};
    }
}
