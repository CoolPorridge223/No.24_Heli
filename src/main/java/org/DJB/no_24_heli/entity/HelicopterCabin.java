/** 文件：四座舱位、四扇铰接门及乘降时序；端侧：COMMON；随 helicopter 的 Flight 快照同步。 */
package org.DJB.no_24_heli.entity;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：固定座号与乘客列表分离，服务端裁决最近门乘降、占座拖拽、门时序和出口空间，客户端只读同一动画时间线。
 * 引用 MC API：
 *   - net.minecraft.entity.Entity#startRiding/stopRiding：真实乘骑与离座；
 *   - net.minecraft.entity.EntityPose.STANDING、World#getBlockCollisions：检查门外站立净空；
 *   - net.minecraft.nbt.NbtCompound：保存座位 UUID、动作与门状态。
 * 生命周期：每架飞机一个实例，世界主线程 tick；渲染线程仅读取插值坐标。
 * 端侧/线程：COMMON，逻辑服务端权威；客户端不写座位、门或动作状态。
 * 排查：错座查 UUID 快照；门打不开查距离/侧别/门进度；动画跳变查世界 tick 和同步时间；
 *      地面仍无法下机查 findExit 与 findFallbackExit/forceDismount 的兜底路径。
 */
public final class HelicopterCabin {
    /** 0 左前主驾、1 右前副驾、2 左后、3 右后；后排距前排 0.74 格，避开向机尾收窄的舱壁。 */
    public static final int SEATS = 4;
    public static final double ROW_SPACING = .74;
    /** 沿用原机身的两块侧窗；中柱宽 0.07 格，前后门均以前缘为轴向外打开 78 度。 */
    public static final double DOOR_BACK = -1.15, REAR_DOOR_FRONT = -.38,
            FRONT_DOOR_BACK = -.31, DOOR_FRONT = .50, DOOR_ANGLE = 78;
    public static final int DOOR_TICKS = 10, BOARD_TICKS = 24, EXIT_TICKS = 20, JUMP_TICKS = 12;
    /** 动作编号：静坐、迈入、地面走下、空中跃出、被拖出；夺座先开门，再播放拎起、左肘击和倒地。 */
    public static final int SEATED = 0, BOARD = 1, EXIT = 2, JUMP = 3, PULL = 4;
    /** 开门后的动作持续 44 tick（2.2 秒）；以下归一化节点由位置、骨骼和镜头共用。 */
    public static final int PULL_TICKS = 44;
    public static final float PULL_GRAB = .18f, PULL_LIFT = .48f, PULL_WINDUP = .64f,
            PULL_HIT = .70f, PULL_LANDED = .96f;
    /** 同侧出口从近到远找可站立点，单位格；升降范围容纳台阶、薄停机坪及收架后的低机身。 */
    private static final double[] EXIT_DISTANCES = {2.05, 2.4, 2.75}, EXIT_OFFSETS = {0, -.35, .35};
    private static final double EXIT_STEP_UP = 1.1, EXIT_STEP_DOWN = 1.5, EXIT_FLOOR_GAP = .02;
    /**
     * 机门被地形或实体卡住时的兜底搜索：本侧优先，再试对侧；距离和前后偏移比正常下机更宽。
     * 纵向多留 2.5 格，覆盖机身停在台阶、斜坡、浅坑或收架后的低姿态。单位：格。
     */
    private static final double[] FALLBACK_DISTANCES = {2.05, 2.4, 2.75, 3.2, 3.8},
            FALLBACK_OFFSETS = {0, -.35, .35, -.7, .7};
    private static final double FALLBACK_STEP_UP = 2.0, FALLBACK_STEP_DOWN = 4.0;
    /** 权威座位身份；动作在保持真实乘骑期间完成，结束离座才释放席位。 */
    private final UUID[] occupants = new UUID[SEATS];
    /** 每座当前动作（SEATED/BOARD/EXIT/JUMP）；同时驱动服务端时序和客户端插值。 */
    private final int[] motions = new int[SEATS];
    /** 动作开始的世界 tick；门未全开时先等待，之后再按动作时长推进。 */
    private final long[] started = new long[SEATS];
    /** 动画入口/出口的机身局部坐标；null 表示静坐或没有待用落点。 */
    private final Vec3d[] outside = new Vec3d[SEATS];
    /** Y 下绳先等待本座门打开，服务端再次检查悬停后转移；不跨存档恢复请求。 */
    private final boolean[] ropeExits = new boolean[SEATS];
    /** 每座唯一拉人者 UUID；动作完成前原乘员继续占座，杜绝重复按键或第三人插队。 */
    private final UUID[] pullers = new UUID[SEATS];
    /** 门号与座号一致，四门独立；世界总 tick 时间戳避免实体重追踪时重新播放。 */
    private final boolean[] doors = new boolean[SEATS];
    /** 每扇门最后一次目标变化的世界 tick，用于反向插值关闭；初始视为已完全关闭。 */
    private final long[] doorChanged = {-DOOR_TICKS, -DOOR_TICKS, -DOOR_TICKS, -DOOR_TICKS};
    /** 所属飞机；舱位组件不持有世界或玩家引用，跨维度/卸载随实体失效。 */
    private final HelicopterEntity heli;

    /** 由所属实体构造；不访问尚未初始化的 net.minecraft.entity.Entity 数据追踪器。 */
    public HelicopterCabin(HelicopterEntity heli) { this.heli = heli; }

