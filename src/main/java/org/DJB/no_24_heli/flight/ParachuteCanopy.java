/** 文件：落地拖行与脱落伞面的布料积分；端侧：COMMON，服务端权威。 */
package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.entity.ParachuteEntity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;

/**
 * 职责：9×5 世界节点通过 Verlet、结构/剪切/弯曲约束和体积碰撞形成可摊开的失压布面。
 * 引用：net.minecraft.entity.Entity#adjustMovementForCollisions 对节点体积做原版方块碰撞。
 * 生命周期：伞降结束时从翼面姿态初始化，实体 tick 积分并同步，取下伞包后随实体释放。
 * 线程：服务端主线程；客户端只接收节点快照并插值，不回写物理。
 * 排查：地面滑行查碰撞摩擦，拖行查吊绳长度，脱落飘走查独立气阻与寿命。
 */
public final class ParachuteCanopy {
    /** 四十五个物理节点；视觉布面在节点间插值为原有 25×9 网格。 */
    public static final int SPAN = 8, CHORD = 4;
    /** 活翼中心距脚 5.2 格，翼尖下弯 1.6 格；失压后不再向整张布施加位置目标。 */
    public static final double HEIGHT = 5.2, ARCH = 1.6;
    private static final double HALF_SPAN = 3.7, LENGTH = 2.4;
    /** 节点碰撞半径 9cm，允许布料沿被牵引方向越过一格台阶和 1.5 格栅栏。 */
    private static final double NODE_RADIUS = .09, STEP_HEIGHT = 1.6, CONTACT_MARGIN = .002;
    /** 六遍交替求解柔绳与布内张力；软弯曲允许吊点先移动，其余布面滞后并起褶。 */
    private static final int CONSTRAINT_PASSES = 6;
    /** 世界位置、Verlet 上一步及显示/碰撞的本 tick 起点，三个数组不能互相覆盖。 */
    private final Vec3d[] points = new Vec3d[(SPAN + 1) * (CHORD + 1)];
    private final Vec3d[] previous = new Vec3d[points.length], frame = new Vec3d[points.length];
    /** 上一步接地标志与拖拽后的四 tick 惯性窗口，屈曲只能消耗局部拖拽功，不能静态自激。 */
    private final boolean[] grounded = new boolean[points.length];
    private int draggingTicks;
    /** 只在有效快照/初始化后采样；玩家跨距离传送时平移整块布料。 */
    private boolean ready;
    private Vec3d lastHarness;
    /** 分段吊绳是唯一来自玩家的牵引路径，绳松时不推伞，也不强制翼面朝向。 */
    private final ParachuteSuspension suspension = new ParachuteSuspension();

    /** 跨翼优先编号，与网络快照和约束统一。 */
    private static int index(int x, int z) { return x * (CHORD + 1) + z; }

    /** 载人阶段的局部伞型，供客户端充气布面及服务端失压初态共用。 */
    public static Vec3d localPoint(double u, double v, double opening, double ticks) {
        double lines = Math.clamp(ticks / (ParachutePhysics.LINE_TICKS + 6), 0, 1);
        return new Vec3d(u * HALF_SPAN * (.055 + .945 * opening),
                1.4 + (HEIGHT - 1.4) * lines - ARCH * u * u * opening + .23 * Math.sin(v * Math.PI) * opening,
                ((v - .5) * LENGTH - .4 * u * u) * (.12 + .88 * opening));
    }

