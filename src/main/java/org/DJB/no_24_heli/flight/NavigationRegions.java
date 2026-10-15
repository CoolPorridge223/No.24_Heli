package org.DJB.no_24_heli.flight;

import java.util.Arrays;
import java.util.List;

/**
 * 文件：用扩张圆并集内闭环的实际绕数验证网格洪泛的封闭属性。
 * 端侧：COMMON；规划工作线程调用，无 Minecraft API、无注册项。
 * 排查：公共交叠形成的图环不一定围住合法点，必须查坐标绕数；窄缝误堵查洪泛与本类差异。
 */
final class NavigationRegions {
    /** 完整一圈的角度，单位为弧度；绕数只在点位于全部扩张圆外时使用。 */
    private static final double FULL_TURN = 2.0 * Math.PI;

    /** 两端真实自由区域属性；sameRegion 比较所有基本环绕数，而非只比较封闭布尔值。 */
    record Classification(NavigationRoute.Status status, boolean startEnclosed,
                          boolean goalEnclosed, boolean sameRegion) {}

    /** 功能：禁止实例化纯几何工具；无 MC API、无副作用。 */
    private NavigationRegions() {}

    /**
     * 功能：按扩张圆真实相交关系生成森林，再计算全部闭环对两端坐标的整数绕数。
     * 引用 MC API：无。端侧/线程：规划线程。参数：两端必须在扩张圆外，由自由格保证。
     * 返回：真实封闭和同一区域标记，或独立几何资源状态。副作用：本次预算计数。
     * 故障排查：相交圆中心连线全部被圆并集覆盖，闭环绕住圆外点才构成真实障碍屏障。
     * 公共交叠处的图环对合法圆外点绕数为零，不能只检查图是否有环。
     */
    static Classification classify(NavigationRoute.Point start, NavigationRoute.Point goal,
                                   List<NavigationRoute.Circle> zones, NavigationRoute.Settings settings,
                                   NavigationGrid.Budget budget) {
        int count = zones.size();
        // 两次完整圆对检查是本方法必要工作，先检查预算再分配，避免海量圆造成无界内存。
        if ((long) count * (count - 1) > budget.remainingGeometry()) {
            return failure(NavigationRoute.Status.GEOMETRY_LIMIT);
        }
        int[] parent = new int[count];
        int[] size = new int[count];
        int[] heads = new int[count];
        Arrays.fill(heads, -1);
        int edges = Math.max(0, count - 1) * 2;
        int[] neighbors = new int[edges];
        int[] next = new int[edges];
        for (int at = 0; at < count; at++) {
            parent[at] = at;
            size[at] = 1;
        }
        NavigationRoute.Status built = forest(zones, settings.clearance(), budget,
                parent, size, heads, neighbors, next);
        if (built != NavigationRoute.Status.SUCCESS) {
            return failure(built);
        }
        double[] startAngles = new double[count];
        double[] goalAngles = new double[count];
        NavigationRoute.Status traversed = angles(start, goal, zones, budget,
                heads, neighbors, next, startAngles, goalAngles);
        if (traversed != NavigationRoute.Status.SUCCESS) {
            return failure(traversed);
        }
        return windings(start, goal, zones, settings.clearance(), budget, startAngles, goalAngles);
    }

    /** 功能：将真实相交圆中心连边，用并查集保留生成森林，切触也算闭障碍接触。 */
    private static NavigationRoute.Status forest(List<NavigationRoute.Circle> zones, double clearance,
                                                 NavigationGrid.Budget budget, int[] parent, int[] size,
                                                 int[] heads, int[] neighbors, int[] next) {
        int edges = 0;
        for (int a = 0; a < zones.size(); a++) {
            for (int b = a + 1; b < zones.size(); b++) {
                NavigationRoute.Status limit = budget.geometry();
                if (limit != NavigationRoute.Status.SUCCESS) {
                    return limit;
                }
                if (!overlap(zones.get(a), zones.get(b), clearance)) {
                    continue;
                }
                int rootA = root(parent, a);
                int rootB = root(parent, b);
                if (rootA == rootB) {
                    continue;
                }
                if (size[rootA] < size[rootB]) {
                    int swap = rootA;
                    rootA = rootB;
                    rootB = swap;
                }
                parent[rootB] = rootA;
                size[rootA] += size[rootB];
                neighbors[edges] = b;
                next[edges] = heads[a];
                heads[a] = edges++;
                neighbors[edges] = a;
                next[edges] = heads[b];
                heads[b] = edges++;
            }
        }
        return NavigationRoute.Status.SUCCESS;
    }

