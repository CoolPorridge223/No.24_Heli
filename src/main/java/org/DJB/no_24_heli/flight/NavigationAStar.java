package org.DJB.no_24_heli.flight;

import java.util.Arrays;

/**
 * 文件：八邻 A* 搜索，以索引堆限制队列内存；归属既有 flight 实体飞控模块。
 * 端侧：COMMON，无注册项、无 MC API；每次后台规划独占实例和数组。
 * 排查：切角查两个正交邻格；搜索爆内存查堆索引；精度失败不能解释为几何不可达。
 */
final class NavigationAStar {
    /** 搜索返回父链及展开状态；只在成功到达后恢复路径。 */
    record Search(NavigationRoute.Status status, boolean targetReached, int[] previous) {}
    /** 索引堆、反向索引、父链；每格最多一个堆项，-1 未入堆、-2 已关闭。 */
    private final int[] heap, positions, previous;
    /** 累计代价和含八方向启发式的总代价，单位为格步。 */
    private final double[] cost, score;
    /** 当前堆大小，仅本次工作线程访问。 */
    private int size;

    /** 功能：分配受网格预算约束的数组；无 MC API，无外部副作用。 */
    private NavigationAStar(int cells) {
        heap = new int[cells]; positions = new int[cells]; previous = new int[cells];
        cost = new double[cells]; score = new double[cells];
        Arrays.fill(positions, -1); Arrays.fill(previous, -1);
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
    }

    /**
     * 功能：用一致的八方向启发式寻找代价最小路径，到达终点立即结束。
     * 引用 MC API：无。端侧/线程：后台；参数为不可变网格和本次预算。
     * 返回：父链及资源状态；副作用仅预算记账。封闭性质由 NavigationRegions 独立确认。
     * 故障排查：NODE_LIMIT 是资源失败；未到达是精度不足或真实封闭，调用方须分类。
     */
    static Search search(NavigationGrid grid, int start, int target, NavigationGrid.Budget budget) {
        NavigationAStar open = new NavigationAStar(grid.size());
        open.cost[start] = 0; open.previous[start] = start;
        open.offer(start, heuristic(start, target, grid.width()));
        while (open.size > 0) {
            NavigationRoute.Status limit = budget.node();
            if (limit != NavigationRoute.Status.SUCCESS) return new Search(limit, false, open.previous);
            int cell = open.poll();
            if (cell == target) return new Search(NavigationRoute.Status.SUCCESS, true, open.previous);
            int x = cell % grid.width(), z = cell / grid.width();
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if ((dx == 0 && dz == 0) || x + dx < 0 || x + dx >= grid.width()
                        || z + dz < 0 || z + dz >= grid.size() / grid.width()) continue;
                int next = cell + dx + dz * grid.width();
                if (grid.blocked(next) || open.positions[next] == -2) continue;
                if (dx != 0 && dz != 0 && (grid.blocked(cell + dx)
                        || grid.blocked(cell + dz * grid.width()))) continue;
                double candidate = open.cost[cell] + (dx == 0 || dz == 0 ? 1 : Math.sqrt(2));
                if (candidate >= open.cost[next]) continue;
                open.cost[next] = candidate; open.previous[next] = cell;
                open.offer(next, candidate + heuristic(next, target, grid.width()));
            }
        }
        return new Search(NavigationRoute.Status.SUCCESS, false, open.previous);
    }

    /** 功能：八方向距离的可采纳启发式；无 MC API，无副作用。 */
    private static double heuristic(int from, int to, int width) {
        int dx = Math.abs(from % width - to % width), dz = Math.abs(from / width - to / width);
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    /** 功能：插入或降低一个格子的代价，不产生重复队列项；仅改本实例。 */
    private void offer(int cell, double priority) {
        score[cell] = priority;
        int at = positions[cell];
        if (at < 0) { at = size++; heap[at] = cell; positions[cell] = at; }
        while (at > 0) {
            int parent = (at - 1) / 2;
            if (score[heap[parent]] <= priority) break;
            swap(at, parent); at = parent;
        }
    }

    /** 功能：弹出最小总代价格并标记关闭；无 MC API，仅改本实例。 */
    private int poll() {
        int result = heap[0]; swap(0, --size); positions[result] = -2;
        int at = 0;
        while (at * 2 + 1 < size) {
            int child = at * 2 + 1;
            if (child + 1 < size && score[heap[child + 1]] < score[heap[child]]) child++;
            if (score[heap[at]] <= score[heap[child]]) break;
            swap(at, child); at = child;
        }
        return result;
    }

    /** 功能：交换堆项并同步反向索引；无 MC API，仅改本实例。 */
    private void swap(int a, int b) {
        int cell = heap[a]; heap[a] = heap[b]; heap[b] = cell;
        positions[heap[a]] = a; positions[heap[b]] = b;
    }
}
