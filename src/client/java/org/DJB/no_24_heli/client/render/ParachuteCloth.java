/** 文件：滑翔翼表面张力与阻尼布面；端侧：CLIENT，仅视觉网格，不计算玩家气动力。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.entity.ParachuteEntity;
import org.DJB.no_24_heli.flight.ParachuteCanopy;


import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

/**
 * 职责：49×17 节点布面，活翼用 Verlet 保持冲压翼弧形，失压翼采样连续弯曲曲面。
 * 引用：net.minecraft.util.math.Vec3d 保存局部位置；ParachuteEntity 的同步充气和速度驱动表现。
 * 生命周期：每具可见伞独立实例，渲染器每个客户端 tick 最多积分一次，帧间插值。
 * 端侧/线程：仅客户端渲染线程；不修改实体或服务端世界。
 * 排查：过度抖动查阻尼和约束遍数，翼面扭曲查 restPoint；节点不能跨玩家共享。
 */
@Environment(EnvType.CLIENT)
final class ParachuteCloth {
    /** 跨翼四十八段、弦向十六段；气室仍为十二个，显示面数增为原来的四倍。 */
    static final int SPAN = 48, CHORD = 16;
    /** 高阻尼保留小幅布面起伏；约束三遍控制结构和剪切拉伸，后缘响应更柔和。 */
    private static final double DAMPING = .78;
    private final Vec3d[] nodes = new Vec3d[(SPAN + 1) * (CHORD + 1)];
    private final Vec3d[] previous = new Vec3d[nodes.length], frame = new Vec3d[nodes.length];
    private final Vec3d[] rest = new Vec3d[nodes.length];
    /** 同帧共享的中心面、上下表面和法线；渲染与吊绳直接复用，避免逐面反复插值和叉乘。 */
    private final Vec3d[] rendered = new Vec3d[nodes.length];
    private final Vec3d[] upper = new Vec3d[nodes.length], lower = new Vec3d[nodes.length];
    private final Vec3d[] upperNormals = new Vec3d[nodes.length], lowerNormals = new Vec3d[nodes.length];
    /** 上次可见 tick、充气和世界速度，用于帧率无关积分及转弯惯性形变。 */
    private int lastAge = -1;
    private Vec3d lastVelocity = Vec3d.ZERO;
    /** 失压网格每帧先做细分节点的地形接触投影，不能让插值面切进台阶或地板。 */
    private boolean loose;

    /** 节点以弦向连续编号，供网格和吊绳共同引用。 */
    private static int index(int x, int z) { return x * (CHORD + 1) + z; }

    /**
     * 目标冲压翼弧：先拉直伞绳，再由中心向翼端张开；伞翼具有弯曲前缘、气室鼓包和后掠。
     * 无 MC 世界 API，返回以乘员脚底为原点的局部格坐标，+Z 是伞前方。
     */
    private static Vec3d restPoint(int x, int z, double opening, double ticks) {
        double u = x * 2.0 / SPAN - 1, v = z / (double) CHORD;
        double cell = .045 * Math.sin(x * Math.PI * 12 / SPAN) * Math.sin(v * Math.PI) * opening;
        return ParachuteCanopy.localPoint(u, v, opening, ticks)
                .add(0, cell + (1 - opening) * .15 * Math.cos(x * 24.0 / SPAN * 1.7 + v * 8), 0);
    }