    /** net.minecraft.entity.Entity#getUuid 是跨保存/重连稳定的座位标识；未分配返回 -1。 */
    public int seat(Entity entity) {
        for (int i = 0; i < SEATS; i++) if (entity.getUuid().equals(occupants[i])) return i;
        return -1;
    }

    /** 返回指定座位的实际乘员，不能用乘客列表下标推断驾驶权；两端只读。 */
    public Entity occupant(int seat) {
        for (Entity entity : heli.getPassengerList()) if (entity.getUuid().equals(occupants[seat])) return entity;
        return null;
    }

    /** +Z 机头的左侧为 +X；偶数座在左侧，奇数座在右侧。 */
    public static int side(int seat) { return seat & 1; }

    /** 前后门的纵向后缘，单位格；渲染拆面和碰撞共用同一边界。 */
    public static double doorBack(int door) { return door < 2 ? FRONT_DOOR_BACK : DOOR_BACK; }

    /** 前后门的纵向前缘，单位格；两扇门之间保留原有固定中柱。 */
    public static double doorFront(int door) { return door < 2 ? DOOR_FRONT : REAR_DOOR_FRONT; }

    /** 竖直铰链位于各门前缘最宽处；net.minecraft.util.math.Vec3d 供画面与命中共用。 */
    public static Vec3d doorHinge(int door) {
        return new Vec3d((side(door) == 0 ? 1 : -1) * (door < 2 ? .91 : .975), 1.24, doorFront(door));
    }

    /** 左右镜像外开角，单位弧度；不后滑，前后门均采用轿车式前缘铰链。 */
    public static float doorAngle(int door, float progress) {
        return (float) Math.toRadians((side(door) == 0 ? -1 : 1) * DOOR_ANGLE * progress);
    }

    /**
     * 座位脚位保留前排基准；后排每侧内移 0.10 格、抬高 0.02 格，避开后舱向下收窄的机腹。
     * net.minecraft.util.math.Vec3d 供模型、乘员和眼位共用，不能只移动可见椅子。
     */
    public static Vec3d seatPosition(int seat) {
        double lateral=seat>=2?.28:.38;
        return new Vec3d(side(seat) == 0 ? lateral : -lateral, seat>=2?.12:.10,
                -.12 + HelicopterEntity.SEAT_FORWARD_OFFSET - (seat >= 2 ? ROW_SPACING : 0));
    }

    /** 将玩家实际世界位置旋回机身坐标；服务端侧别不信任客户端提交。 */
    public Vec3d localPosition(Entity entity) {
        return entity.getPos().subtract(heli.getPos()).rotateY((float) Math.toRadians(heli.getYaw()));
    }

    /** 舱内使用固定座侧；舱外使用机身坐标，飞机旋转后仍保持左右一致。 */
    public int accessSide(Entity entity) {
        int seat = seat(entity);
        if(entity.getVehicle() instanceof RopeSeatEntity rope && rope.helicopter()==heli)return 1;
        return seat >= 0 ? side(seat) : localPosition(entity).x >= 0 ? 0 : 1;
    }

    /** 舱内使用本座门，绳顶保留后排优先；舱外按真实门面中心距离选门，不因占座改选别座。 */
    public int accessDoor(Entity entity) {
        int seat = seat(entity);
        if (seat >= 0) return seat;
        if (entity.getVehicle() instanceof RopeSeatEntity rope && rope.helicopter() == heli) {
            int free = freeRopeSeat();
            return free >= 0 ? free : 1;
        }
        Vec3d local = localPosition(entity);
        int side = accessSide(entity), rear = side + 2;
        Vec3d frontCenter = new Vec3d(side == 0 ? .94 : -.94, 0, (doorBack(side) + doorFront(side)) / 2);
        Vec3d rearCenter = new Vec3d(side == 0 ? .94 : -.94, 0, (doorBack(rear) + doorFront(rear)) / 2);
        return local.squaredDistanceTo(frontCenter) <= local.squaredDistanceTo(rearCenter) ? side : rear;
    }

    /** 外部操作限定在侧门附近三格内，排除机鼻、长尾、旁观者和其他载具。 */
    public boolean canReach(PlayerEntity player) {
        return !CabinPlayerState.locked(player) && canReachPosition(player);
    }

    /** 拖拽续帧只跳过本动作的输入锁，仍复核与普通登机相同的存活、载具和门外位置边界。 */
    private boolean canReachPosition(PlayerEntity player) {
        if (heli.flight.wreck || player.isSpectator() || !player.isAlive()) return false;
        if (heli.hasPassenger(player)) return true;
        // mount 已验证绳顶资格后预占空座；原版 startRiding 再次检查时不能把最后一座的预占当成满员。
        if(player.getVehicle() instanceof RopeSeatEntity rope)
            return rope.helicopter()==heli&&(seat(player)>=0||rope.canBoardHelicopter(player));
        if(player.hasVehicle())return false;
        Vec3d local = localPosition(player);
        return Math.abs(local.x) >= .55 && Math.abs(local.x) <= 3
                && local.z >= DOOR_BACK - .65 && local.z <= DOOR_FRONT + .65
                && local.y >= -1.5 && local.y <= 2.5;
    }

    /** 同侧前排优先；两侧都满也不允许通过另一扇门跨侧占座。 */
    public int freeSeat(int side) {
        return occupants[side] == null ? side : occupants[side + 2] == null ? side + 2 : -1;
    }

