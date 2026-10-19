/** 文件：四座舱位、四扇铰接门及乘降时序；端侧：COMMON；随 helicopter 的 Flight 快照同步。 */
package org.DJB.no_24_heli.entity;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：固定座号与乘客列表分离，服务端裁决同侧乘降，客户端只读同一动画时间线。
 * 引用 net.minecraft.entity.Entity#startRiding 完成真实乘骑，NbtCompound 保存 UUID 座位及门状态。
 * 生命周期：每架飞机一个实例，世界主线程 tick；渲染线程仅读取插值坐标。
 * 排查：错座查 UUID 快照；门打不开查距离/侧别；动画跳变查世界 tick 和同步时间。
 */
public final class HelicopterCabin {
    /** 0 左前主驾、1 右前副驾、2 左后、3 右后；后排距前排 0.90 格。 */
    public static final int SEATS = 4;
    public static final double ROW_SPACING = .90;
    /** 沿用原机身的两块侧窗；中柱宽 0.07 格，前后门均以前缘为轴向外打开 78 度。 */
    public static final double DOOR_BACK = -1.15, REAR_DOOR_FRONT = -.38,
            FRONT_DOOR_BACK = -.31, DOOR_FRONT = .50, DOOR_ANGLE = 78;
    public static final int DOOR_TICKS = 10, BOARD_TICKS = 24, EXIT_TICKS = 20, JUMP_TICKS = 12;
    /** 动作编号：静坐、迈入、地面走下、空中跃出。 */
    public static final int SEATED = 0, BOARD = 1, EXIT = 2, JUMP = 3;
    /** 权威座位身份；动作在保持真实乘骑期间完成，结束离座才释放席位。 */
    private final UUID[] occupants = new UUID[SEATS];
    private final int[] motions = new int[SEATS];
    private final long[] started = new long[SEATS];
    private final Vec3d[] outside = new Vec3d[SEATS];
    /** Y 下绳先等待本座门打开，服务端再次检查悬停后转移；不跨存档恢复请求。 */
    private final boolean[] ropeExits = new boolean[SEATS];
    /** 门号与座号一致，四门独立；世界总 tick 时间戳避免实体重追踪时重新播放。 */
    private final boolean[] doors = new boolean[SEATS];
    private final long[] doorChanged = {-DOOR_TICKS, -DOOR_TICKS, -DOOR_TICKS, -DOOR_TICKS};
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