    /** 与客户端 Y(-yaw)·Z(bank)·X(pitch) 矩阵相同，保留结束瞬间的伞面位置及惯性。 */
    public void initialize(ParachuteEntity entity, boolean detached) {
        double opening = entity.inflation(1), ticks = entity.openingTicks(1);
        double yaw = Math.toRadians(entity.getYaw()); Vec3d heading = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            Vec3d local = localPoint(x * 2.0 / SPAN - 1, z / (double) CHORD, opening, ticks);
            Vec3d posed = local.add(0, -1.25, 0)
                    .rotateX((float) -Math.toRadians(entity.canopyPitch(1)))
                    .rotateZ((float) -Math.toRadians(entity.bank(1))).add(0, 1.25, 0)
                    .rotateY((float) -Math.toRadians(entity.getYaw()));
            int i = index(x, z);
            points[i] = frame[i] = moveNode(entity, entity.getPos().add(0, 1.25, 0),
                    entity.getPos().add(posed), false);
            Vec3d inertia = entity.getVelocity().multiply(detached ? .25 : .3);
            if (detached) inertia = inertia.add(0, .18, 0);
            else inertia = inertia.subtract(heading.multiply(.28)); // 失压塌落的初始气流惯性，只在释放时施加。
            previous[i] = points[i].subtract(inertia);
        }
        ready = true;
        if (!detached) suspension.initialize(entity, entity.getPos().add(0, 1.25, 0), this);
    }

    /**
     * 只给布料重力、惯性和小幅风扰，玩家的力必须经柔绳传到对应吊点，再由布内张力传播。
     * 没有整翼目标位置或跟随朝向，玩家向伞靠近时只能让绳松下来，不能把伞推开。
     */
    public void tick(ParachuteEntity entity, Vec3d harness, boolean detached) {
        if (!ready) initialize(entity, detached);
        if (harness != null && lastHarness != null && harness.squaredDistanceTo(lastHarness) > 32 * 32) {
            Vec3d shift = harness.subtract(lastHarness);
            for (int i = 0; i < points.length; i++) {
                points[i] = points[i].add(shift); previous[i] = previous[i].add(shift);
            }
            suspension.translate(shift);
        }
        if (harness != null && lastHarness != null
                && harness.subtract(lastHarness).horizontalLengthSquared() > .0004) draggingTicks = 4;
        else draggingTicks = Math.max(0, draggingTicks - 1);
        lastHarness = harness;
        double time = entity.getWorld().getTime();
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            int i = index(x, z);
            frame[i] = points[i]; Vec3d current = points[i];
            Vec3d air = new Vec3d(Math.sin(time * .033 + i * .1) * .0025,
                    detached ? -.006 + Math.sin(time * .09 + i) * .002 : -.035,
                    Math.cos(time * .027 + i * .13) * .0025);
            points[i] = current.add(current.subtract(previous[i]).multiply(detached ? .965 : .98)).add(air);
            previous[i] = current;
        }
        if (harness != null) suspension.beginTick(entity, harness, this);
        for (int pass = 0; pass < CONSTRAINT_PASSES; pass++) {
            if (harness != null) suspension.solve(entity, harness, this);
            for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
                if (x < SPAN) constrain(index(x, z), index(x + 1, z), 2 * HALF_SPAN / SPAN, .65);
                if (z < CHORD) constrain(index(x, z), index(x, z + 1), LENGTH / CHORD, .65);
                if (x < SPAN && z < CHORD) {
                    double diagonal = Math.hypot(2 * HALF_SPAN / SPAN, LENGTH / CHORD);
                    constrain(index(x, z), index(x + 1, z + 1), diagonal, .3);
                    constrain(index(x + 1, z), index(x, z + 1), diagonal, .3);
                }
                if (x + 2 <= SPAN) {
                    constrain(index(x, z), index(x + 2, z), 4 * HALF_SPAN / SPAN, .025);
                    buckle(index(x, z), index(x + 1, z), index(x + 2, z), 2 * HALF_SPAN / SPAN);
                }
                if (z + 2 <= CHORD) {
                    constrain(index(x, z), index(x, z + 2), 2 * LENGTH / CHORD, .025);
                    buckle(index(x, z), index(x, z + 1), index(x, z + 2), LENGTH / CHORD);
                }
            }
            for (int i = 0; i < points.length; i++)
                points[i] = moveNode(entity, frame[i], points[i], !detached);
        }
        dampContacts(entity);
        if (harness != null) suspension.finishTick(entity, this);
    }

    /** 布料抵抗拉伸和过量压缩，软弯曲约束容许折叠，不能再把所有节点压成同一点。 */
    private void constrain(int a, int b, double rest, double stiffness) {
        Vec3d offset = points[b].subtract(points[a]); double length = offset.length();
        if (length < .00001) return;
        double strength = length < rest ? stiffness * .12 : stiffness;
        Vec3d correction = offset.multiply(Math.clamp((length - rest) * .5 * strength, -.25, .25) / length);
        points[a] = points[a].add(correction); points[b] = points[b].subtract(correction);
    }

    /**
     * 三节点薄布受压会向面外屈曲，不能只在水平面缩短成一张纸。端点距离压缩超过 4%
     * 才按两段原长计算中点目标褶高，目标最高 0.55 格；不使用随时间播放的假扭曲动画。
     * 接触约束随后处理地面，竖直/斜面边则沿与端点连线垂直的向上方向起褶。
     */
    private void buckle(int a, int b, int c, double rest) {
        if (draggingTicks == 0 || !grounded[b] || !suspension.isPulling()
                || points[b].y - frame[b].y > .14) return;
        Vec3d offset = points[c].subtract(points[a]); double half = offset.length() * .5;
        if (half >= rest * .96 || half < .00001) return;
        Vec3d axis = offset.normalize(), normal = new Vec3d(0, 1, 0).subtract(axis.multiply(axis.y));
        if (normal.lengthSquared() < .01) return;
        normal = normal.normalize();
        double target = Math.min(.55, Math.sqrt(rest * rest - half * half));
        double height = points[b].subtract(points[a].lerp(points[c], .5)).dotProduct(normal);
        if (height < target) points[b] = points[b].add(normal.multiply(Math.min(.02, (target - height) * .12)));
    }

    /** 绳求解器取得单个布面吊点，不能直接操作整翼中心。 */
    public Vec3d node(int x, int z) { return points[index(x, z)]; }
    /** 仅单个吊点接受拉绳投影，其他布面节点下一遍通过内部张力响应。 */
    public void pull(int x, int z, Vec3d correction) { int i = index(x, z); points[i] = points[i].add(correction); }
    /** 已同步柔绳的世界节点，供客户端按原有两 tick 快照节奏插值。 */
    public Vec3d linePoint(int line, int node, float delta) { return suspension.point(line, node, delta); }
    /** 无柔绳字段的旧存档在重建完成前不提交绳网，避免未初始化坐标连向世界原点。 */
    public boolean hasLines() { return suspension.hasNodes(); }

    /** 接地摩擦每 tick 只结算一次，防止每遍约束重复制动导致拖不动。 */
    private void dampContacts(ParachuteEntity entity) {
        for (int i = 0; i < points.length; i++) {
            Vec3d velocity = points[i].subtract(frame[i]);
            Vec3d downward = adjust(entity, points[i], new Vec3d(0, -.025, 0), NODE_RADIUS);
            grounded[i] = downward.y > -.024;
            if (grounded[i]) velocity = velocity.multiply(.82, 0, .82);
            if (velocity.lengthSquared() > 4) velocity = velocity.normalize().multiply(2);
            previous[i] = points[i].subtract(velocity);
        }
    }

    /**
     * 半径九厘米的节点使用原版扫掠 AABB，沿墙保留切向运动，薄块/台阶也不能漏碰。
     * 水平被阻挡时尝试先抬升、平移、下落，只有前进更多才选择越障结果。
     */
    private static Vec3d moveNode(ParachuteEntity entity, Vec3d start, Vec3d target, boolean allowStep) {
        return movePoint(entity, start, target, allowStep, NODE_RADIUS);
    }

    /** 布料和细绳复用同一接触算法，半径分别为九厘米和 2.5 厘米。 */
    public static Vec3d movePoint(ParachuteEntity entity, Vec3d start, Vec3d target, boolean allowStep, double radius) {
        Vec3d safe = surfacePoint(entity, start, radius), wanted = target.subtract(start);
        Vec3d moved = adjust(entity, safe, wanted, radius);
        double wantedHorizontal = wanted.horizontalLengthSquared();
        if (allowStep && Math.abs(wanted.y) < .8 && moved.horizontalLengthSquared() + .000001 < wantedHorizontal) {
            Vec3d lift = adjust(entity, safe, new Vec3d(0, STEP_HEIGHT, 0), radius);
            Vec3d over = adjust(entity, safe.add(lift), new Vec3d(wanted.x, 0, wanted.z), radius);
            if (over.horizontalLengthSquared() > moved.horizontalLengthSquared() + .000001) {
                Vec3d down = adjust(entity, safe.add(lift).add(over), new Vec3d(0, wanted.y - lift.y, 0), radius);
                moved = lift.add(over).add(down);
            }
        }
        return surfacePoint(entity, safe.add(moved), radius);
    }

    /** 原版方块碰撞算法支持任意 VoxelShape；空实体列表表示布面不推挤玩家/飞机。 */
    private static Vec3d adjust(ParachuteEntity entity, Vec3d point, Vec3d motion, double radius) {
        return Entity.adjustMovementForCollisions(entity, motion, nodeBox(point, radius), entity.getWorld(), List.of());
    }

    /** 球形节点的保守 AABB，底层在地表上至少保留九厘米的安全高度。 */
    private static Box nodeBox(Vec3d point, double radius) {
        return new Box(point.x - radius, point.y - radius, point.z - radius,
                point.x + radius, point.y + radius, point.z + radius);
    }

    /** 检查真正的方块形状，不让其他乘员或玩家本身阻碍布料。 */
    private static boolean isFree(ParachuteEntity entity, Vec3d point, double radius) {
        return !entity.getWorld().getBlockCollisions(entity, nodeBox(point, radius)).iterator().hasNext();
    }

    /**
     * 处理旧存档穿地、方块放进伞面和约束产生的初始重叠。优先最短自由面，
     * 候选仍堵住时向上寻找裸露表面，避免初始节点已在实体方块内而永久卡住。
     */
    public static Vec3d surfacePoint(ParachuteEntity entity, Vec3d point) {
        return surfacePoint(entity, point, NODE_RADIUS);
    }

    /** 用调用者指定的节点半径恢复地表，客户端也只读此算法投影细分显示节点。 */
    public static Vec3d surfacePoint(ParachuteEntity entity, Vec3d point, double radius) {
        if (isFree(entity, point, radius)) return point;
        Vec3d best = null; double distance = Double.POSITIVE_INFINITY;
        for (var shape : entity.getWorld().getBlockCollisions(entity, nodeBox(point, radius))) for (Box box : shape.getBoundingBoxes()) {
            Vec3d[] candidates = {
                    new Vec3d(point.x, box.maxY + radius + CONTACT_MARGIN, point.z),
                    new Vec3d(box.minX - radius - CONTACT_MARGIN, point.y, point.z),
                    new Vec3d(box.maxX + radius + CONTACT_MARGIN, point.y, point.z),
                    new Vec3d(point.x, point.y, box.minZ - radius - CONTACT_MARGIN),
                    new Vec3d(point.x, point.y, box.maxZ + radius + CONTACT_MARGIN)};
            for (Vec3d candidate : candidates) {
                double squared = candidate.squaredDistanceTo(point);
                if (squared < distance && isFree(entity, candidate, radius)) { best = candidate; distance = squared; }
            }
        }
        if (best != null) return best;
        int ceiling = entity.getWorld().getTopY() + 1;
        for (double y = point.y + .25; y <= ceiling; y += .5) {
            Vec3d above = new Vec3d(point.x, y, point.z);
            if (isFree(entity, above, radius)) return above;
        }
        return new Vec3d(point.x, ceiling + radius, point.z);
    }

    /** 网络和存档以 double 的 long 位模式保留精度；上一帧速度仅存档需要。 */
    public NbtCompound snapshot(boolean saveVelocity) {
        NbtCompound nbt = new NbtCompound();
        if (ready) {
            nbt.putLongArray("Points", encode(points));
            if (saveVelocity) nbt.putLongArray("Previous", encode(previous));
            suspension.write(nbt, saveVelocity);
        }
        return nbt;
    }

    /** 客户端快照以旧点作为插值起点；存档则恢复 Verlet 速度，拒绝非有限/错误长度数组。 */
    public void read(NbtCompound nbt) {
        long[] values = nbt.getLongArray("Points"), velocity = nbt.getLongArray("Previous");
        if (values.length != points.length * 3) return;
        for (long value : values) if (!Double.isFinite(Double.longBitsToDouble(value))) return;
        for (int i = 0; i < points.length; i++) {
            Vec3d next = decode(values, i);
            frame[i] = ready ? point(i / (CHORD + 1), i % (CHORD + 1), 1) : next;
            points[i] = next;
            previous[i] = velocity.length == values.length ? decode(velocity, i) : next;
            if (!Double.isFinite(previous[i].lengthSquared())) previous[i] = next;
        }
        ready = true;
        suspension.read(nbt);
    }

    /** 双线性采样任意视觉节点；输入使用物理网格单位，帧插值只影响显示。 */
    public Vec3d point(double x, double z, float delta) {
        if (!ready) return Vec3d.ZERO;
        int ix = Math.min(SPAN - 1, (int) x), iz = Math.min(CHORD - 1, (int) z);
        double fx = x - ix, fz = z - iz;
        return interpolate(index(ix, iz), delta).lerp(interpolate(index(ix + 1, iz), delta), fx)
                .lerp(interpolate(index(ix, iz + 1), delta)
                        .lerp(interpolate(index(ix + 1, iz + 1), delta), fx), fz);
    }

    /** 相邻同步快照之间的线性插值，服务端调用 delta=1 直接取得权威节点。 */
    private Vec3d interpolate(int i, float delta) { return frame[i].lerp(points[i], delta); }

    /** 跟踪中心使用中间节点，保证远处飘走后仍按实际位置发送和裁剪。 */
    public Vec3d center() { return points[index(SPAN / 2, CHORD / 2)]; }

    /** 扁平编码避免每点创建多个 NBT 标签。 */
    private static long[] encode(Vec3d[] nodes) {
        long[] result = new long[nodes.length * 3];
        for (int i = 0; i < nodes.length; i++) {
            result[i * 3] = Double.doubleToLongBits(nodes[i].x);
            result[i * 3 + 1] = Double.doubleToLongBits(nodes[i].y);
            result[i * 3 + 2] = Double.doubleToLongBits(nodes[i].z);
        }
        return result;
    }

    /** 与快照编码一致，调用方先验证长度和有限值。 */
    private static Vec3d decode(long[] values, int i) {
        return new Vec3d(Double.longBitsToDouble(values[i * 3]), Double.longBitsToDouble(values[i * 3 + 1]),
                Double.longBitsToDouble(values[i * 3 + 2]));
    }
}