    /** 绳顶入舱先右后、左后，再右前、左前；任意一座空闲即可登机，不受所在侧限制。 */
    public int freeRopeSeat() {
        for (int seat = SEATS - 1; seat >= 0; seat--) if (occupants[seat] == null) return seat;
        return -1;
    }

    /** 门的目标状态；仅供模型、碰撞和同步状态读取。 */
    public boolean isOpen(int door) { return doors[door]; }

    /** 世界时间插值铰接门，关闭过程反向播放；返回 0..1，不依赖客户端帧率。 */
    public float doorProgress(int door, float delta) {
        float t = MathHelper.clamp((heli.getWorld().getTime() - doorChanged[door] + delta) / DOOR_TICKS, 0, 1);
        t = t * t * (3 - 2 * t);
        return doors[door] ? t : 1 - t;
    }

    /** 完全打开才放行；门动画中不能穿过仍覆盖入口的蒙皮。 */
    public boolean canPass(int door) { return doors[door] && doorProgress(door, 0) >= .99f; }

    /** F 的统一乘降入口：机外登机，机内请求下机；服务端复核范围，空中确认交给实体。 */
    public void interact(PlayerEntity player) {
        if (heli.getWorld().isClient || !canReach(player)) return;
        if (heli.hasPassenger(player)) heli.requestDismount(player);
        else if (!(player.getVehicle() instanceof RopeSeatEntity) && occupants[accessDoor(player)] != null) beginTakeOver(player);
        else board(player);
    }

    /** 自动乘降设置门状态；反向开门沿已有进度继续，避免地面下机后立即登机时门板跳变。 */
    private void setDoor(int door, boolean open) {
        if (doors[door] == open) return;
        long now = heli.getWorld().getTime();
        long elapsed = Math.clamp(now - doorChanged[door], 0L, (long) DOOR_TICKS);
        doorChanged[door] = now - (DOOR_TICKS - elapsed);
        doors[door] = open;
    }

    /** 普通上机固定使用最近门的对应座；占座时仅 F 入口可以发起拖拽，本方法不强行换人。 */
    public boolean board(PlayerEntity player) {
        if (!canReach(player) || heli.hasPassenger(player)) return false;
        if (player.getVehicle() instanceof RopeSeatEntity) return boardFromRope(player);
        int seat = accessDoor(player);
        if (occupants[seat] != null) { tell(player, "seat_occupied"); return false; }
        return mount(player, seat, true);
    }

    /**
     * 功能：服务端原子切换固定座位；只移动本机已坐稳乘员的 UUID，不触发离机或副驾自动接管。
     * 引用 MC API：net.minecraft.entity.Entity#hasPassenger 复核真实乘骑；PlayerEntity#isAlive 拒绝失效请求。
     * 端侧/线程：逻辑服务端主线程；参数：player 为发包乘员，target 为 0..3；返回：无。
     * 副作用：转移座位、关闭原座门并立即同步；涉及主驾时清除旧输入和自动驾驶，保留当前总距。
     * 排查：占座含正在登机或被拖拽的预占 UUID；乘降、下绳和眩晕期间不能换座，也不交换或挤出他人。
     */
    public void switchSeat(PlayerEntity player, int target) {
        if (heli.getWorld().isClient || !heli.hasPassenger(player) || heli.flight.wreck
                || !player.isAlive() || player.isSpectator() || target < 0 || target >= SEATS) return;
        int source = seat(player);
        if (source < 0 || source == target) return;
        if (occupants[target] != null) { tell(player, "seat_switch_occupied"); return; }
        if (motions[source] != SEATED || ropeExits[source] || pullers[source] != null || CabinPlayerState.locked(player)) {
            tell(player, "seat_switch_unavailable"); return;
        }
        occupants[target] = occupants[source]; occupants[source] = null;
        motions[source] = motions[target] = SEATED;
        outside[source] = outside[target] = null;
        ropeExits[source] = ropeExits[target] = false;
        started[source] = started[target] = heli.getWorld().getTime();
        setDoor(source, false);
        if (source == 0 || target == 0) {
            heli.stopAutopilot(); heli.flight.cancelAutoFlight(); heli.flight.clearInputs();
        }
        heli.syncCabin();
    }

    /** 服务端与 HUD 共用占座拖拽资格；只处理停稳在陆地、坐稳且存活的玩家，不影响空中离机。 */
    public boolean canTakeOver(PlayerEntity player) {
        if (!canReach(player) || player.hasVehicle() || !heli.isCabinGrounded() || heli.isTouchingWater()
                || heli.getVelocity().horizontalLengthSquared() > .01
                || heli.flight.vx * heli.flight.vx + heli.flight.vz * heli.flight.vz > .01
                || Math.abs(heli.flight.vy) > .1) return false;
        int seat = accessDoor(player);
        return occupant(seat) instanceof PlayerEntity victim && victim.isAlive() && !victim.isSpectator()
                && motions[seat] == SEATED && !ropeExits[seat] && pullers[seat] == null;
    }

    /** 拉人者的同步 ID 必须与本座 UUID 和 PULL 动作同时成立；不信任单独的客户端状态。 */
    public boolean isPulling(Entity player, int seat) {
        return seat >= 0 && seat < SEATS && motions[seat] == PULL && player.getUuid().equals(pullers[seat]);
    }

    /** 返回本座被拖者，供拉人者的手臂和正面镜头复用同一进度；未在动作中返回 null。 */
    public Entity pulledOccupant(Entity player) {
        for (int seat = 0; seat < SEATS; seat++) if (isPulling(player, seat)) return occupant(seat);
        return null;
    }

