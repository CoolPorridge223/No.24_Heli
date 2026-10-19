/** 文件：落地伞的十二根柔性吊绳；端侧：COMMON，服务端物理、客户端快照插值。 */
package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.entity.ParachuteEntity;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;

/**
 * 职责：分段 Verlet 绳仅承受拉力，端点连接背包与含四角在内的十二个布面吊点，不产生推力。
 * 引用：net.minecraft.util.math.Vec3d 保存世界节点；ParachuteCanopy 共用体积碰撞。
 * 生命周期：结束伞降时建立；与布面交替求解，实体快照/NBT 一起保存并清理。
 * 线程：服务端主线程权威，客户端只插值；排查推车感查单向约束，直棍查是否绘制分段节点。
 */
public final class ParachuteSuspension {
    /** 左右各三组、每组前后两根，合计十二根；每根八段，总长 4.8 格。 */
    public static final int GROUPS_PER_SIDE = 3, LINES_PER_GROUP = 2, LINES = 12, SEGMENTS = 8;
    /** 旧版八根绳的布局没有四角吊点，读取后由服务端按现有布面重建。 */
    private static final int LEGACY_LINES = 8;
    private static final double LENGTH = 4.8, RADIUS = .025, CLOTH_MASS_WEIGHT = .45;
    private final Vec3d[] nodes = new Vec3d[LINES * (SEGMENTS + 1)];
    private final Vec3d[] previous = new Vec3d[nodes.length], frame = new Vec3d[nodes.length];
    /** 旧存档没有柔绳节点时，在服务端第一 tick 从当前吊点重建。 */
    private boolean ready;
    /** 本 tick 最大吊点水平拉力，仅用于接地布料受压起褶，不在松绳时凭空注入能量。 */
    private double peakPull;

    /** 每侧三个对称跨翼位置，最外组位于翼尖，内组避开中心重复吊点。 */
    public static int attachmentX(int line) {
        int group = line / LINES_PER_GROUP;
        int right = switch (Math.min(group, GROUPS_PER_SIDE * 2 - 1 - group)) {
            case 0 -> 0;
            case 1 -> ParachuteCanopy.SPAN / 4;
            default -> ParachuteCanopy.SPAN / 2 - 1;
        };
        return isLeft(line) ? ParachuteCanopy.SPAN - right : right;
    }
    /** 每组分别牵引前后翼缘，最外两组的四根绳正好拉住四个角。 */
    public static int attachmentZ(int line) { return line % LINES_PER_GROUP == 0 ? 0 : ParachuteCanopy.CHORD; }
    /** 与渲染共用侧别，每侧三组两根，不能按翼面中点再次分配。 */
    public static boolean isLeft(int line) { return line >= LINES / 2; }
    /** 以玩家脚位为原点的背包内部穿绳点，落在原版伞包模型的上半部。 */
    public static Vec3d packAnchor(boolean left) { return new Vec3d(left ? .14 : -.14, 1.26, -.23); }
    /** 背包两股绳随人物身体航向转动；harness 仍为脚位上方 1.25 格的世界/局部参考点。 */
    public static Vec3d anchor(Vec3d harness, int line, float yaw) {
        return harness.add(packAnchor(isLeft(line)).add(0, -1.25, 0).rotateY((float) -Math.toRadians(yaw)));
    }
    /** 每根绳的节点连续编号。 */
    private static int index(int line, int node) { return line * (SEGMENTS + 1) + node; }

    /** 按实际松量初始化下垂弧线，不能用不可弯曲的直杆代替绳。 */
    public void initialize(ParachuteEntity entity, Vec3d harness, ParachuteCanopy cloth) {
        for (int line = 0; line < LINES; line++) {
            Vec3d start = anchor(harness, line, entity.harnessYaw(1)), end = cloth.node(attachmentX(line), attachmentZ(line));
            double sag = Math.sqrt(Math.max(0, LENGTH * LENGTH - start.squaredDistanceTo(end))) * .35;
            for (int node = 0; node <= SEGMENTS; node++) {
                double t = node / (double) SEGMENTS; int i = index(line, node);
                Vec3d point = start.lerp(end, t).add(0, -Math.sin(t * Math.PI) * sag, 0);
                nodes[i] = previous[i] = frame[i] = ParachuteCanopy.surfacePoint(entity, point, RADIUS);
            }
        }
        ready = true;
    }

    /** 重力和阻尼使松绳落地；只固定两端，中间节点自由弯曲。 */
    public void beginTick(ParachuteEntity entity, Vec3d harness, ParachuteCanopy cloth) {
        if (!ready) initialize(entity, harness, cloth);
        peakPull = 0;
        for (int line = 0; line < LINES; line++) for (int node = 0; node <= SEGMENTS; node++) {
            int i = index(line, node); Vec3d current = nodes[i]; frame[i] = current;
            if (node == 0) nodes[i] = anchor(harness, line, entity.harnessYaw(1));
            else if (node == SEGMENTS) nodes[i] = cloth.node(attachmentX(line), attachmentZ(line));
            else nodes[i] = current.add(current.subtract(previous[i]).multiply(.94)).add(0, -.035, 0);
            previous[i] = current;
        }
    }