    /** 功能：逐棵树累计两端坐标对中心连线的有向角，供非树边快速还原闭环绕数。 */
    private static NavigationRoute.Status angles(NavigationRoute.Point start, NavigationRoute.Point goal,
                                                 List<NavigationRoute.Circle> zones, NavigationGrid.Budget budget,
                                                 int[] heads, int[] neighbors, int[] next,
                                                 double[] startAngles, double[] goalAngles) {
        boolean[] visited = new boolean[zones.size()];
        int[] queue = new int[zones.size()];
        for (int origin = 0; origin < zones.size(); origin++) {
            if (visited[origin]) {
                continue;
            }
            int head = 0;
            int tail = 1;
            queue[0] = origin;
            visited[origin] = true;
            while (head < tail) {
                NavigationRoute.Status limit = budget.geometry();
                if (limit != NavigationRoute.Status.SUCCESS) {
                    return limit;
                }
                int from = queue[head++];
                for (int edge = heads[from]; edge >= 0; edge = next[edge]) {
                    int to = neighbors[edge];
                    if (!visited[to]) {
                        visited[to] = true;
                        startAngles[to] = startAngles[from] + angle(zones.get(from), zones.get(to), start);
                        goalAngles[to] = goalAngles[from] + angle(zones.get(from), zones.get(to), goal);
                        queue[tail++] = to;
                    }
                }
            }
        }
        return NavigationRoute.Status.SUCCESS;
    }

    /** 功能：比较森林加每条相交边产生的基本环绕数，真实识别不同圆并集孔洞。 */
    private static Classification windings(NavigationRoute.Point start, NavigationRoute.Point goal,
                                           List<NavigationRoute.Circle> zones, double clearance,
                                           NavigationGrid.Budget budget,
                                           double[] startAngles, double[] goalAngles) {
        boolean startEnclosed = false;
        boolean goalEnclosed = false;
        boolean same = true;
        for (int a = 0; a < zones.size(); a++) {
            for (int b = a + 1; b < zones.size(); b++) {
                NavigationRoute.Status limit = budget.geometry();
                if (limit != NavigationRoute.Status.SUCCESS) {
                    return failure(limit);
                }
                if (!overlap(zones.get(a), zones.get(b), clearance)) {
                    continue;
                }
                long startWinding = Math.round((startAngles[a]
                        + angle(zones.get(a), zones.get(b), start) - startAngles[b]) / FULL_TURN);
                long goalWinding = Math.round((goalAngles[a]
                        + angle(zones.get(a), zones.get(b), goal) - goalAngles[b]) / FULL_TURN);
                startEnclosed |= startWinding != 0;
                goalEnclosed |= goalWinding != 0;
                same &= startWinding == goalWinding;
            }
        }
        return new Classification(NavigationRoute.Status.SUCCESS, startEnclosed, goalEnclosed, same);
    }

    /** 功能：判断两扩张闭圆是否真实接触；无 MC API、无副作用。 */
    private static boolean overlap(NavigationRoute.Circle a, NavigationRoute.Circle b, double clearance) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z()) <= a.radius() + b.radius() + clearance * 2.0;
    }

    /** 功能：取得并压缩并查集根节点；无 MC API，仅修改本次数组。 */
    private static int root(int[] parent, int at) {
        while (parent[at] != at) {
            parent[at] = parent[parent[at]];
            at = parent[at];
        }
        return at;
    }

    /** 功能：返回圆外点观察中心连线的有向最小角，范围为负π到π，单位为弧度。 */
    private static double angle(NavigationRoute.Circle from, NavigationRoute.Circle to, NavigationRoute.Point p) {
        double angle = Math.atan2(to.z() - p.z(), to.x() - p.x())
                - Math.atan2(from.z() - p.z(), from.x() - p.x());
        if (angle > Math.PI) {
            angle -= FULL_TURN;
        } else if (angle <= -Math.PI) {
            angle += FULL_TURN;
        }
        return angle;
    }

    /** 功能：生成未完成分类的资源失败结果；无 MC API、无副作用。 */
    private static Classification failure(NavigationRoute.Status status) {
        return new Classification(status, false, false, false);
    }
}