    /** 开门前先确认门外落点；两端角色不换座，服务端等拖拽完成才释放原乘员并挂载新玩家。 */
    private void beginTakeOver(PlayerEntity player) {
        if (!canTakeOver(player)) { tell(player, "takeover_unavailable"); return; }
        int seat = accessDoor(player);
        pullers[seat] = player.getUuid();
        Vec3d target = findExit(occupant(seat), seat, false);
        if (target == null) { pullers[seat] = null; tell(player, "door_exit_blocked"); return; }
        setDoor(seat, true); outside[seat] = target; motions[seat] = PULL;
        started[seat] = Math.max(heli.getWorld().getTime(), doorChanged[seat] + DOOR_TICKS);
        ((CabinPlayerState) player).cabinPull(heli.getId(), seat);
        if (seat == 0) { heli.stopAutopilot(); heli.flight.cancelAutoFlight(); heli.flight.clearInputs(); }
        heli.syncCabin();
    }

    /** 取消时保留原座并关门；玩家追踪字段也同时释放，避免离开范围/断线后残留动作。 */
    private void cancelTakeOver(int seat) {
        PlayerEntity player = pullers[seat] == null ? null : heli.getWorld().getPlayerByUuid(pullers[seat]);
        if (player != null) ((CabinPlayerState) player).cabinPull(-1, -1);
        pullers[seat] = null; motions[seat] = SEATED; outside[seat] = null;
        setDoor(seat, false); heli.syncCabin();
    }

    /**
     * 服务端逐 tick 复核双方、停稳状态及地面落点；中途失效取消，成功离座后才开始三秒趴地计时。
     * net.minecraft.entity.Entity#stopRiding 完成真实离座，随后 mount 保持原固定座号，不提升其他乘员。
     */
    private void tickTakeOver(Entity victim, int seat) {
        PlayerEntity player = pullers[seat] == null ? null : heli.getWorld().getPlayerByUuid(pullers[seat]);
        if (player == null || !player.isAlive() || player.isSpectator() || player.hasVehicle() || !victim.isAlive()
                || heli.flight.wreck || !heli.isCabinGrounded() || heli.isTouchingWater()
                || !canReachPosition(player) || accessDoor(player) != seat
                || heli.flight.vx * heli.flight.vx + heli.flight.vz * heli.flight.vz > .01 || Math.abs(heli.flight.vy) > .1) {
            cancelTakeOver(seat); return;
        }
        if (progress(victim, 0) < 1) return;
        Vec3d local = findExit(victim, seat, false);
        if (local == null) { tell(player, "door_exit_blocked"); cancelTakeOver(seat); return; }
        outside[seat] = local;
        Vec3d pullerEnd = toWorld(pullerPosition(player, 0));
        // removed 会清空本座拖拽身份；先保存操作者，解除后再进入常规登机动画。
        ((CabinPlayerState) player).cabinPull(-1, -1);
        victim.stopRiding(); victim.refreshPositionAfterTeleport(toWorld(local)); victim.setVelocity(Vec3d.ZERO);
        ((CabinPlayerState) victim).cabinStun(heli.getWorld().getTime() + CabinPlayerState.STUN_TICKS,
                heli.getYaw() + (side(seat) == 0 ? -90 : 90));
        // 真实登机入口接续最后一帧的后撤脚位，避免视觉拉人者突然跳回动作前的位置。
        player.refreshPositionAfterTeleport(pullerEnd);
        mount(player, seat, true);
    }

    /** 绳顶 F/E 均后排优先，后排满后进入任意前排空座；自动打开对应门，坐稳后关门。 */
    public boolean boardFromRope(PlayerEntity player) {
        if (!canReach(player)) return false;
        int seat = freeRopeSeat();
        return seat >= 0 && mount(player, seat, true);
    }

    /** 权威端先预占座位，开门期间保持入口脚位；门完全打开才开始迈入，失败时撤销预占。 */
    public boolean mount(Entity entity, int seat, boolean animate) {
        if (heli.getWorld().isClient || heli.flight.wreck || seat < 0 || seat >= SEATS || occupants[seat] != null
                || CabinPlayerState.locked(entity)) return false;
        Vec3d entry=localPosition(entity);
        boolean rope=entity.getVehicle() instanceof RopeSeatEntity;
        occupants[seat] = entity.getUuid();
        if (!entity.startRiding(heli, true)) { occupants[seat] = null; return false; }
        outside[seat] = rope ? new Vec3d(side(seat) == 0 ? 1.65 : -1.65, .05, seatPosition(seat).z) : entry;
        setDoor(seat, animate);
        motions[seat] = animate ? BOARD : SEATED;
        started[seat] = Math.max(heli.getWorld().getTime(), doorChanged[seat] + DOOR_TICKS);
        entity.fallDistance = 0; heli.syncCabin(); return true;
    }

    /** 下绳沿用 Y 的安全悬停资格，自动开门并等待；返回 true 才允许真实转移到绳实体。 */
    public boolean prepareRappel(PlayerEntity player) {
        int seat = seat(player);
        if (seat < 0 || motions[seat] != SEATED) return false;
        if (canPass(seat)) return true;
        setDoor(seat, true); ropeExits[seat] = true; heli.syncCabin();
        return false;
    }

