/** 文件：拖拽参与者与趴地眩晕的玩家同步状态；端侧：COMMON；由原版玩家数据追踪器同步。 */
package org.DJB.no_24_heli.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * 职责：给 net.minecraft.entity.player.PlayerEntity 附加短时动作状态，不保存到玩家 NBT。
 * 生命周期：CabinPlayerStateMixin 实现，舱位服务端写入，客户端相机与人物渲染只读。
 * 端侧/线程：COMMON 世界主线程；原版 DataTracker 自动覆盖附近观察者及后来开始追踪的玩家。
 * 排查：重复动作查 cabinLocked；眩晕不结束查世界时间；退出重进由新玩家实体自然清空。
 */
public interface CabinPlayerState {
    /** 趴地持续 60 个游戏 tick，即正常服务器速度下三秒；仅在真实离座后计时。 */
    int STUN_TICKS = 60;

    /** 正在拉人的飞机网络 ID；-1 表示没有拖拽请求。 */
    int cabinPullHelicopter();
    /** 正在拉人的固定座号；只能由服务端舱位设置。 */
    int cabinPullSeat();
    /** 眩晕结束的世界 tick；零表示没有眩晕。 */
    long cabinStunUntil();
    /** 倒地时冻结身体朝向，避免鼠标观察带着趴地人物转圈。 */
    float cabinStunYaw();
    /** 同步拉人身份；结束、取消或失去飞机时写入 -1/-1。 */
    void cabinPull(int helicopter, int seat);
    /** 同步眩晕时间和朝向；传入零结束眩晕，恢复过程不改变已有药水效果。 */
    void cabinStun(long until, float yaw);

    /** 两端按 net.minecraft.world.World#getTime 判断眩晕，客户端不自行延长服务器期限。 */
    default boolean cabinStunned() {
        return cabinStunUntil() > ((PlayerEntity) this).getWorld().getTime();
    }

    /** 按当前世界实体和舱位 UUID 复核拖拽身份；飞机移除或动作取消后立即失效。 */
    default HelicopterEntity cabinPullTarget() {
        PlayerEntity player = (PlayerEntity) this;
        return player.getWorld().getEntityById(cabinPullHelicopter()) instanceof HelicopterEntity heli
                && heli.cabin.isPulling(player, cabinPullSeat()) ? heli : null;
    }

    /** 只锁定本次拖拽双方和趴地玩家；普通玩家、正常乘降与其他载具保持原行为。 */
    static boolean locked(Entity entity) {
        if (!(entity instanceof PlayerEntity player)) return false;
        CabinPlayerState state = (CabinPlayerState) player;
        return state.cabinStunned() || state.cabinPullTarget() != null
                || player.getVehicle() instanceof HelicopterEntity heli && heli.cabin.motion(player) == HelicopterCabin.PULL;
    }

    /** 共用 Fabric 交互回调限制短时动作中的攻击和使用；客户端与服务端都复核，不修改原有物品。 */
    static void initialize() {
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, world, hand) -> locked(player)
                ? net.minecraft.util.TypedActionResult.fail(player.getStackInHand(hand))
                : net.minecraft.util.TypedActionResult.pass(player.getStackInHand(hand)));
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> locked(player)
                ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> locked(player)
                ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> locked(player)
                ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> locked(player)
                ? net.minecraft.util.ActionResult.FAIL : net.minecraft.util.ActionResult.PASS);
    }
}
