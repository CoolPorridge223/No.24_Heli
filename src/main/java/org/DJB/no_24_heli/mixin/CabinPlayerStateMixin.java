/** 文件：玩家拖拽状态追踪与三秒行动限制；端侧：COMMON；目标：PlayerEntity。 */
package org.DJB.no_24_heli.mixin;

import net.minecraft.entity.EntityPose;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.DJB.no_24_heli.entity.CabinPlayerState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 职责：原版 DataTracker 同步拖拽与眩晕；只在动作期间冻结移动，结束恢复原版姿态。
 * 引用 net.minecraft.entity.player.PlayerEntity#tickMovement 无可取消 Fabric 事件，故在此窄入口拦截。
 * 生命周期：玩家实体创建时注册四个追踪字段，每 tick 复核存活、维度、期限及动作归属。
 * 端侧/线程：COMMON 世界主线程；服务端固定脚位，客户端同时停下输入预测，不能靠按键提前解除。
 * 排查：趴地残留查到期清理；被外部移动查 anchor 回位；死亡/换维度不会继承旧动作。
 */
@Mixin(PlayerEntity.class)
public abstract class CabinPlayerStateMixin implements CabinPlayerState {
    /** 使用 net.minecraft.entity.data.DataTracker 自带追踪协议，免除手写广播与迟到观察者补包。 */
    @Unique private static final TrackedData<Integer> CABIN_PULL_HELI = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.INTEGER);
    @Unique private static final TrackedData<Integer> CABIN_PULL_SEAT = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.INTEGER);
    @Unique private static final TrackedData<Long> CABIN_STUN_UNTIL = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.LONG);
    @Unique private static final TrackedData<Float> CABIN_STUN_YAW = DataTracker.registerData(PlayerEntity.class, TrackedDataHandlerRegistry.FLOAT);
    /** 当前动作的服务端脚位和维度；不持久化，避免重连后继续锁住玩家。 */
    @Unique private Vec3d cabin$anchor;
    @Unique private RegistryKey<World> cabin$world;
    /** 本端上一 tick 是否绘制趴地；客户端到期时立即恢复，免等下一份站姿追踪包。 */
    @Unique private boolean cabin$prone;

    /** 在 net.minecraft.entity.player.PlayerEntity#initDataTracker 尾部追加默认空状态。 */
    @Inject(method = "initDataTracker", at = @At("TAIL"))
    private void cabin$initialize(DataTracker.Builder builder, CallbackInfo ci) {
        builder.add(CABIN_PULL_HELI, -1); builder.add(CABIN_PULL_SEAT, -1);
        builder.add(CABIN_STUN_UNTIL, 0L); builder.add(CABIN_STUN_YAW, 0f);
    }

    /** 返回原版玩家追踪器，客户端永不在此写入权威值。 */
    @Unique private DataTracker cabin$tracker() { return ((PlayerEntity) (Object) this).getDataTracker(); }
    /** 拖拽飞机 ID，只读同步字段。 */
    @Override public int cabinPullHelicopter() { return cabin$tracker().get(CABIN_PULL_HELI); }
    /** 拖拽座号，只读同步字段。 */
    @Override public int cabinPullSeat() { return cabin$tracker().get(CABIN_PULL_SEAT); }
    /** 趴地结束 tick，只读同步字段。 */
    @Override public long cabinStunUntil() { return cabin$tracker().get(CABIN_STUN_UNTIL); }
    /** 趴地身体偏航，单位为度。 */
    @Override public float cabinStunYaw() { return cabin$tracker().get(CABIN_STUN_YAW); }
    /** 服务端开始拉人时立即固定真实脚位；结束清空锚点，避免沿用上一个阶段的位置。 */
    @Override public void cabinPull(int helicopter, int seat) {
        cabin$tracker().set(CABIN_PULL_HELI, helicopter); cabin$tracker().set(CABIN_PULL_SEAT, seat);
        PlayerEntity player = (PlayerEntity) (Object) this;
        cabin$anchor = helicopter >= 0 ? player.getPos() : null;
        cabin$world = helicopter >= 0 ? player.getWorld().getRegistryKey() : null;
    }
    /** 服务端开始或结束趴地；SWIMMING 仅提供矮碰撞箱，趴地骨骼由客户端专用渲染完成。 */
    @Override public void cabinStun(long until, float yaw) {
        cabin$tracker().set(CABIN_STUN_UNTIL, until); cabin$tracker().set(CABIN_STUN_YAW, yaw);
        PlayerEntity player = (PlayerEntity) (Object) this;
        cabin$anchor = until > 0 ? player.getPos() : null;
        cabin$world = until > 0 ? player.getWorld().getRegistryKey() : null;
        player.setPose(until > 0 ? EntityPose.SWIMMING : EntityPose.STANDING);
    }

    /** 阻止拖拽/眩晕期间的走动、跳跃和游泳预测；保留玩家实体 tick、伤害及服务器网络处理。 */
    @Inject(method = "tickMovement", at = @At("HEAD"), cancellable = true)
    private void cabin$freezeMovement(CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (CabinPlayerState.locked(player)) { player.setVelocity(Vec3d.ZERO); ci.cancel(); }
    }

    /** 服务端复核期限与动作归属，回收失效状态；眩晕只保持三秒，不添加或清除原有药水。 */
    @Inject(method = "tick", at = @At("TAIL"))
    private void cabin$finishTick(CallbackInfo ci) {
        PlayerEntity player = (PlayerEntity) (Object) this;
        if (!player.getWorld().isClient) {
            if (!player.isAlive() || player.isSpectator() || cabin$world != null && cabin$world != player.getWorld().getRegistryKey()) {
                if (cabinStunUntil() != 0) cabinStun(0, 0);
                if (cabinPullHelicopter() >= 0) cabinPull(-1, -1);
            }
            if (cabinStunUntil() != 0 && !cabinStunned()) cabinStun(0, 0);
            if (cabinPullHelicopter() >= 0 && cabinPullTarget() == null) cabinPull(-1, -1);
        }
        if (cabinStunned()) { player.setPose(EntityPose.SWIMMING); cabin$prone = true; }
        else if (cabin$prone) { player.setPose(EntityPose.STANDING); cabin$prone = false; }
        if (CabinPlayerState.locked(player) && !player.hasVehicle()) {
            player.setVelocity(Vec3d.ZERO); player.fallDistance = 0;
            if (cabin$anchor == null) { cabin$anchor = player.getPos(); cabin$world = player.getWorld().getRegistryKey(); }
            if (player instanceof ServerPlayerEntity serverPlayer && player.squaredDistanceTo(cabin$anchor) > 1.0e-6)
                serverPlayer.networkHandler.requestTeleport(cabin$anchor.x, cabin$anchor.y, cabin$anchor.z, player.getYaw(), player.getPitch());
        } else { cabin$anchor = null; cabin$world = null; }
    }
}