    /** 原版存档恢复/命令挂载回调：已有 UUID 保持原座，新玩家按最近门分配，其他实体占首个空位。 */
    public void added(Entity entity) {
        if (heli.getWorld().isClient || seat(entity) >= 0) return;
        if(entity instanceof PlayerEntity){
            int seat=accessDoor(entity);
            if(occupants[seat]==null)occupants[seat]=entity.getUuid();
        }else for (int i = 0; i < SEATS; i++) if (occupants[i] == null) { occupants[i] = entity.getUuid(); break; }
        heli.syncCabin();
    }

    /** 离座只在空中将仍坐稳的副驾玩家提升到主驾；后排、村民及正在退出的副驾永不接管。 */
    public void removed(Entity entity, boolean airborne) {
        if (heli.getWorld().isClient) return;
        int seat = seat(entity);
        if (seat < 0) return;
        if (pullers[seat] != null) cancelTakeOver(seat);
        occupants[seat] = null; motions[seat] = SEATED; outside[seat] = null; ropeExits[seat] = false;
        if (seat == 0 && airborne && !heli.flight.wreck && occupant(1) instanceof PlayerEntity
                && motions[1] == SEATED) {
            occupants[0] = occupants[1]; occupants[1] = null;
            motions[0]=motions[1]=SEATED;outside[0]=outside[1]=null;
            heli.flight.clearInputs();
        }
        heli.syncCabin();
    }

    /** 当前动作编号；原版挂载包先于座位快照时暂按静坐处理，不播放错误侧动画。 */
    public int motion(Entity entity) { int seat = seat(entity); return seat < 0 ? SEATED : motions[seat]; }

    /** 动作进度使用同一服务端时间戳；开门等待期间为零，门未全开时保持入口或座位脚位。 */
    public float progress(Entity entity, float delta) {
        int seat = seat(entity);
        if (seat < 0 || motions[seat] == SEATED) return 1;
        int duration = motions[seat] == BOARD ? BOARD_TICKS : motions[seat] == JUMP ? JUMP_TICKS
                : motions[seat] == PULL ? PULL_TICKS : EXIT_TICKS;
        return MathHelper.clamp((heli.getWorld().getTime() - started[seat] + delta) / duration, 0, 1);
    }

    /**
     * 功能：入口抬脚、跨门槛、转向坐下；离机按反向站起走下或向外跃出。
     * 引用 net.minecraft.util.math.Vec3d#lerp 插值局部脚位；两端读取，不写玩家视角。
     * 排查：人物穿门查 DOOR_BACK/FRONT；第一人称眼高须叠加在该脚位之上。
     */
    public Vec3d position(Entity entity, float delta) {
        int seat = seat(entity);
        if (seat < 0) return seatPosition(0);
        Vec3d seated = seatPosition(seat);
        if (motions[seat] == SEATED || outside[seat] == null) return seated;
        float p = progress(entity, delta);
        if (motions[seat] == PULL) {
            float lift = pullPhase(p, PULL_GRAB, PULL_LIFT);
            float fall = pullPhase(p, PULL_HIT, PULL_LANDED);
            return seated.lerp(outside[seat], pullPhase(p, .06f, PULL_LIFT))
                    .add(0, .30 * lift * (1 - fall), 0); // 双手拎起三十厘米，受击后才落向地面。
        }
        double t = motions[seat] == BOARD ? p : 1 - p;
        Vec3d threshold = new Vec3d(side(seat) == 0 ? .90 : -.90, .38, seated.z);
        Vec3d point = t < .55 ? outside[seat].lerp(threshold, smooth(t / .55))
                : threshold.lerp(seated, smooth((t - .55) / .45));
        if (motions[seat] == JUMP) point = point.add(0, Math.sin(p * Math.PI) * .32, 0);
        return point;
    }

    /** 平滑步进函数；参数为归一化时间，避免端点速度突变。 */
    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** 动作片段的平滑进度；COMMON 两端只读，共享端点保证抓取、肘击、倒地与镜头同时发生。 */
    public static float pullPhase(float progress, float from, float to) {
        return (float) smooth(MathHelper.clamp((progress - from) / (to - from), 0, 1));
    }

    /**
     * 功能：拉人者先靠近门把手，再双手后撤拎出乘员，击中后向旁边让出倒地空间。
     * 引用 net.minecraft.util.math.Vec3d#lerp 只计算机身局部脚位；渲染读取，服务端完成时接回同一登机入口。
     * 排查：双手够不到乘员先查门侧及 PULL_LIFT；不改输入锁使用的真实站位或普通上下机路径。
     */
    public Vec3d pullerPosition(Entity player, float delta) {
        Entity victim = pulledOccupant(player);
        if (victim == null) return localPosition(player);
        int seat = seat(victim);
        float p = progress(victim, delta), sign = side(seat) == 0 ? 1 : -1;
        Vec3d near = new Vec3d(sign * 1.30, outside[seat].y, seatPosition(seat).z - .35);
        Vec3d retreat = outside[seat].add(sign * .75, 0, 0);
        return localPosition(player).lerp(near, doorProgress(seat, delta))
                .lerp(retreat, pullPhase(p, PULL_GRAB, PULL_LIFT))
                .add(0, 0, .65 * pullPhase(p, PULL_HIT, PULL_LANDED));
    }

