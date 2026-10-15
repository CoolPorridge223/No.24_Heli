package org.DJB.no_24_heli.flight;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件：安全曲线及弧长速度表，归属实体飞控 flight 模块，无注册项和 MC API。
 * 端侧：COMMON；规划线程一次构建，服务端只读。曲线整段采样并校验所有扩张圆。
 * 排查：曲线切圈查采样段而非控制点；弯前刹停查曲率；长路线资源失败查采样预算。
 */
public final class NavigationTrajectory {
    /** 允许的横向加速度、减速度，单位格/tick²；按原积分器及 25 度侧倾留出余量。 */
    public static final double LATERAL_ACCELERATION = .008, BRAKE = .004;
    /** 最大偏航速率，弧度/tick；1.4 度/tick 低于原脚蹬完整控制能力。 */
    public static final double YAW_RATE = Math.toRadians(1.4);
    /** 直线采样间距 2 格，曲线间距 0.5 格；曲线附加 0.25 格弦误差余量。 */
    private static final double SPACING = 2, CURVE_SPACING = .5, CURVE_MARGIN = .25;
    /** 最大切角距离 48 格，最多缩小 8 次；低于半径 0.5 格的曲线拒绝飞行。 */
    private static final double MAX_CUT = 48, MAX_CURVATURE = 2;
    /** 只读采样点、累计弧长、曲率和预计算速度上限；速度单位格/tick。 */
    private final List<NavigationRoute.Point> points;
    private final double[] lengths, curvature, speeds;

    /** 构造结果，失败不交付半条曲线；无 MC API，工作线程创建。 */
    public record Build(NavigationRoute.Status status, NavigationTrajectory trajectory) {}

    /** 功能：生成不可变弧长和速度表；仅后台调用，无 MC API 或世界副作用。 */
    private NavigationTrajectory(List<NavigationRoute.Point> points, double cruise) {
        this.points = List.copyOf(points);
        int count = points.size();
        lengths = new double[count]; curvature = new double[count]; speeds = new double[count];
        for (int i = 1; i < count; i++) lengths[i] = lengths[i - 1]
                + NavigationRoute.distance(points.get(i - 1), points.get(i));
        for (int i = 0; i < count; i++) {
            if (i > 0 && i + 1 < count) curvature[i] = bend(points.get(i - 1), points.get(i), points.get(i + 1));
            double k = Math.abs(curvature[i]);
            speeds[i] = k < 1e-6 ? cruise : Math.min(cruise,
                    Math.min(Math.sqrt(LATERAL_ACCELERATION / k), YAW_RATE / k));
        }
        speeds[count - 1] = 0;
        // 从终点倒推真实制动距离，连续多个弯道共同决定速度包络。
        for (int i = count - 2; i >= 0; i--) speeds[i] = Math.min(speeds[i],
                Math.sqrt(speeds[i + 1] * speeds[i + 1] + 2 * BRAKE * (lengths[i + 1] - lengths[i])));
    }

    /**
     * 功能：将可见性折线变成切线连续的二次 Bezier 曲线，再建立速度包络。
     * 引用 MC API：无。端侧/线程：后台；参数为不可变起点、航路、圆、设置和巡航速度。
     * 返回：完整轨迹或精度/资源失败。副作用仅本次局部数组和预算。
     * 故障排查：不安全切角逐次缩小；仍无法实现的尖角拒绝，不让控制器强行穿圆。
     */
    public static Build build(NavigationRoute.Point start, List<NavigationRoute.Point> path,
            List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings, double cruise) {
        var budget = new NavigationGrid.Budget(settings.maxCells(), settings.maxNodes());
        List<NavigationRoute.Point> raw = new ArrayList<>(); raw.add(start); raw.addAll(path);
        List<NavigationRoute.Point> output = new ArrayList<>(); output.add(start);
        for (int i = 1; i + 1 < raw.size(); i++) {
            var a = raw.get(i - 1); var corner = raw.get(i); var b = raw.get(i + 1);
            double incoming = NavigationRoute.distance(a, corner), outgoing = NavigationRoute.distance(corner, b);
            double cut = Math.min(MAX_CUT, Math.min(incoming, outgoing) * .45);
            boolean accepted = false;
            for (int attempt = 0; attempt < 8 && !accepted; attempt++, cut *= .5) {
                var entry = interpolate(corner, a, cut / Math.max(incoming, 1e-6));
                var exit = interpolate(corner, b, cut / Math.max(outgoing, 1e-6));
                List<NavigationRoute.Point> curve = new ArrayList<>(); curve.add(entry);
                int steps = Math.max(4, (int) Math.ceil(cut * 2 / CURVE_SPACING));
                for (int n = 1; n <= steps; n++) {
                    double t = (double) n / steps;
                    curve.add(interpolate(interpolate(entry, corner, t), interpolate(corner, exit, t), t));
                }
                NavigationRoute.Status status = validate(curve, circles, settings, budget, CURVE_MARGIN);
                if (status == NavigationRoute.Status.GEOMETRY_LIMIT || status == NavigationRoute.Status.INTERRUPTED)
                    return new Build(status, null);
                if (status != NavigationRoute.Status.SUCCESS) continue;
                boolean feasible = true;
                for (int n = 1; n + 1 < curve.size(); n++)
                    feasible &= Math.abs(bend(curve.get(n - 1), curve.get(n), curve.get(n + 1))) <= MAX_CURVATURE;
                if (!feasible) continue;
                if (!appendLine(output, entry, settings.maxCells())) return new Build(NavigationRoute.Status.CELL_LIMIT, null);
                if (output.size() + curve.size() - 1 > settings.maxCells()) return new Build(NavigationRoute.Status.CELL_LIMIT, null);
                output.addAll(curve.subList(1, curve.size())); accepted = true;
            }
            if (!accepted) return new Build(NavigationRoute.Status.TRAJECTORY_UNSAFE, null);
        }
        if (!appendLine(output, raw.getLast(), settings.maxCells())) return new Build(NavigationRoute.Status.CELL_LIMIT, null);
        NavigationRoute.Status status = validate(output, circles, settings, budget, 0);
        return new Build(status, status == NavigationRoute.Status.SUCCESS ? new NavigationTrajectory(output, cruise) : null);
    }