    /** 座位脚位与现有前排保持一致；net.minecraft.util.math.Vec3d 用于模型和眼位共同基准。 */
    public static Vec3d seatPosition(int seat) {
        return new Vec3d(side(seat) == 0 ? .38 : -.38, .10,
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

    /** 舱内使用本座门，绳顶按后排优先选择空座门；普通舱外优先同侧前座对应门。 */
    public int accessDoor(Entity entity) {
        int seat = seat(entity);
        if (seat >= 0) return seat;
        if (entity.getVehicle() instanceof RopeSeatEntity rope && rope.helicopter() == heli) {
            int free = freeRopeSeat();
            return free >= 0 ? free : 1;
        }
        int side = accessSide(entity), free = freeSeat(side);
        return free >= 0 ? free : side;
    }

    /** 外部操作限定在侧门附近三格内，排除机鼻、长尾、旁观者和其他载具。 */
    public boolean canReach(PlayerEntity player) {
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

    /** 普通上机自动打开同侧对应门，前排优先；绳顶独立按后排优先分配，并复核顶部范围。 */
    public boolean board(PlayerEntity player) {
        if (!canReach(player) || heli.hasPassenger(player)) return false;
        if (player.getVehicle() instanceof RopeSeatEntity) return boardFromRope(player);
        int side = accessSide(player), seat = freeSeat(side);
        if (seat < 0) { tell(player, "side_full"); return false; }
        return mount(player, seat, true);
    }

    /** 绳顶 F/E 均后排优先，后排满后进入任意前排空座；自动打开对应门，坐稳后关门。 */
    public boolean boardFromRope(PlayerEntity player) {
        if (!canReach(player)) return false;
        int seat = freeRopeSeat();
        return seat >= 0 && mount(player, seat, true);
    }

    /** 权威端先预占座位，开门期间保持入口脚位；门完全打开才开始迈入，失败时撤销预占。 */
    public boolean mount(Entity entity, int seat, boolean animate) {
        if (heli.getWorld().isClient || heli.flight.wreck || seat < 0 || seat >= SEATS || occupants[seat] != null) return false;
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

    /** 原版存档恢复/命令挂载回调：已有 UUID 保持原座，新乘客占第一个空位；不挪动其他乘员。 */
    public void added(Entity entity) {
        if (heli.getWorld().isClient || seat(entity) >= 0) return;
        if(entity instanceof PlayerEntity){
            int seat=freeSeat(accessSide(entity));
            if(seat>=0)occupants[seat]=entity.getUuid();
        }else for (int i = 0; i < SEATS; i++) if (occupants[i] == null) { occupants[i] = entity.getUuid(); break; }
        heli.syncCabin();
    }

    /** 离座只在空中将仍坐稳的副驾玩家提升到主驾；后排、村民及正在退出的副驾永不接管。 */
    public void removed(Entity entity, boolean airborne) {
        if (heli.getWorld().isClient) return;
        int seat = seat(entity);
        if (seat < 0) return;
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
        int duration = motions[seat] == BOARD ? BOARD_TICKS : motions[seat] == JUMP ? JUMP_TICKS : EXIT_TICKS;
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
        double t = motions[seat] == BOARD ? p : 1 - p;
        Vec3d threshold = new Vec3d(side(seat) == 0 ? .90 : -.90, .38, seated.z);
        Vec3d point = t < .55 ? outside[seat].lerp(threshold, smooth(t / .55))
                : threshold.lerp(seated, smooth((t - .55) / .45));
        if (motions[seat] == JUMP) point = point.add(0, Math.sin(p * Math.PI) * .32, 0);
        return point;
    }

    /** 平滑步进函数；参数为归一化时间，避免端点速度突变。 */
    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    /** 确认离机后自动开门，等待门全开再起身；动画期间保留座位，目标受阻时保持坐稳。 */
    public void beginExit(PlayerEntity player, boolean airborne) {
        int seat = seat(player);
        if (seat < 0 || motions[seat] != SEATED) return;
        Vec3d target = new Vec3d(side(seat) == 0 ? 2.05 : -2.05, .10, seatPosition(seat).z);
        Vec3d world = toWorld(target);
        if (!heli.getWorld().isSpaceEmpty(player, player.getBoundingBox().offset(world.subtract(player.getPos())))) {
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
     * 服务端完成乘降；离机前复查移动后的落点，障碍出现则回到座位并明确提示。
     * net.minecraft.entity.Entity#stopRiding 最终解除乘骑，登机坐稳及地面离机后关门，空中跳出留门打开。
     */
    public void tick() {
        if (heli.getWorld().isClient) return;
        for (int seat = 0; seat < SEATS; seat++) {
            Entity entity = occupant(seat);
            // 原版不把离线玩家作为乘员实体保存；重载后没有实际乘员的 UUID 必须释放。
            if(entity==null){occupants[seat]=null;motions[seat]=SEATED;outside[seat]=null;ropeExits[seat]=false;continue;}
            if (ropeExits[seat] && canPass(seat) && entity instanceof PlayerEntity player) {
                ropeExits[seat] = false; heli.rappel(player);
                if (!heli.hasPassenger(entity)) continue;
            }
            if (motions[seat] == SEATED || progress(entity, 0) < 1) continue;
            int motion = motions[seat];
            if (motion == BOARD) { motions[seat] = SEATED; setDoor(seat, false); heli.syncCabin(); continue; }
            Vec3d target = exitPosition(entity);
            if (!heli.getWorld().isSpaceEmpty(entity, entity.getBoundingBox().offset(target.subtract(entity.getPos())))) {
                motions[seat] = SEATED; setDoor(seat, false);
                if (entity instanceof PlayerEntity player) tell(player, "door_exit_blocked");
                heli.syncCabin(); continue;
            }
            entity.stopRiding(); entity.refreshPositionAfterTeleport(target);
            if (heli.isCabinGrounded()) { setDoor(seat, false); heli.syncCabin(); }
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
        Arrays.fill(occupants, null); Arrays.fill(motions, SEATED); Arrays.fill(outside, null);
        for (int i = 0; i < SEATS; i++) {
            NbtCompound seat = cabin.getCompound("Seat" + i);
            if (!seat.containsUuid("Occupant")) continue;
            UUID id = seat.getUuid("Occupant");
            if (Arrays.asList(occupants).contains(id)) { No_24_heli.LOGGER.warn("丢弃重复的直升机座位 UUID：{}", id); continue; }
            occupants[i] = id; started[i] = seat.getLong("Started");
            int motion = seat.getInt("Motion");
            double x = seat.getDouble("X"), y = seat.getDouble("Y"), z = seat.getDouble("Z");
            if (motion < SEATED || motion > JUMP || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 3 || Math.abs(y) > 3 || Math.abs(z) > 2.5) {
                No_24_heli.LOGGER.warn("丢弃非法的直升机乘降动作：{}", id); continue;
            }
            motions[i] = motion; outside[i] = new Vec3d(x, y, z);
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
        Arrays.fill(doorChanged, heli.getWorld().getTime() - DOOR_TICKS);
    }

    /** 服务端动作栏反馈；net.minecraft.text.Text 使用两种语言资源。 */
    private static void tell(PlayerEntity player, String key) {
        player.sendMessage(Text.translatable("message.no_24_heli." + key), true);
    }
}
