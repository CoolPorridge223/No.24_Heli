/** 文件：登机视角生命周期及机内眼位；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.camera;

import org.DJB.no_24_heli.entity.HelicopterEntity;


import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 职责：登机保持透视，V 切机内/机外，下机恢复；机内眼位跟随机体倾斜。
 * 引用：net.minecraft.client.option.Perspective 切换原版透视；net.minecraft.util.math.Vec3d 保存座椅眼位。
 * 生命周期：客户端 tick 管理乘骑，Camera Mixin 每帧读取姿态。
 * 端侧/线程：客户端线程；不改玩家朝向，避免产生额外飞控输入。
 * 排查：离机不恢复查 previous；舱内漂移查 bodyRotation；V 无响应查注册与 GUI 焦点。
 */
@net.fabricmc.api.Environment(net.fabricmc.api.EnvType.CLIENT)
public final class HeliView {
    private static Perspective previous;
    private static HelicopterEntity mounted;
    private static boolean following;
    private static boolean initialized;
    private static float yaw, pitch;
    private static long frameTime;
    /** 机内相对观察在每次切入时初始化；单位为度，仅保存在本客户端。 */
    private static boolean cockpitInitialized;
    private static float lookYaw,lookPitch,oldLookYaw,oldLookPitch;
    /** 初始下看 8°，自由观察水平 ±105°、垂直 ±70°，避免视线穿入椅背。 */
    private static final float DEFAULT_LOOK_PITCH=8,MAX_LOOK_YAW=105,MAX_LOOK_PITCH=70;
    /** 每次滚动改变一格，4–24 格范围内仍交给原版碰撞裁剪。 */
    private static float distance=10;
    /** 座舱局部视场角，单位：度；滚轮每格五度，25°～110°，不改原版全局设置。 */
    private static double cockpitFov=70;
    public static float distance(){return distance;}
    public static void zoom(double scroll){distance=MathHelper.clamp(distance-(float)scroll,4,24);}
    /** 功能：缩放座舱视野；客户端鼠标回调调用，不改变瞄准、相机位置或飞行输入。 */
    public static void zoomCockpit(double scroll){cockpitFov=MathHelper.clamp(cockpitFov-scroll*5,25,110);}
    /** 功能：返回座舱独立视场角，供 GameRenderer 的投影计算使用；客户端调用。 */
    public static double cockpitFov(){return cockpitFov;}
    private HeliView() {}

    /** 登机保留原透视，下机或断线恢复原视角。 */
    public static void tick(MinecraftClient client) {
        if(!client.options.getPerspective().isFirstPerson())cockpitInitialized=false;
        else{following=false;initialized=false;}
        HelicopterEntity current = client.player != null && client.player.getVehicle() instanceof HelicopterEntity h ? h : null;
        if (current == mounted) return;
        if (mounted != null && previous != null) client.options.setPerspective(previous);
        mounted = current;
        following = false;
        initialized = false;
        cockpitInitialized = false;
        if (current != null) {
            cockpitFov=client.options.getFov().getValue();
            previous = client.options.getPerspective();
            // 保留登机前透视，使第一人称能完整看到迈入和坐下；V/F5 仍可自由切换。
        } else previous = null;
    }

