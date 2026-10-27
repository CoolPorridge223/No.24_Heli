/** 文件：旋翼低耐久燃烧与引擎/油箱延时爆炸；端侧：COMMON，逻辑服务端权威；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.entity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.flight.FlightState;
import org.joml.Vector3f;

/**
 * 职责：汇总四处火源、独立管理两个爆炸计时器，生成原版小火焰/烟雾并结算旋翼附加损耗。
 * 引用 net.minecraft.server.world.ServerWorld#spawnParticles 向附近客户端发送原版粒子，
 * net.minecraft.world.World#isRaining 读取全局雨天天气，net.minecraft.nbt.NbtCompound 保存连续低血量时间。
 * 生命周期：每架 HelicopterEntity 持有一个实例，服务端碰撞结算后每 tick 调用；修复和销毁立即复核。
 * 端侧/线程：仅逻辑服务端主线程写入；客户端不抽签、不扣血。
 * 排查：火焰错位查模型局部锚点/姿态；雨天掉血区分燃烧与既有飞行磨损；重复爆炸查 wreck 和计时清除。
 */
public final class HelicopterDamageEffects {
    /** 原版正常游戏每秒二十 tick；爆炸每整秒抽签一次，不能每 tick 重复使用同一秒的概率。 */
    private static final int TICKS_PER_SECOND = 20;
    /** 旋翼耐久严格低于三十点时燃烧；零耐久桨叶已消失，不再生成粒子或扣负血量。 */
    private static final double BURN_THRESHOLD = 30;
    /** 引擎和油箱严格低于二十点时进入危险状态，包括零耐久；与开机、剩余燃油无关。 */
    private static final double EXPLOSION_THRESHOLD = 20;
    /** 燃烧额外消耗每秒零点五耐久，不经 damagePart，避免附带损坏未被要求的机身。 */
    private static final double BURN_DAMAGE_PER_TICK = .5 / TICKS_PER_SECOND;
    /** 每四 tick 发送一组粒子，服务端不放置火方块，不改变周围地形。 */
    private static final int PARTICLE_INTERVAL = 4;
    /** 模型局部桨毂坐标，单位格；与 HelicopterCollision 的主桨、尾桨位置一致。 */
    private static final Vec3d MAIN_HUB = new Vec3d(0, 2.71, -.17);
    private static final Vec3d TAIL_HUB = new Vec3d(.23, 1.84, -4.94);
    /** 统一火灾状态的部位位；扩展新火源时只需加入部位并由 FlightState.hasFire 汇总告警。 */
    private static final int MAIN_FIRE = 1, TAIL_FIRE = 2, ENGINE_FIRE = 4, TANK_FIRE = 8;
    /** 引擎外壳和油箱外侧的多点小火苗锚点，单位格；不启用原版整实体巨型着火贴图。 */
    private static final Vec3d[] ENGINE_POINTS = {new Vec3d(-.43, 2.3, -.9), new Vec3d(.43, 2.3, -.8),
            new Vec3d(-.22, 2.54, -.45), new Vec3d(.2, 2.54, -.1)};
    private static final Vec3d[] TANK_POINTS = {new Vec3d(-.86, 1.12, -1.10), new Vec3d(.86, 1.12, -1.10),
            new Vec3d(0, 1.94, -1.17)};
    /** 连续低于阈值的已加载游戏 tick，独立保存；修复或残骸清零，区块卸载期间暂停。 */
    private long engineTicks, tankTicks;

    /**
     * 功能：服务端每 tick 结算燃烧和两个独立的百分比爆炸抽签，返回是否需要整机爆炸。
     * 引用 net.minecraft.server.world.ServerWorld 发送粒子和读取天气，Entity#getRandom 提供本机随机源。
     * 端侧/线程：仅服务端世界主线程；由实体碰撞结算后调用一次。
     * 副作用：只扣两副旋翼耐久；任一抽签命中即清除两个计时，不再抽另一方。
     * 排查：爆炸过快查调用频率是否为二十 tick 一次；修复后秒数不归零查 resetRecovered 调用。
     */
    public boolean tick(HelicopterEntity heli) {
        FlightState state = heli.flight;
        refresh(heli);
        if (state.wreck) return false;
        ServerWorld world = (ServerWorld) heli.getWorld();
        if ((state.fireParts & MAIN_FIRE) != 0) {
            state.rotor = Math.max(0, state.rotor - BURN_DAMAGE_PER_TICK);
            if (state.rotor > 0 && heli.age % PARTICLE_INTERVAL == 0) particles(heli, world, false);
        }
        if ((state.fireParts & TAIL_FIRE) != 0) {
            state.tail = Math.max(0, state.tail - BURN_DAMAGE_PER_TICK);
            if (state.tail > 0 && heli.age % PARTICLE_INTERVAL == 0) particles(heli, world, true);
        }
        if (heli.age % PARTICLE_INTERVAL == 0) {
            if ((state.fireParts & ENGINE_FIRE) != 0) for (Vec3d point : ENGINE_POINTS) emit(heli, world, point, 1);
            if ((state.fireParts & TANK_FIRE) != 0) for (Vec3d point : TANK_POINTS) emit(heli, world, point, 1);
        }
        refresh(heli);
        if (state.engine < EXPLOSION_THRESHOLD && engineTicks < Long.MAX_VALUE) engineTicks++;
        if (state.tank < EXPLOSION_THRESHOLD && tankTicks < Long.MAX_VALUE) tankTicks++;
        if (engineTicks > 0 && engineTicks % TICKS_PER_SECOND == 0
                && heli.getRandom().nextDouble() * 100 < engineProbability(engineTicks / TICKS_PER_SECOND)) {
            clear();
            return true;
        }
        if (tankTicks > 0 && tankTicks % TICKS_PER_SECOND == 0
                && heli.getRandom().nextDouble() * 100 < tankProbability(tankTicks / TICKS_PER_SECOND)) {
            clear();
            return true;
        }
        return false;
    }