    /**
     * 功能：确认离机后自动开门，等待门全开再起身；动画期间保留座位。
     * 地面或水面出口被地形卡住时不再播放动画，直接按原版下载具离开，避免乘员被困；
     * 空中没有安全落点仍保留座位并提示，不能把跃出改成自由落体。
     * 引用 net.minecraft.entity.player.PlayerEntity 只传玩家；服务端调用。
     */
    public void beginExit(PlayerEntity player, boolean airborne) {
        int seat = seat(player);
        if (seat < 0 || motions[seat] != SEATED) return;
        Vec3d target = findExit(player, seat, airborne);
        if (target == null) {
            // 地面/水面的“门被卡住”不应把玩家锁在座位上；空中仍必须保留二次确认后的安全跃出。
            if (!airborne) { forceDismount(player, seat); return; }
            tell(player, "door_exit_blocked"); return;
        }
        ropeExits[seat] = false;
        setDoor(seat, true);
        outside[seat] = target; motions[seat] = airborne ? JUMP : EXIT;
        started[seat] = Math.max(heli.getWorld().getTime(), doorChanged[seat] + DOOR_TICKS);
        heli.syncCabin();
    }

    /** 与实体服务端脚位一致，只旋转偏航；相机/人物视觉再使用机体俯仰横滚。 */
    public Vec3d toWorld(Vec3d local) {
        return heli.getPos().add(local.rotateY((float) -Math.toRadians(heli.getYaw())));
    }

    /** 供 net.minecraft.entity.Entity#updatePassengerForDismount 读取仍保留的同侧出口。 */
    public Vec3d exitPosition(Entity passenger) {
        int seat = seat(passenger);
        return toWorld(seat >= 0 && outside[seat] != null ? outside[seat]
                : new Vec3d(accessSide(passenger) == 0 ? 2.05 : -2.05, .10, 0));
    }

    /**
     * 功能：在本座同侧门外按真实碰撞面找站立落点，不把固定机身高度当成地面高度。
     * 引用 net.minecraft.world.World#raycast 读取台阶/半砖顶面；EntityPose.STANDING 保证站起后仍有头部净空。
     * 服务端开始及完成离机各复核一次；空中保持已确认的跃出高度，水面允许正常离机入水。
     * 排查：不跨到对侧、不越过侧墙；无完整站立空间才返回 null 并保留座位。
     */
    private Vec3d findExit(Entity passenger, int seat, boolean airborne) {
        for (double distance : EXIT_DISTANCES) for (double offset : EXIT_OFFSETS) {
            Vec3d local = new Vec3d(side(seat) == 0 ? distance : -distance, .10, seatPosition(seat).z + offset);
            Vec3d target = toWorld(local);
            if (!heli.getWorld().isChunkLoaded(BlockPos.ofFloored(target))) continue;
            if (!airborne && !heli.isTouchingWater()) {
                var hit = heli.getWorld().raycast(new RaycastContext(
                        new Vec3d(target.x, heli.getY() + EXIT_STEP_UP, target.z),
                        new Vec3d(target.x, heli.getY() - EXIT_STEP_DOWN, target.z),
                        RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, passenger));
                if (hit.getType() == HitResult.Type.MISS) continue;
                target = new Vec3d(target.x, hit.getPos().y + EXIT_FLOOR_GAP, target.z);
            }
            if (exitClear(passenger, seat, side(seat), target))
                return target.subtract(heli.getPos()).rotateY((float) Math.toRadians(heli.getYaw()));
        }
        return null;
    }

    /**
     * 功能：检查站立空间和门槛至落点的外侧通道，只排除本机乘员，其他直升机按真实分部盒检查。
     * 引用 net.minecraft.world.World#getBlockCollisions/#getOtherEntities 查询环境，WorldBorder.contains 拦住边界。
     * 参数 side 由调用方显式给出：正常下机使用本座侧，兜底下机可能搜索对侧，不能再用座位侧反推门槛位置。
     * 服务端只读；不使用乘坐中的旧包围盒，不允许为了换落点穿过真实墙壁或停在打开的门板中。
     */
    private boolean exitClear(Entity passenger, int seat, int side, Vec3d target) {
        var dimensions = passenger.getDimensions(EntityPose.STANDING);
        Box standing = dimensions.getBoxAt(target);
        Vec3d threshold = toWorld(new Vec3d(side == 0 ? 1.3 : -1.3, .4, seatPosition(seat).z));
        threshold = new Vec3d(threshold.x, Math.max(threshold.y, target.y), threshold.z);
        Vec3d outside = new Vec3d(target.x, threshold.y, target.z);
        Box passage = dimensions.getBoxAt(threshold).stretch(outside.subtract(threshold));
        Box stepDown = dimensions.getBoxAt(outside).stretch(target.subtract(outside));
        // 先跨出门槛再落脚；整块斜向包围盒会把旁侧薄平台或台阶误判为挡门墙壁。
        if (!heli.getWorld().getWorldBorder().contains(passage)
                || !heli.getWorld().getWorldBorder().contains(stepDown)
                || heli.getWorld().getBlockCollisions(passenger, passage).iterator().hasNext()
                || heli.getWorld().getBlockCollisions(passenger, stepDown).iterator().hasNext()) return false;
        for (HelicopterCollision.Shape part : HelicopterCollision.shapes(heli))
            if (part.intersects(standing)) return false;
        for (Entity other : heli.getWorld().getOtherEntities(passenger, standing.expand(HelicopterCollision.QUERY_RADIUS),
                other -> !other.isSpectator() && !other.isRemoved() && !heli.isConnectedThroughVehicle(other)
                        && !other.getUuid().equals(pullers[seat]))) {
            if (other instanceof HelicopterEntity aircraft) {
                for (HelicopterCollision.Shape part : HelicopterCollision.shapes(aircraft))
                    if (part.intersects(standing)) return false;
            } else if ((other.isCollidable() || other instanceof net.minecraft.entity.LivingEntity)
                    && other.getBoundingBox().intersects(standing)) return false;
        }
        return true;
    }

