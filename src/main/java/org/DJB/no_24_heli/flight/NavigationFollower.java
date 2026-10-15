package org.DJB.no_24_heli.flight;

import java.util.List;

/**
 * 文件：有序局部投影与 Pure Pursuit 前视跟踪，COMMON，无注册项、无 MC API。
 * 生命周期：每架飞机一个实例，服务端 tick 更新；换轨迹才重置进度。
 * 排查：回环跳段查局部窗口和单次进度上限；切圈查可见性缩短；终点停车查剩余弧长。
 */
public final class NavigationFollower {
    /** 每刻最多检查 128 个相邻采样段，不随全程长度增长。 */
    private static final int WINDOW = 128;
    /** 轨迹进度只前进，单位格；segment 是当前段的起点索引。 */
    private double progress;
    private int segment;
    /** 当前不可变轨迹，绑定后才能 sample。 */
    private NavigationTrajectory trajectory;
    /** 控制样本：目标、最终剩余距离、包络速度、曲率、横向误差与安全标志。 */
    public record Sample(NavigationRoute.Point target, double remaining, double speed,
                         double curvature, double deviation, boolean safe) {}

    /** 功能：绑定新轨迹，清除旧弧长；服务端，无 MC API，不修改物理速度。 */
    public void bind(NavigationTrajectory next) { trajectory = next; progress = 0; segment = 0; }

    /**
     * 功能：投影到当前附近航段并沿弧长选择动态前视点，缩短不安全的视线。
     * 引用 MC API：无。端侧/线程：服务端 tick；速度为真实运动反馈，距离单位格。
     * 返回：只读样本。副作用：更新单调进度；不扫描全图，不瞬移。
     * 故障排查：严重偏离由驾驶器触发重规划，禁止全局最近点搜索跳到交叉后的另一条支路。
     */
    public Sample sample(NavigationRoute.Point pos, double speed, double lookMin, double lookMax,
            List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings) {
        var points = trajectory.points();
        double best = Double.POSITIVE_INFINITY, selected = progress;
        double maximum = progress + Math.max(4, speed * 3);
        for (int i = segment; i + 1 < points.size() && i < segment + WINDOW; i++) {
            if (trajectory.length(i) > maximum) break;
            var a = points.get(i); var b = points.get(i + 1);
            double dx = b.x() - a.x(), dz = b.z() - a.z(), square = dx * dx + dz * dz;
            double t = square < 1e-9 ? 0 : Math.clamp(((pos.x() - a.x()) * dx + (pos.z() - a.z()) * dz) / square, 0, 1);
            double along = trajectory.length(i) + Math.sqrt(square) * t;
            if (along > maximum) continue;
            if (along < progress) {
                along = progress;
                t = square < 1e-9 ? 0 : Math.clamp((progress - trajectory.length(i)) / Math.sqrt(square), 0, 1);
            }
            double distance = Math.hypot(a.x() + dx * t - pos.x(), a.z() + dz * t - pos.z());
            if (distance < best) { best = distance; selected = along; }
        }
        progress = selected;
        while (segment + 2 < points.size() && trajectory.length(segment + 1) <= progress) segment++;
        double look = Math.clamp(lookMin + speed * 20, lookMin, lookMax);
        NavigationRoute.Point target = pointAt(Math.min(trajectory.totalLength(), progress + look));
        // 连线必须安全；逐次缩短在已验证轨迹上的前视弧长，不把目标拉进禁区。
        for (int i = 0; i < 8 && !NavigationRoute.clear(pos, target, circles, settings); i++) {
            look *= .5; target = pointAt(Math.min(trajectory.totalLength(), progress + look));
        }
        boolean safe = NavigationRoute.clear(pos, target, circles, settings);
        double span = trajectory.length(segment + 1) - trajectory.length(segment);
        double t = span < 1e-9 ? 0 : (progress - trajectory.length(segment)) / span;
        double allowed = trajectory.speed(segment) * (1 - t) + trajectory.speed(segment + 1) * t;
        // 末端保留位置闭环的微调权限，越过终点时也能用正常操纵返回，不能被零包络锁死。
        if (trajectory.totalLength() - progress < 4) allowed = Math.max(allowed, .12);
        return new Sample(target, trajectory.totalLength() - progress, allowed,
                trajectory.curvature(segment), best, safe);
    }

    /** 功能：在当前前视窗口内插弧长目标；无 MC API，无副作用。 */
    private NavigationRoute.Point pointAt(double distance) {
        var points = trajectory.points(); int at = segment;
        while (at + 2 < points.size() && at < segment + WINDOW && trajectory.length(at + 1) < distance) at++;
        double span = trajectory.length(at + 1) - trajectory.length(at);
        return NavigationTrajectory.interpolate(points.get(at), points.get(at + 1),
                span < 1e-9 ? 0 : Math.clamp((distance - trajectory.length(at)) / span, 0, 1));
    }
    /** 功能：返回下一个采样点索引，供保留的地形预判使用；无副作用。 */
    public int nextNode() { return segment + 1; }
}
