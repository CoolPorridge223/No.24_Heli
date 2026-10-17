/** 文件：滑翔翼表面张力与阻尼布面；端侧：CLIENT，仅视觉网格，不计算玩家气动力。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.entity.ParachuteEntity;
import org.DJB.no_24_heli.flight.ParachuteCanopy;


import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.math.Vec3d;

/**
 * 职责：25×9 节点 Verlet 布面，用结构/剪切约束保持冲压翼弧形，后缘允许气流起伏。
 * 引用：net.minecraft.util.math.Vec3d 保存局部位置；ParachuteEntity 的同步充气和速度驱动表现。
 * 生命周期：每具可见伞独立实例，渲染器每个客户端 tick 最多积分一次，帧间插值。
 * 端侧/线程：仅客户端渲染线程；不修改实体或服务端世界。
 * 排查：过度抖动查阻尼和约束遍数，翼面扭曲查 restPoint；节点不能跨玩家共享。
 */
@Environment(EnvType.CLIENT)
final class ParachuteCloth {
    /** 跨翼二十四段形成十二个冲压气室，弦向八段；不依赖帧率改变网格密度。 */
    static final int SPAN = 24, CHORD = 8;
    /** 高阻尼保留小幅布面起伏；约束三遍控制结构和剪切拉伸，后缘响应更柔和。 */
    private static final double DAMPING = .78;
    private final Vec3d[] nodes = new Vec3d[(SPAN + 1) * (CHORD + 1)];
    private final Vec3d[] previous = new Vec3d[nodes.length], frame = new Vec3d[nodes.length];
    private final Vec3d[] rest = new Vec3d[nodes.length];
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
        double cell = .045 * Math.sin(x * Math.PI / 2) * Math.sin(v * Math.PI) * opening;
        return ParachuteCanopy.localPoint(u, v, opening, ticks)
                .add(0, cell + (1 - opening) * .15 * Math.cos(x * 1.7 + z), 0);
    }

    /**
     * 每 tick 以惯性、气流和充气压力积分；结构和对角约束限制伸长，不能只画正弦动画冒充布面。
     * net.minecraft.entity.Entity#getVelocity 提供同步气流变化，跨越不可见时段重置以免数值爆炸。
     */
    void update(ParachuteEntity canopy) {
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
            double flutter = Math.sin(canopy.age * .47 + x * .83 + z * .62)
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
    void prepareLoose(ParachuteEntity canopy, float delta) {
        loose = true; Vec3d origin = canopy.getLerpedPos(delta);
        for (int x = 0; x <= SPAN; x++) for (int z = 0; z <= CHORD; z++) {
            Vec3d world = canopy.loosePoint(x, z, delta).add(origin);
            nodes[index(x, z)] = ParachuteCanopy.surfacePoint(canopy, world).subtract(origin);
        }
    }

    /** 两节点的结构/剪切弹簧约束，休止长度随真实充气形状改变；无世界访问或权威写入。 */
    private void constrain(int a, int b) {
        Vec3d offset = nodes[b].subtract(nodes[a]); double distance = offset.length();
        if (distance < .000001) return;
        Vec3d correction = offset.multiply((distance - rest[a].distanceTo(rest[b])) / distance * .42);
        nodes[a] = nodes[a].add(correction); nodes[b] = nodes[b].subtract(correction);
    }

    /** net.minecraft.util.math.Vec3d#lerp 在两个模拟帧之间插值，供三角面、气室和吊绳共用。 */
    Vec3d point(int x, int z, float delta) {
        int i = index(x, z); return loose ? nodes[i] : frame[i].lerp(nodes[i], delta);
    }
}