    /**
     * 长度不足时完全不施力；超长才把拉力沿相邻节点传递，最后一段仅拉对应布面吊点。
     * 布面比绳节点更重，因此不会被一根绳瞬间拽到目标位置；其他布面点靠内部张力跟随。
     */
    public void solve(ParachuteEntity entity, Vec3d harness, ParachuteCanopy cloth) {
        for (int line = 0; line < LINES; line++) {
            nodes[index(line, 0)] = anchor(harness, line, entity.harnessYaw(1));
            nodes[index(line, SEGMENTS)] = cloth.node(attachmentX(line), attachmentZ(line));
            for (int node = 0; node < SEGMENTS; node++) {
                int a = index(line, node), b = a + 1;
                Vec3d offset = nodes[b].subtract(nodes[a]); double distance = offset.length();
                if (distance <= LENGTH / SEGMENTS) continue;
                double wa = node == 0 ? 0 : 1, wb = node + 1 == SEGMENTS ? CLOTH_MASS_WEIGHT : 1;
                Vec3d correction = offset.multiply((distance - LENGTH / SEGMENTS) / distance * .85 / (wa + wb));
                nodes[a] = nodes[a].add(correction.multiply(wa));
                Vec3d pull = correction.multiply(-wb); nodes[b] = nodes[b].add(pull);
                if (node + 1 == SEGMENTS) {
                    cloth.pull(attachmentX(line), attachmentZ(line), pull);
                    peakPull = Math.max(peakPull, pull.horizontalLength());
                }
            }
            for (int node = 1; node < SEGMENTS; node++) {
                int i = index(line, node);
                nodes[i] = ParachuteCanopy.movePoint(entity, frame[i], nodes[i], true, RADIUS);
            }
        }
    }

    /** 小于六毫米的静态数值扰动不触发拖拽起褶，真正拉紧的绳才驱动薄布屈曲。 */
    public boolean isPulling() { return peakPull > .006; }

    /** 接地摩擦一次结算，绳长松弛不能把人靠近的位移作为推力传给伞。 */
    public void finishTick(ParachuteEntity entity, ParachuteCanopy cloth) {
        for (int line = 0; line < LINES; line++) {
            nodes[index(line, SEGMENTS)] = cloth.node(attachmentX(line), attachmentZ(line));
            for (int node = 1; node < SEGMENTS; node++) {
                int i = index(line, node); Vec3d velocity = nodes[i].subtract(frame[i]);
                Vec3d floor = ParachuteCanopy.surfacePoint(entity, nodes[i].add(0, -.02, 0), RADIUS);
                if (floor.y > nodes[i].y - .019) velocity = velocity.multiply(.88, 0, .88);
                if (velocity.lengthSquared() > 4) velocity = velocity.normalize().multiply(2);
                previous[i] = nodes[i].subtract(velocity);
            }
        }
    }

    /** 玩家大距离传送时与布面一起平移，不能留下横跨世界的拉索。 */
    public void translate(Vec3d shift) {
        if (!ready) return;
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = nodes[i].add(shift); previous[i] = previous[i].add(shift); frame[i] = frame[i].add(shift);
        }
    }

    /** 绳节点世界位置插值，由实体减去渲染原点；端点和绳中段使用同一物理快照。 */
    public Vec3d point(int line, int node, float delta) {
        if (!ready) return Vec3d.ZERO;
        int i = index(line, node); return frame[i].lerp(nodes[i], delta);
    }

    /** 老存档的首次快照可能尚未重建柔绳，显示端应等完整节点到达再绘制。 */
    public boolean hasNodes() { return ready; }

    /** 与原有布面快照一起发送，存档额外保留上一位置以恢复 Verlet 速度。 */
    public void write(NbtCompound nbt, boolean saveVelocity) {
        if (!ready) return;
        nbt.putLongArray("Lines", encode(nodes));
        if (saveVelocity) nbt.putLongArray("LinePrevious", encode(previous));
    }

    /** NBT/网络边界校验有限坐标和完整节点数；新快照从与布面一致的显示位置继续过渡。 */
    public void read(NbtCompound nbt, float blend) {
        long[] data = nbt.getLongArray("Lines"), old = nbt.getLongArray("LinePrevious");
        if (data.length == LEGACY_LINES * (SEGMENTS + 1) * 3) { ready = false; return; }
        if (data.length != nodes.length * 3) return;
        for (long value : data) if (!Double.isFinite(Double.longBitsToDouble(value))) return;
        for (int i = 0; i < nodes.length; i++) {
            Vec3d point = decode(data, i); frame[i] = ready ? frame[i].lerp(nodes[i], blend) : point; nodes[i] = point;
            previous[i] = old.length == data.length ? decode(old, i) : point;
            if (!Double.isFinite(previous[i].lengthSquared())) previous[i] = point;
        }
        ready = true;
    }

    /** 扁平位模式编码不损失世界坐标精度。 */
    private static long[] encode(Vec3d[] values) {
        long[] data = new long[values.length * 3];
        for (int i = 0; i < values.length; i++) {
            data[i * 3] = Double.doubleToLongBits(values[i].x); data[i * 3 + 1] = Double.doubleToLongBits(values[i].y);
            data[i * 3 + 2] = Double.doubleToLongBits(values[i].z);
        }
        return data;
    }
    /** 调用者已经校验长度，按 x/y/z 顺序解码。 */
    private static Vec3d decode(long[] data, int i) {
        return new Vec3d(Double.longBitsToDouble(data[i * 3]), Double.longBitsToDouble(data[i * 3 + 1]),
                Double.longBitsToDouble(data[i * 3 + 2]));
    }
}
