package org.DJB.no_24_heli.flight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件：将 A* 父节点转成真实坐标航点，平滑和最终输出均检查完整扩张圆安全。
 * 端侧：COMMON；规划工作线程调用，无 Minecraft API、无注册项。
 * 排查：切圈先查完整线段校验，终点偏移查是否保留原始 goal，耗尽查 GEOMETRY_LIMIT。
 */
final class NavigationPath {
    /** 坐标转换结果；失败不输出部分路径，避免跟随器误用尚未验证的航段。 */
    record Conversion(NavigationRoute.Status status, List<NavigationRoute.Point> path) {}

    /** 功能：禁止实例化转换工具；无 MC API、无副作用。 */
    private NavigationPath() {}

    /**
     * 功能：恢复格心路径，连接真实起终点并以可见性删除多余航点。
     * 引用 MC API：无。端侧/线程：规划线程。参数：成功 A* 的父节点数组。
     * 返回：不含起点、保留真实终点的不可变安全路径。副作用：本次几何计数递增。
     * 故障排查：终点不可替换为格心；原始连接及每条平滑输出均不得擦扩张圆边。
     */
    static Conversion convert(NavigationRoute.Point start, NavigationRoute.Point goal,
                              List<NavigationRoute.Circle> zones, NavigationRoute.Settings settings,
                              NavigationGrid grid, int startCell, int goalCell, int[] previous,
                              NavigationGrid.Budget budget) {
        List<Integer> reverse = new ArrayList<>();
        for (int at = goalCell; ; at = previous[at]) {
            NavigationRoute.Status limit = budget.geometry();
            if (limit != NavigationRoute.Status.SUCCESS) {
                return failure(limit);
            }
            reverse.add(at);
            if (at == startCell) {
                break;
            }
        }
        Collections.reverse(reverse);
        List<NavigationRoute.Point> raw = new ArrayList<>(reverse.size() + 2);
        raw.add(start);
        for (int index : reverse) {
            NavigationRoute.Point center = grid.center(index);
            if (NavigationRoute.distance(raw.getLast(), center) > NavigationRoute.POSITION_EPSILON) {
                raw.add(center);
            }
        }
        // 保留玩家输入的真实终点，即使它几乎与格心重合，也不交付坐标被移动的路径。
        raw.add(goal);
        NavigationRoute.Status rawStatus = validate(raw, zones, settings, budget);
        if (rawStatus != NavigationRoute.Status.SUCCESS) {
            return failure(rawStatus);
        }
        return smooth(raw, zones, settings, budget);
    }

    /** 功能：顺序扩展可见航段，遇障碍保留上一个可见点，工作量受有限预算约束。 */
    private static Conversion smooth(List<NavigationRoute.Point> raw, List<NavigationRoute.Circle> zones,
                                      NavigationRoute.Settings settings, NavigationGrid.Budget budget) {
        List<NavigationRoute.Point> result = new ArrayList<>();
        NavigationRoute.Point anchor = raw.getFirst();
        NavigationRoute.Point visible = anchor;
        for (int at = 1; at < raw.size(); at++) {
            NavigationRoute.Point candidate = raw.get(at);
            NavigationRoute.Status checked = followingSegment(anchor, candidate, zones, settings, budget);
            if (checked == NavigationRoute.Status.GRID_RESOLUTION) {
                result.add(visible);
                anchor = visible;
                checked = followingSegment(anchor, candidate, zones, settings, budget);
            }
            if (checked != NavigationRoute.Status.SUCCESS) {
                return failure(checked);
            }
            visible = candidate;
        }
        result.add(raw.getLast());
        // 最终再验证从真实起点出发的全部输出线段，防止平滑后漏检任意连接。
        List<NavigationRoute.Point> output = new ArrayList<>(result.size() + 1);
        output.add(raw.getFirst());
        output.addAll(result);
        NavigationRoute.Status validated = validate(output, zones, settings, budget);
        return validated == NavigationRoute.Status.SUCCESS
                ? new Conversion(validated, List.copyOf(result)) : failure(validated);
    }

    /** 功能：验证相邻航点的完整线段，资源失败与几何不安全分开返回。 */
    private static NavigationRoute.Status validate(List<NavigationRoute.Point> path,
                                                    List<NavigationRoute.Circle> zones,
                                                    NavigationRoute.Settings settings,
                                                    NavigationGrid.Budget budget) {
        for (int at = 1; at < path.size(); at++) {
            NavigationRoute.Status status = segment(path.get(at - 1), path.get(at), zones, settings, budget);
            if (status != NavigationRoute.Status.SUCCESS) {
                return status;
            }
        }
        return NavigationRoute.Status.SUCCESS;
    }

    /** 功能：带预算验证单个闭线段，机体与安全间距不因靠近终点而降低。 */
    private static NavigationRoute.Status segment(NavigationRoute.Point from, NavigationRoute.Point to,
                                                   List<NavigationRoute.Circle> zones,
                                                   NavigationRoute.Settings settings,
                                                   NavigationGrid.Budget budget) {
        NavigationRoute.Status interrupted = budget.geometry();
        if (interrupted != NavigationRoute.Status.SUCCESS) {
            return interrupted;
        }
        for (NavigationRoute.Circle circle : zones) {
            NavigationRoute.Status limit = budget.geometry();
            if (limit != NavigationRoute.Status.SUCCESS) {
                return limit;
            }
            if (NavigationRoute.segmentDistance(from, to, circle) <= circle.radius() + settings.clearance()) {
                return NavigationRoute.Status.GRID_RESOLUTION;
            }
        }
        return NavigationRoute.Status.SUCCESS;
    }

    /**
     * 功能：平滑时保留原四邻格心线的半格额外净空，真实起终点按自身实际净空放宽。
     * 引用 MC API：无。端侧/线程：规划线程。返回：可见性或独立资源状态。
     * 副作用：本次预算递增。故障排查：切角后停在扩张边缘查是否误用无跟随余量的 segment。
     */
    private static NavigationRoute.Status followingSegment(NavigationRoute.Point from, NavigationRoute.Point to,
                                                            List<NavigationRoute.Circle> zones,
                                                            NavigationRoute.Settings settings,
                                                            NavigationGrid.Budget budget) {
        NavigationRoute.Status interrupted = budget.geometry();
        if (interrupted != NavigationRoute.Status.SUCCESS) return interrupted;
        for (NavigationRoute.Circle circle : zones) {
            NavigationRoute.Status limit = budget.geometry();
            if (limit != NavigationRoute.Status.SUCCESS) return limit;
            if (!NavigationRoute.followingSegmentClear(from,to,circle,settings))
                return NavigationRoute.Status.GRID_RESOLUTION;
        }
        return NavigationRoute.Status.SUCCESS;
    }

    /** 功能：失败时不交付未完成路径；无 MC API、无副作用。 */
    private static Conversion failure(NavigationRoute.Status status) {
        return new Conversion(status, List.of());
    }
}