    /**
     * 功能：在机身两侧按由近到远的距离寻找可站立落点，供机门被卡住时直接下机。
     *
     * 引用 MC API：
     *   - net.minecraft.world.World#raycast(RaycastContext)：按真实方块碰撞顶面确定脚位，
     *     水面跳过地面探测，与正常下机共用同一高度语义；
     *   - net.minecraft.world.World#isChunkLoaded(BlockPos)：未加载区块不能作为落点判定依据。
     *
     * 端侧/线程：仅逻辑服务端，主线程只读世界。
     * 返回：机身局部坐标；两侧都找不到可用落点时返回 null，由调用方使用原版包围盒顶部落点。
     * 排查：本侧被堵时会先尝试对侧；落点仍被拒查 exitClear 的站立、门槛、方块和实体检查。
     */
    private Vec3d findFallbackExit(Entity passenger, int seat) {
        int preferred = side(seat);
        // 先本侧、后对侧：对侧只用于兜底，正常下机仍然不会跨侧。
        for (int attempt = 0; attempt < 2; attempt++) {
            int exitSide = attempt == 0 ? preferred : 1 - preferred;
            for (double distance : FALLBACK_DISTANCES) for (double offset : FALLBACK_OFFSETS) {
                Vec3d local = new Vec3d(exitSide == 0 ? distance : -distance, .10, seatPosition(seat).z + offset);
                Vec3d target = toWorld(local);
                if (!heli.getWorld().isChunkLoaded(BlockPos.ofFloored(target))) continue;
                if (!heli.isTouchingWater()) {
                    var hit = heli.getWorld().raycast(new RaycastContext(
                            new Vec3d(target.x, heli.getY() + FALLBACK_STEP_UP, target.z),
                            new Vec3d(target.x, heli.getY() - FALLBACK_STEP_DOWN, target.z),
                            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, passenger));
                    if (hit.getType() == HitResult.Type.MISS) continue;
                    target = new Vec3d(target.x, hit.getPos().y + EXIT_FLOOR_GAP, target.z);
                }
                if (exitClear(passenger, seat, exitSide, target))
                    return target.subtract(heli.getPos()).rotateY((float) Math.toRadians(heli.getYaw()));
            }
        }
        return null;
    }

    /**
     * 功能：跳过开门、抬脚和走下动画，直接解除乘骑并放到兜底落点，保证乘员不会被困在机舱。
     *
     * 引用 MC API：
     *   - net.minecraft.entity.Entity#stopRiding()：原版下载具入口，触发乘员移除和离机碰撞豁免；
     *   - net.minecraft.entity.Entity#refreshPositionAfterTeleport：立即同步最终脚位，避免停留在
     *     updatePassengerForDismount 返回的固定门侧位置；
     *   - net.minecraft.entity.Entity#getBoundingBox：扩展搜索也失败时取包围盒顶面中心作最后落点。
     *
     * 端侧/线程：仅逻辑服务端。参数 seat 为当前座号；调用后该座立即空出。
     * 副作用：解除乘骑、关闭对应舱门并同步舱位快照；不播放下机动画、不清除玩家速度。
     * 排查：玩家仍留在舱内查 stopRiding 是否被其它逻辑重新挂载；落点悬空查扩展搜索与包围盒顶面。
     */
    private void forceDismount(Entity entity, int seat) {
        Vec3d local = findFallbackExit(entity, seat);
        if (local == null) {
            // 与 net.minecraft.entity.Entity#updatePassengerForDismount 的默认落点一致：包围盒顶面中心。
            // 额外抬起 EXIT_FLOOR_GAP，避免脚位正好贴在旋翼/蒙皮顶面产生一帧相交。
            // 只作为扩展搜索也失败时的最后保险，保证玩家一定离开载具。
            local = new Vec3d(0, heli.getBoundingBox().maxY - heli.getY() + EXIT_FLOOR_GAP, 0);
        }
        entity.stopRiding();
        entity.refreshPositionAfterTeleport(toWorld(local));
        setDoor(seat, false);
        heli.syncCabin();
    }

