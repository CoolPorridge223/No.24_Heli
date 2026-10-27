/** 文件：尾根曲面与分部碰撞共用的机身截面；端侧：COMMON；关联 no_24_heli:helicopter。 */
package org.DJB.no_24_heli.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.Box;

/**
 * 职责：统一尾梁与机身连接处的曲面和四段碰撞包络，不修改顶部或旋翼连接结构。
 * 引用 net.minecraft.util.math.Box 保存局部盒；模型构造和分部碰撞读取同一截面。
 * 生命周期：静态初始化一次；端侧/线程：COMMON，只读几何，不访问世界或客户端类。
 * 排查：尾根凹陷查两端切线，碰撞错位查局部 +Z 机头约定及分部姿态变换。
 */
public final class HelicopterGeometry {
    /** 尾根包络预留 1.2 厘米采样余量，单位格，不影响其他分部。 */
    private static final double BOUNDS_MARGIN = .012;
    /** 每段包络取 64 个间隔，仅静态初始化一次。 */
    private static final int BOUNDS_SAMPLES = 64;
    /** 各纵截面依次为 Z、半宽、中心高度、半高；前舱数据沿用原机模。 */
    private static final double[][] BODY_PROFILE = {
            {-5.06, .075, 1.87, .075}, {-3.40, .14, 1.68, .14}, {-2.10, .20, 1.49, .20},
            {-1.78, .25, 1.40, .25}, {-1.55, .43, 1.21, .38}, {-1.15, .83, 1.23, .68},
            {-.65, .97, 1.24, .80}, {0, .98, 1.24, .83}, {.50, .91, 1.23, .80},
            {.95, .79, 1.16, .67}, {1.40, .60, 1.03, .47}, {1.75, .36, .91, .28},
            {1.97, .025, .86, .035}};
    /** 尾根四段包络，仍属于原 TAIL_BOOM 模块。 */
    public static final List<Box> TAIL_ROOT_BOUNDS = tailRootBounds();

    /** 只读几何工具，不创建实例。 */
    private HelicopterGeometry() {}

    /**
     * 功能：尾根跨过原凹陷控制点并保留两端切线；无 MC API，无世界修改。
     * 参数：z 为纵向格数，column 为半宽/中心高度/半高的 1/2/3；返回：截面参数。
     * 端侧：COMMON；排查：只在 -2.10..-1.15 格改形，前舱轮廓保持原数据与算法。
     */
    public static double profile(double z, int column) {
        int index = 0;
        while (index < BODY_PROFILE.length - 2 && z > BODY_PROFILE[index + 1][0]) index++;
        int end = index + 1;
        if (z >= BODY_PROFILE[2][0] && z <= BODY_PROFILE[5][0]) { index = 2; end = 5; }
        double[] a = BODY_PROFILE[index], b = BODY_PROFILE[end];
        double t = Math.clamp((z - a[0]) / (b[0] - a[0]), 0, 1);
        double[] before = BODY_PROFILE[Math.max(0, index - 1)];
        double[] after = BODY_PROFILE[Math.min(BODY_PROFILE.length - 1, end + 1)];
        double[] next = BODY_PROFILE[index + 1], previous = BODY_PROFILE[end - 1];
        double tangentA = (next[column] - before[column]) / (next[0] - before[0]) * (b[0] - a[0]);
        double tangentB = (after[column] - previous[column]) / (after[0] - previous[0]) * (b[0] - a[0]);
        return (2 * t * t * t - 3 * t * t + 1) * a[column] + (t * t * t - 2 * t * t + t) * tangentA
                + (-2 * t * t * t + 3 * t * t) * b[column] + (t * t * t - t * t) * tangentB;
    }
    /**
     * 功能：从共享椭圆截面建立四段尾根包络；引用 net.minecraft.util.math.Box 保存边界。
     * 返回：只读局部盒；端侧：COMMON 静态初始化；不访问世界或修改实体状态。
     * 排查：与原 CABIN/TAIL_BOOM 分界 -1.30 格相接，其他尾梁和旋翼模块沿用原盒。
     */
    private static List<Box> tailRootBounds() {
        List<Box> result = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            double back = -2.18 + .88 * i / 4, front = -2.18 + .88 * (i + 1) / 4;
            double width = 0, low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
            for (int j = 0; j <= BOUNDS_SAMPLES; j++) {
                double z = back + (front - back) * j / BOUNDS_SAMPLES;
                width = Math.max(width, profile(z, 1));
                low = Math.min(low, profile(z, 2) - profile(z, 3));
                high = Math.max(high, profile(z, 2) + profile(z, 3));
            }
            result.add(new Box(-width - BOUNDS_MARGIN, low - BOUNDS_MARGIN, back,
                    width + BOUNDS_MARGIN, high + BOUNDS_MARGIN, front));
        }
        return List.copyOf(result);
    }
}