    /** 引擎每秒百分比概率 π ln(x)，x≤1 时为零；纯数学运算，无 MC API，服务端抽签时调用。 */
    static double engineProbability(double seconds) {
        return seconds <= 1 ? 0 : Math.clamp(Math.PI * Math.log(seconds), 0, 100);
    }

    /** 油箱每秒百分比概率 exp(x/π²)−√π，钳制 0..100；纯数学运算，避免将百分数误作 0..1。 */
    static double tankProbability(double seconds) {
        return Math.clamp(Math.exp(seconds / (Math.PI * Math.PI)) - Math.sqrt(Math.PI), 0, 100);
    }

    /** 修复交互后立即清除已恢复部件的计时；无 MC API，不重置另一仍低血量部件，残骸清除双方。 */
    private void resetRecovered(FlightState state) {
        if (state.wreck || state.engine >= EXPLOSION_THRESHOLD) engineTicks = 0;
        if (state.wreck || state.tank >= EXPLOSION_THRESHOLD) tankTicks = 0;
    }

    /**
     * 功能：汇总任意部位火灾并立即复核恢复阈值，tick 和真实维修交互共用。
     * 引用 net.minecraft.world.World#isRaining 仅熄灭外露旋翼火灾；引擎/油箱内部火灾仍保持爆炸准备。
     * 端侧/线程：仅服务端主线程；零耐久桨叶已消失，引擎/油箱零耐久仍危险，残骸清除全部火源。
     */
    public void refresh(HelicopterEntity heli) {
        FlightState state = heli.flight;
        resetRecovered(state);
        state.fireParts = 0;
        if (state.wreck) return;
        if (!heli.getWorld().isRaining()) {
            if (state.rotor > 0 && state.rotor < BURN_THRESHOLD) state.fireParts |= MAIN_FIRE;
            if (state.tail > 0 && state.tail < BURN_THRESHOLD) state.fireParts |= TAIL_FIRE;
        }
        if (state.engine < EXPLOSION_THRESHOLD) state.fireParts |= ENGINE_FIRE;
        if (state.tank < EXPLOSION_THRESHOLD) state.fireParts |= TANK_FIRE;
    }

    /** 事故发生时清除双方计时，防止当前 tick 或后续残骸生命周期再次计算；无 MC API。 */
    public void clear() { engineTicks = tankTicks = 0; }

    /**
     * 功能：在桨毂附近的桨叶根部发出原版火焰和烟雾，姿态与实际机模一致。
     * 引用 net.minecraft.particle.ParticleTypes#SMALL_FLAME/#SMOKE 复用原版材质与生命周期，
     * net.minecraft.server.world.ServerWorld#spawnParticles 同步给附近玩家；只在服务端每四 tick 调用。
     * 排查：转向/倾斜后错位查 HelicopterCollision.Pose.rotation；零耐久时由 tick 停止发射。
     */
    private static void particles(HelicopterEntity heli, ServerWorld world, boolean tail) {
        Vec3d hub = tail ? TAIL_HUB : MAIN_HUB;
        double angle = heli.getRandom().nextDouble() * Math.PI * 2;
        double radius = tail ? .35 : .9;
        emit(heli, world, hub.add(tail ? 0 : Math.cos(angle) * radius,
                tail ? Math.cos(angle) * radius : 0, Math.sin(angle) * radius), tail ? 2 : 3);
    }

    /** 将局部小火源按机体姿态转到世界位置，net.minecraft.server.world.ServerWorld 发送原版粒子；服务端调用。 */
    private static void emit(HelicopterEntity heli, ServerWorld world, Vec3d point, int flames) {
        Vector3f local = new Vector3f((float) point.x, (float) point.y, (float) point.z)
                .rotate(HelicopterCollision.pose(heli).rotation());
        Vec3d position = heli.getPos().add(local.x, local.y, local.z);
        world.spawnParticles(ParticleTypes.SMALL_FLAME, position.x, position.y, position.z, flames, .08, .07, .08, .01);
        world.spawnParticles(ParticleTypes.SMOKE, position.x, position.y + .12, position.z, 1, .10, .08, .10, .02);
    }

    /** 使用 net.minecraft.nbt.NbtCompound 写入 Flight 快照/存档，保持跨加载的危险时长；服务端主线程。 */
    public void write(NbtCompound nbt) {
        nbt.putLong("EngineDangerTicks", engineTicks);
        nbt.putLong("TankDangerTicks", tankTicks);
    }

    /** 从 net.minecraft.nbt.NbtCompound 读取非负 tick，旧存档缺字段按零处理；实体读档/客户端快照时调用。 */
    public void read(NbtCompound nbt, FlightState state) {
        engineTicks = Math.max(0, nbt.getLong("EngineDangerTicks"));
        tankTicks = Math.max(0, nbt.getLong("TankDangerTicks"));
        resetRecovered(state);
    }
}
