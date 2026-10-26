/** 文件：拖拽与趴地期间的正面第三人称镜头；端侧：CLIENT；状态来自玩家与舱位追踪器。 */
package org.DJB.no_24_heli.client.camera;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.client.render.CabinPassengerRenderer;
import org.DJB.no_24_heli.entity.CabinPlayerState;
import org.DJB.no_24_heli.entity.HelicopterCabin;
import org.DJB.no_24_heli.entity.HelicopterEntity;

/**
 * 职责：只在拖拽双方动作及被拖者三秒趴地期间覆盖透视，结束恢复动作前的视角。
 * 引用 net.minecraft.client.option.Perspective.THIRD_PERSON_FRONT 使原版绘制本地人物；Camera Mixin 定位正面。
 * 生命周期：HeliView.tick 后调用；断线/换维度清空，渲染线程只读本次场景。
 * 排查：镜头提前退出查 boarding；离机恢复错误查 HeliView.finishCabinScene；不写玩家鼠标角度。
 */
@Environment(EnvType.CLIENT)
public final class CabinActionView {
    /** 进入动作前的透视与飞机；新乘员坐稳前继续展示接续登机，避免中途跳回第一人称。 */
    private static Perspective previous;
    private static HelicopterEntity scene;
    /** 动作正面距离，保留 net.minecraft.client.render.Camera#clipToSpace 的原版墙壁裁剪。 */
    private static final float DISTANCE = 3.5f, IMPACT_DISTANCE = 2.15f;

    /** 静态相机工具，不创建或移动权威实体。 */
    private CabinActionView() {}

    /** 推进本地透视生命周期，并为所有可见趴地玩家补绘环绕头部的眩晕星点。 */
    public static void tick(MinecraftClient client) {
        PlayerEntity player = client.player;
        boolean active = player != null && client.world != null && CabinPlayerState.locked(player);
        if (active && previous == null) {
            previous = client.options.getPerspective();
            scene = player.getVehicle() instanceof HelicopterEntity heli ? heli : ((CabinPlayerState) player).cabinPullTarget();
        }
        boolean boarding = previous != null && player != null && scene != null && player.getVehicle() == scene
                && scene.cabin.motion(player) == HelicopterCabin.BOARD;
        if (active || boarding) client.options.setPerspective(Perspective.THIRD_PERSON_FRONT);
        else if (previous != null) {
            HeliView.finishCabinScene(client, previous); previous = null; scene = null;
        }
        if (client.world == null) return;
        for (PlayerEntity other : client.world.getPlayers()) {
            CabinPlayerState state = (CabinPlayerState) other;
            // 世界时钟在玩家 tick 后推进；到期渲染帧同步恢复姿态，避免镜头已退出却仍保留一帧游泳姿态。
            if (state.cabinStunUntil() > 0 && !state.cabinStunned()
                    && other.getPose() == net.minecraft.entity.EntityPose.SWIMMING)
                other.setPose(net.minecraft.entity.EntityPose.STANDING);
            if (!state.cabinStunned() || client.world.getTime() % 4 != 0) continue;
            double angle = client.world.getTime() * .35;
            Vec3d head = other.getPos().add(new Vec3d(0, .45, 1.35).rotateY((float) -Math.toRadians(state.cabinStunYaw())));
            client.world.addParticle(ParticleTypes.CRIT, head.x + Math.cos(angle) * .28, head.y,
                    head.z + Math.sin(angle) * .28, 0, .015, 0);
        }
    }

    /** 渲染帧只对真正处于本地动作中的玩家覆写相机；观察其他玩家时不夺走镜头。 */
    public static boolean active(Entity focus) {
        return previous != null && focus == MinecraftClient.getInstance().player;
    }

    /** 按实际身体正面确定相机朝向，原版鼠标转头不影响拍摄方向。 */
    public static float facing(PlayerEntity player, float delta) {
        CabinPlayerState state = (CabinPlayerState) player;
        if (state.cabinStunned()) return state.cabinStunYaw();
        if (player.getVehicle() instanceof HelicopterEntity heli)
            return heli.getYaw(delta) - CabinPassengerRenderer.yaw(heli, player, delta);
        HelicopterEntity heli = state.cabinPullTarget();
        Entity victim = heli == null ? null : heli.cabin.pulledOccupant(player);
        if (victim == null) return player.getYaw(delta);
        Vec3d direction = heli.cabin.toWorld(heli.cabin.position(victim, delta))
                .subtract(heli.cabin.toWorld(heli.cabin.pullerPosition(player, delta)));
        return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
    }

    /** 双方共用被拖者的侧前机位，偏向操作者左肘一侧六十五度，避免背部挡住接触点。 */
    public static float cameraYaw(PlayerEntity player, float delta) {
        PlayerEntity victim = subject(player);
        if (victim.getVehicle() instanceof HelicopterEntity heli && heli.cabin.motion(victim) == HelicopterCabin.PULL)
            return heli.getYaw(delta) + (heli.cabin.accessSide(victim) == 0 ? -90 : 90) + 180 - 65;
        return facing(subject(player), delta) + 180 - 65;
    }

    /** 肘击前平滑推近，在倒地阶段拉回；保留 Camera#clipToSpace 的墙壁裁剪，不改全局 FOV。 */
    public static float distance(PlayerEntity player, float delta) {
        float p = pullProgress(player, delta);
        float zoom = HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_LIFT, HelicopterCabin.PULL_WINDUP)
                * (1 - HelicopterCabin.pullPhase(p, HelicopterCabin.PULL_HIT + .04f, HelicopterCabin.PULL_LANDED));
        return MathHelper.lerp(zoom, DISTANCE, IMPACT_DISTANCE);
    }

    /** 本地双方读取被拖者同一世界时钟；非拖拽阶段返回一，确保登机与眩晕不继承缩放。 */
    private static float pullProgress(PlayerEntity player, float delta) {
        PlayerEntity victim = subject(player);
        return victim.getVehicle() instanceof HelicopterEntity heli && heli.cabin.motion(victim) == HelicopterCabin.PULL
                ? heli.cabin.progress(victim, delta) : 1;
    }

    /** 拉人者镜头取被拖玩家为主体；入座及趴地阶段再跟随自身。 */
    private static PlayerEntity subject(PlayerEntity player) {
        HelicopterEntity heli = ((CabinPlayerState) player).cabinPullTarget();
        return heli != null && heli.cabin.pulledOccupant(player) instanceof PlayerEntity victim ? victim : player;
    }

    /** 机内沿共享动画脚位取躯干，趴地时对准头部高度，机外拉人者使用普通胸部高度。 */
    public static Vec3d center(PlayerEntity player, float delta) {
        player = subject(player);
        CabinPlayerState state = (CabinPlayerState) player;
        if (state.cabinStunned()) return player.getLerpedPos(delta).add(0, .4, 0);
        if (player.getVehicle() instanceof HelicopterEntity heli) {
            Vec3d center = HeliView.cockpitPosition(heli, player, delta).add(0, -.4, 0);
            if (heli.cabin.motion(player) == HelicopterCabin.PULL) {
                float p = heli.cabin.progress(player, delta);
                // 击中后约两 tick 的微小回弹增强接触感，不叠加随机抖动或改变玩家鼠标角度。
                float impact = MathHelper.clamp((p - HelicopterCabin.PULL_HIT) / .05f, 0, 1);
                center = center.add(0, .035 * Math.sin(impact * Math.PI * 2) * (1 - impact), 0);
            }
            return center;
        }
        return player.getLerpedPos(delta).add(0, 1.15, 0);
    }
}