    /**
     * 服务端完成乘降；离机前复查移动后的落点。
     * 地面动画期间地形变化导致落点消失时直接按原版下载具离开，不再退回座位；空中仍回到座位并提示。
     * net.minecraft.entity.Entity#stopRiding 最终解除乘骑，登机坐稳及地面/空中离机后都关闭对应舱门。
     */
    public void tick() {
        if (heli.getWorld().isClient) return;
        for (int seat = 0; seat < SEATS; seat++) {
            Entity entity = occupant(seat);
            // 原版不把离线玩家作为乘员实体保存；重载后没有实际乘员的 UUID 必须释放。
            if(entity==null){
                if (pullers[seat] != null) cancelTakeOver(seat);
                occupants[seat]=null;motions[seat]=SEATED;outside[seat]=null;ropeExits[seat]=false;continue;
            }
            if (motions[seat] == PULL) { tickTakeOver(entity, seat); continue; }
            if (ropeExits[seat] && canPass(seat) && entity instanceof PlayerEntity player) {
                ropeExits[seat] = false; heli.rappel(player);
                if (!heli.hasPassenger(entity)) continue;
            }
            if (motions[seat] == SEATED || progress(entity, 0) < 1) continue;
            int motion = motions[seat];
            if (motion == BOARD) { motions[seat] = SEATED; setDoor(seat, false); heli.syncCabin(); continue; }
            Vec3d local = findExit(entity, seat, motion == JUMP);
            if (local == null) {
                // 地面下机动画已结束但出口被新地形占住：直接离开，避免玩家把座位当成永久牢笼。
                if (motion != JUMP) { forceDismount(entity, seat); continue; }
                motions[seat] = SEATED; setDoor(seat, false);
                if (entity instanceof PlayerEntity player) tell(player, "door_exit_blocked");
                heli.syncCabin(); continue;
            }
            outside[seat] = local;
            Vec3d target = toWorld(local);
            entity.stopRiding(); entity.refreshPositionAfterTeleport(target);
            setDoor(seat, false); heli.syncCabin();
            if (motion == JUMP) {
                Vec3d push = new Vec3d(side(seat) == 0 ? .18 : -.18, .12, 0)
                        .rotateY((float) -Math.toRadians(heli.getYaw()));
                entity.setVelocity(heli.flight.vx + push.x, heli.flight.vy + push.y, heli.flight.vz + push.z);
                entity.velocityModified = true;
                if (entity instanceof PlayerEntity player) ParachuteEntity.startFreefall(player, heli, seat);
            }
        }
    }

    /** 将座位/动作及门时序附加到现有 Flight 快照；net.minecraft.nbt.NbtCompound 兼顾追踪和存档。 */
    public void write(NbtCompound nbt) {
        NbtCompound cabin = new NbtCompound();
        for (int i = 0; i < SEATS; i++) if (occupants[i] != null) {
            NbtCompound seat = new NbtCompound(); seat.putUuid("Occupant", occupants[i]);
            seat.putInt("Motion", motions[i]); seat.putLong("Started", started[i]);
            if (pullers[i] != null) seat.putUuid("Puller", pullers[i]);
            if (outside[i] != null) {
                seat.putDouble("X", outside[i].x); seat.putDouble("Y", outside[i].y); seat.putDouble("Z", outside[i].z);
            }
            cabin.put("Seat" + i, seat);
        }
        cabin.putInt("DoorCount", SEATS);
        for (int i = 0; i < SEATS; i++) { cabin.putBoolean("Open" + i, doors[i]); cabin.putLong("Changed" + i, doorChanged[i]); }
        nbt.put("Cabin", cabin);
    }

    /** 系统边界：旧存档无 Cabin 时由乘员恢复回调分配；重复 UUID/非法动画丢弃并记日志。 */
    public void read(NbtCompound nbt) {
        NbtCompound cabin = nbt.getCompound("Cabin");
        Arrays.fill(occupants, null); Arrays.fill(motions, SEATED); Arrays.fill(outside, null); Arrays.fill(pullers, null);
        for (int i = 0; i < SEATS; i++) {
            NbtCompound seat = cabin.getCompound("Seat" + i);
            if (!seat.containsUuid("Occupant")) continue;
            UUID id = seat.getUuid("Occupant");
            if (Arrays.asList(occupants).contains(id)) { No_24_heli.LOGGER.warn("丢弃重复的直升机座位 UUID：{}", id); continue; }
            occupants[i] = id; started[i] = seat.getLong("Started");
            int motion = seat.getInt("Motion");
            double x = seat.getDouble("X"), y = seat.getDouble("Y"), z = seat.getDouble("Z");
            if (motion < SEATED || motion > PULL || motion == PULL && !seat.containsUuid("Puller")
                    || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 3 || Math.abs(y) > 3 || Math.abs(z) > 2.5) {
                No_24_heli.LOGGER.warn("丢弃非法的直升机乘降动作：{}", id); continue;
            }
            motions[i] = motion; outside[i] = new Vec3d(x, y, z);
            if (motion == PULL) pullers[i] = seat.getUuid("Puller");
        }
        // 兼容旧绳索指定副驾，避免只有副驾的旧飞机在重载后误获得驾驶员。
        if (!nbt.contains("Cabin") && nbt.containsUuid("RopeCopilot")) occupants[1] = nbt.getUuid("RopeCopilot");
        for (int i = 0; i < SEATS; i++) {
            // 旧版一侧一扇滑门的开闭状态同时迁移到该侧前后门，不改变旧乘员座位。
            int savedDoor = cabin.getInt("DoorCount") == SEATS ? i : side(i);
            doors[i] = cabin.getBoolean("Open" + savedDoor);
            doorChanged[i] = cabin.contains("Changed" + savedDoor) ? cabin.getLong("Changed" + savedDoor) : -DOOR_TICKS;
        }
    }

    /** 世界重载不恢复半完成乘降，门恢复最终位置；保留座位 UUID 等待原版乘员 NBT 恢复。 */
    public void resetAnimations() {
        for (int seat = 0; seat < SEATS; seat++) if (motions[seat] != SEATED) doors[seat] = false;
        Arrays.fill(motions, SEATED); Arrays.fill(outside, null);
        Arrays.fill(ropeExits, false);
        Arrays.fill(pullers, null);
        Arrays.fill(doorChanged, heli.getWorld().getTime() - DOOR_TICKS);
    }

    /** 服务端动作栏反馈；net.minecraft.text.Text 使用两种语言资源。 */
    private static void tell(PlayerEntity player, String key) {
        player.sendMessage(Text.translatable("message.no_24_heli." + key), true);
    }
}