    /**
     * 每 tick 以惯性、气流和充气压力积分；结构和对角约束限制伸长，不能只画正弦动画冒充布面。
     * net.minecraft.entity.Entity#getVelocity 提供同步气流变化，跨越不可见时段重置以免数值爆炸。
     */
    private void update(ParachuteEntity canopy) {
        loose = false;
        if (lastAge == canopy.age) return;
        double opening = canopy.inflation(1), ticks = canopy.openingTicks(1);
        Vec3d velocity = canopy.getVelocity();
        boolean reset = lastAge < 0 || canopy.age - lastAge > 4;
        double yaw = Math.toRadians(canopy.getYaw());
        Vec3d acceleration = velocity.subtract(lastVelocity);
        double sway = Math.clamp(-(acceleration.x * Math.cos(yaw) + acceleration.z * Math.sin(yaw)) * .8, -.06, .06);
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            int i = index(x, z); rest[i] = restPoint(x, z, opening, ticks);
            if (reset) { nodes[i] = previous[i] = frame[i] = rest[i]; continue; }
            Vec3d current = nodes[i]; frame[i] = current;
            // 领先边受压较强，后缘较柔软；风扰动仅厘米量级，主体仍由张力约束决定。
            double pressure = z == CHORD ? .48 : .20;
            double flutter = Math.sin(canopy.age * .47 + x * 24.0 / SPAN * .83 + z * 8.0 / CHORD * .62)
                    * (.004 + .006 * (1 - opening)) * (1 - z / (double) (CHORD + 1));
            nodes[i] = current.add(current.subtract(previous[i]).multiply(DAMPING))
                    .add(rest[i].subtract(current).multiply(pressure)).add(sway, flutter, 0);
            previous[i] = current;
        }
        for (int pass = 0; pass < 3; pass++) for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            if (x < SPAN) constrain(index(x, z), index(x + 1, z));
            if (z < CHORD) constrain(index(x, z), index(x, z + 1));
            if (x < SPAN && z < CHORD) {
                constrain(index(x, z), index(x + 1, z + 1));
                constrain(index(x + 1, z), index(x, z + 1));
            }
        }
        lastAge = canopy.age; lastVelocity = velocity;
    }

    /**
     * 九厘米底面安全距离与服务端共用。只读客户端方块形状，投影显示顶点，
     * 不改变服务端物理；细分点也参与接触，避免两个安全粗节点之间的面穿过台阶。
     */
    private void prepareLoose(ParachuteEntity canopy, float delta) {
        loose = true; Vec3d origin = canopy.getLerpedPos(delta);
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            Vec3d world = canopy.loosePoint(x / (double) SPAN, z / (double) CHORD, delta).add(origin);
            nodes[index(x, z)] = ParachuteCanopy.surfacePoint(canopy, world).subtract(origin);
        }
    }

    /**
     * 同帧生成真实厚度的上下表面，再分别计算共享法线；吊绳仍取未加厚的物理吊点。
     * 引用 net.minecraft.util.math.Vec3d 插值位置；仅客户端渲染线程，不回写权威节点。
     */
    void prepare(ParachuteEntity canopy, float delta) {
        if (canopy.mode() != ParachuteEntity.ACTIVE) prepareLoose(canopy, delta); else update(canopy);
        double opening = loose ? 0 : canopy.inflation(delta);
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            int i = index(x, z);
            Vec3d point = loose ? nodes[i] : frame[i].lerp(nodes[i], delta);
            double profile = Math.sin(z / (double) CHORD * Math.PI);
            double thickness = loose ? .09 + .11 * profile : .04 + .32 * opening * profile;
            rendered[i] = point;
            upper[i] = loose ? point.add(0, thickness, 0) : point;
            lower[i] = loose ? point : point.add(0, -thickness, 0);
        }
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            int i = index(x, z);
            upperNormals[i] = surfaceNormal(upper, x, z, 1);
            lowerNormals[i] = surfaceNormal(lower, x, z, -1);
        }
    }

    /** 两节点的结构/剪切弹簧约束，休止长度随真实充气形状改变；无世界访问或权威写入。 */
    private void constrain(int a, int b) {
        Vec3d offset = nodes[b].subtract(nodes[a]); double distance = offset.length();
        if (distance < .000001) return;
        Vec3d correction = offset.multiply((distance - rest[a].distanceTo(rest[b])) / distance * .42);
        nodes[a] = nodes[a].add(correction); nodes[b] = nodes[b].subtract(correction);
    }

    /** 当前帧未加厚的节点供气室和吊绳共用，保持与服务端吊点一致。 */
    Vec3d point(int x, int z) { return rendered[index(x, z)]; }

    /** 上下层使用真实顶点位置；失压布面只向上加厚，不把底层画进地面。 */
    Vec3d surfacePoint(int x, int z, boolean underside) { return (underside ? lower : upper)[index(x, z)]; }

    /** 上下层分别缓存外向法线，厚度沿弦长变化时不能简单取中心面法线的正负值。 */
    Vec3d normal(int x, int z, boolean underside) { return (underside ? lowerNormals : upperNormals)[index(x, z)]; }

    /** 相邻细分点共享切线；直接除以长度，避免 net.minecraft.util.math.Vec3d#normalize 清零微小叉积。 */
    private static Vec3d surfaceNormal(Vec3d[] surface, int x, int z, double sign) {
        Vec3d span = surface[index(Math.min(SPAN, x + 1), z)].subtract(surface[index(Math.max(0, x - 1), z)]);
        Vec3d chord = surface[index(x, Math.min(CHORD, z + 1))].subtract(surface[index(x, Math.max(0, z - 1))]);
        Vec3d normal = chord.crossProduct(span); double length = normal.length();
        // 完全收拢的退化节点使用外向竖直法线，不向光影提交零向量。
        return length > 1e-12 ? normal.multiply(sign / length) : new Vec3d(0, sign, 0);
    }
}