    /** 中键只切换相机跟随，不改玩家朝向，以免生成虚假的操纵输入。 */
    public static void toggleFollow() {
        if(MinecraftClient.getInstance().options.getPerspective().isFirstPerson()){following=false;return;}
        following = !following; initialized = false;
    }
    public static boolean isFollowing() { return following; }
    /**
     * 功能：在机内第一人称与机外后视之间切换；引用 MC API：net.minecraft.client.option.Perspective 指定透视。
     * 端侧/线程：客户端输入；参数：client 为客户端；返回：无；副作用：切视角并重置滤波，无飞控或网络副作用。
     * 排查：仅乘机且无 GUI 时调用；F5 仍按原版切换。
     */
    public static void togglePerspective(MinecraftClient client){
        client.options.setPerspective(client.options.getPerspective().isFirstPerson()?Perspective.THIRD_PERSON_BACK:Perspective.FIRST_PERSON);
        if(client.options.getPerspective().isFirstPerson())following=false;
        cockpitInitialized=false;initialized=false;
    }
    /**
     * 功能：提供机模和相机共用姿态；引用 MC API：net.minecraft.util.math.MathHelper#lerp 插值同步俯仰/横滚。
     * 端侧/线程：渲染线程；参数：heli/delta 为机体与 tick 插值；返回：本地到世界旋转；副作用：无。
     * 排查：相机和机舱错动查是否使用相同插值；损伤抖动也须共用。
     */
    public static Quaternionf bodyRotation(HelicopterEntity heli,float delta){
        float roll=MathHelper.lerp(delta,heli.previousVisualRoll,heli.visualRoll);
        if(heli.flight.rotor<60&&heli.flight.rpm>.2)
            roll+=(float)Math.sin((heli.age+delta)*2)*(1-(float)heli.flight.rotor/100)*2;
        return new Quaternionf().rotationY((float)Math.toRadians(-heli.getYaw(delta)))
                .rotateX((float)Math.toRadians(-MathHelper.lerp(delta,heli.previousVisualPitch,heli.visualPitch)))
                .rotateZ((float)Math.toRadians(roll));
    }
    /**
     * 功能：将主副驾眼位转换到世界坐标；引用 MC API：net.minecraft.entity.Entity#getLerpedPos 插值机身位置。
     * 端侧/线程：渲染线程；参数：heli/focus/delta 为飞机、玩家与插值；返回：眼睛世界坐标；副作用：无。
     * 排查：副驾错位查 isPilot，不能仅凭乘员列表下标猜测绳降后的身份。
     */
    public static Vec3d cockpitPosition(HelicopterEntity heli,Entity focus,float delta){
        // +Z 为机头时，面向机头的左侧是本地 +X；须与服务端座位位置使用相同符号。
        Vec3d eye=org.DJB.no_24_heli.client.render.CabinPassengerRenderer.eye(heli,(net.minecraft.entity.LivingEntity)focus,delta);
        Vector3f offset=new Vector3f((float)eye.x,(float)eye.y,(float)eye.z);
        offset.rotate(bodyRotation(heli,delta));
        return heli.getLerpedPos(delta).add(offset.x,offset.y,offset.z);
    }
    /**
     * 功能：在机体旋转上叠加相对自由观察；引用 MC API：net.minecraft.entity.Entity#getYaw/getPitch 读取玩家观察变化。
     * 端侧/线程：渲染线程；参数：heli/focus/delta 为飞机、玩家与插值；返回：相机完整姿态；副作用：更新观察快照。
     * 排查：键盘转向时视线须跟机头；鼠标观察不能写回玩家朝向，否则会制造虚假输入。
     */
    public static Quaternionf cockpitRotation(HelicopterEntity heli,Entity focus,float delta){
        float currentYaw=focus.getYaw(delta),currentPitch=focus.getPitch(delta);
        if(!cockpitInitialized){
            oldLookYaw=currentYaw;oldLookPitch=currentPitch;lookYaw=0;lookPitch=DEFAULT_LOOK_PITCH;cockpitInitialized=true;
        }
        lookYaw=MathHelper.clamp(lookYaw+MathHelper.wrapDegrees(currentYaw-oldLookYaw),-MAX_LOOK_YAW,MAX_LOOK_YAW);
        lookPitch=MathHelper.clamp(lookPitch+currentPitch-oldLookPitch,-MAX_LOOK_PITCH,MAX_LOOK_PITCH);
        oldLookYaw=currentYaw;oldLookPitch=currentPitch;
        return bodyRotation(heli,delta).rotateY((float)Math.toRadians(
                org.DJB.no_24_heli.client.render.CabinPassengerRenderer.yaw(heli,(net.minecraft.entity.LivingEntity)focus,delta)))
                .mul(new Quaternionf().rotationYXZ(
                (float)Math.PI-(float)Math.toRadians(lookYaw),(float)Math.toRadians(-lookPitch-org.DJB.no_24_heli.client.render.CabinPassengerRenderer.nod(
                        heli,(net.minecraft.entity.LivingEntity)focus,delta)),0));
    }
    /** 功能：第三人称锁定跟随使用与机模相同的逐帧姿态，保留 350 ms 镜头阻尼；仅渲染线程调用。 */
    public static float[] rotation(HelicopterEntity heli, float cameraYaw, float cameraPitch, boolean inverse,float delta) {
        if (!following) return new float[]{cameraYaw, cameraPitch};
        long now = Util.getMeasuringTimeMs();
        if (!initialized) { yaw = cameraYaw; pitch = cameraPitch; frameTime = now; initialized = true; }
        float blend = (float)(1 - Math.exp(-MathHelper.clamp(now - frameTime, 0, 100) / 350.0));
        frameTime = now;
        float targetYaw = heli.getYaw(delta) + (inverse ? 180 : 0);
        float targetPitch = (float)MathHelper.clamp(12 - MathHelper.lerp(delta,heli.previousVisualPitch,heli.visualPitch) * .35, -35, 55) * (inverse ? -1 : 1);
        yaw += MathHelper.wrapDegrees(targetYaw - yaw) * blend;
        pitch += (targetPitch - pitch) * blend;
        return new float[]{yaw, pitch};
    }
}