    /** 功能：完整线段逐圆验证，带资源和取消检查；只修改局部预算，无 MC API。 */
    private static NavigationRoute.Status validate(List<NavigationRoute.Point> path,
            List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings,
            NavigationGrid.Budget budget, double extra) {
        for (int i = 1; i < path.size(); i++) for (var circle : circles) {
            var status = budget.geometry(); if (status != NavigationRoute.Status.SUCCESS) return status;
            if (NavigationRoute.segmentDistance(path.get(i - 1), path.get(i), circle)
                    <= circle.radius() + settings.clearance() + extra) return NavigationRoute.Status.TRAJECTORY_UNSAFE;
        }
        return Thread.currentThread().isInterrupted() ? NavigationRoute.Status.INTERRUPTED : NavigationRoute.Status.SUCCESS;
    }

    /** 功能：有界加密直线以固定跟踪窗口工作量；无 MC API，仅追加局部点集。 */
    private static boolean appendLine(List<NavigationRoute.Point> points, NavigationRoute.Point end, int maxPoints) {
        var start = points.getLast(); double distance = NavigationRoute.distance(start, end);
        if (distance < 1e-6) return true;
        double count = Math.ceil(distance / SPACING);
        if (count + points.size() > maxPoints) return false;
        for (int n = 1; n <= (int) count; n++) points.add(interpolate(start, end, n / count));
        return true;
    }

    /** 功能：三点外接圆的有符号曲率，正值对应正偏航（左转），单位 1/格；无 MC API。 */
    private static double bend(NavigationRoute.Point a, NavigationRoute.Point b, NavigationRoute.Point c) {
        double denominator = NavigationRoute.distance(a, b) * NavigationRoute.distance(b, c) * NavigationRoute.distance(a, c);
        return denominator < 1e-9 ? 0 : 2 * ((b.x() - a.x()) * (c.z() - b.z())
                - (b.z() - a.z()) * (c.x() - b.x())) / denominator;
    }

    /** 功能：线段内插，参数限制由调用者保证；无 MC API，无副作用。 */
    public static NavigationRoute.Point interpolate(NavigationRoute.Point a, NavigationRoute.Point b, double t) {
        return new NavigationRoute.Point(a.x() + (b.x() - a.x()) * t, a.z() + (b.z() - a.z()) * t);
    }
    /** 功能：返回只读采样点，供跟随和原地形扫描；无副作用。 */
    public List<NavigationRoute.Point> points() { return points; }
    /** 功能：返回点的累计弧长，单位格；无副作用。 */
    public double length(int at) { return lengths[at]; }
    /** 功能：返回全程弧长，单位格；无副作用。 */
    public double totalLength() { return lengths[lengths.length - 1]; }
    /** 功能：返回采样点的速度包络，单位格/tick；无副作用。 */
    public double speed(int at) { return speeds[at]; }
    /** 功能：返回采样点曲率，单位 1/格；无副作用。 */
    public double curvature(int at) { return curvature[at]; }
}
