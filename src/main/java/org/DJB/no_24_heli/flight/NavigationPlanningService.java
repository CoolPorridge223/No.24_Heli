package org.DJB.no_24_heli.flight;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 文件职责：在有界后台队列中执行纯坐标 A*，不把世界或实体交给工作线程。
 * 端侧：SERVER；关联通道：无，导航请求仍使用 no_24_heli:navigation。
 * 依赖 MC API：无；NavigationPilot 在 net.minecraft.server.world.ServerWorld 的 tick 中制作快照。
 * 生命周期：全进程共用一个守护工作线程，各飞机持有自己的 Future 并在取消时移出队列。
 * 故障排查：规划排队查 QUEUE_CAPACITY；取消后仍返回结果查主线程是否丢弃旧 Future；
 * 服务器卡顿查工作任务中是否引入了世界 API。
 */
public final class NavigationPlanningService {
    /** 最多排队 16 个请求；单工作线程确保同一时刻只有一份大网格占用计算资源。 */
    private static final int QUEUE_CAPACITY = 16;
    /** 后台任务只能捕获不可变的 Point、Circle、Settings，不持有飞机或世界引用。 */
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_CAPACITY), task -> {
                Thread worker = new Thread(task, "no24-navigation-astar");
                worker.setDaemon(true);
                return worker;
            });

    /** 完整后台结果；elapsedNanos用于单次诊断，reused表示旧轨迹全段仍安全，不重置进度。 */
    public record Planned(NavigationRoute.Result route, NavigationTrajectory trajectory, boolean reused, long elapsedNanos) {}
    /** 曲线优先绕行余量，单位格；窄通道改用原安全范围，不改变禁区管理数据。 */
    private static final double TURN_MARGIN = 12;

    /** 无实例状态；任务的生命周期由调用方管理。 */
    private NavigationPlanningService() {}

    /**
     * 功能：把已完成的地形快照送入同一有界工作队列；不新增线程或绕开取消机制。
     * 无 MC API；服务端提交、后台只读，快照不含世界或实体；队列满保留可用航段并限频重试。
     */
    static Future<NavigationTerrainRoute.Result> submitTerrain(NavigationTerrainSnapshot snapshot) {
        return EXECUTOR.submit(snapshot::plan);
    }

    /**
     * 功能：提交一次规划，队列满时由调用方处理 RejectedExecutionException 并继续悬停。
     * 引用 MC API：无。端侧/线程：服务端 tick 提交，纯算法在后台执行。
     * 参数：起终点、全部圆快照和有限资源配置。返回：可取消的规划结果。
     * 副作用：向有界队列添加任务；不会读取世界、区块、实体或持久状态。
     * 故障排查：数据变化没有失效时检查调用方是否取消旧任务并更新快照。
     */
    public static Future<Planned> submit(NavigationRoute.Point start, NavigationRoute.Point goal,
            List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings,
            NavigationTrajectory existing, int nextNode) {
        List<NavigationRoute.Circle> snapshot = List.copyOf(circles);
        return EXECUTOR.submit(() -> plan(start, goal, snapshot, settings, existing, nextNode));
    }

    /**
     * 功能：先验证旧轨迹或选择宽弧路线，完成全局引导平滑后一次性交付。
     * 引用 MC API：无。端侧/线程：仅工作线程；参数全为只读快照。
     * 返回：轨迹及耗时；副作用仅本次计算。排查：旧轨迹安全时 reused 不得重置跟随进度。
     */
    static Planned plan(NavigationRoute.Point start, NavigationRoute.Point goal,
            List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings,
            NavigationTrajectory existing, int nextNode) {
        long began = System.nanoTime();
        if (existing != null && remainingClear(start, existing, nextNode, circles, settings)) {
            var result = new NavigationRoute.Result(NavigationRoute.Status.SUCCESS, existing.points(), 0,
                    false, false, settings.cellSize());
            return new Planned(result, existing, true, System.nanoTime() - began);
        }
        var wide = new NavigationRoute.Settings(settings.cellSize(), settings.horizontalRadius(),
                settings.safetyMargin() + TURN_MARGIN, settings.boundaryMargin(), settings.maxCells(), settings.maxNodes());
        NavigationRoute.Result result = NavigationRoute.plan(start, goal, circles, wide);
        if (result.status() != NavigationRoute.Status.SUCCESS && result.status() != NavigationRoute.Status.INTERRUPTED) {
            int wideNodes=result.expandedNodes();
            if(wideNodes<settings.maxNodes()) {
                var remaining=new NavigationRoute.Settings(settings.cellSize(),settings.horizontalRadius(),settings.safetyMargin(),
                        settings.boundaryMargin(),settings.maxCells(),settings.maxNodes()-wideNodes);
                result=NavigationRoute.plan(start,goal,circles,remaining);
                result=new NavigationRoute.Result(result.status(),result.path(),wideNodes+result.expandedNodes(),
                        result.startEnclosed(),result.goalEnclosed(),result.cellSize());
            }
        }
        if (result.status() != NavigationRoute.Status.SUCCESS) return new Planned(result, null, false, System.nanoTime() - began);
        var built = NavigationTrajectory.build(start, result.path(), circles, settings);
        if (built.status() != NavigationRoute.Status.SUCCESS) result = new NavigationRoute.Result(built.status(), List.of(),
                result.expandedNodes(), result.startEnclosed(), result.goalEnclosed(), result.cellSize());
        return new Planned(result, built.trajectory(), false, System.nanoTime() - began);
    }

    /** 功能：后台检查全部剩余段；资源耗尽则重新规划，绝不在主线程扫描完整轨迹。 */
    private static boolean remainingClear(NavigationRoute.Point start, NavigationTrajectory trajectory, int node,
            List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings) {
        var budget = new NavigationGrid.Budget(settings.maxCells(), settings.maxNodes());
        var from = start;
        for (int i = node; i < trajectory.points().size(); i++) {
            var to = trajectory.points().get(i);
            for (var circle : circles) {
                if (budget.geometry() != NavigationRoute.Status.SUCCESS
                        || NavigationRoute.segmentDistance(from, to, circle) <= circle.radius() + settings.clearance()) return false;
            }
            from = to;
        }
        return !Thread.currentThread().isInterrupted();
    }

    /**
     * 功能：中断运行中的纯算法，并立即移除尚未执行的排队请求。
     * 引用 MC API：无。端侧/线程：服务端 tick、实体移除或手动接管时调用。
     * 参数：当前任务。返回：无。副作用：取消 Future，不应用任何结果。
     * 故障排查：取消占满队列时检查 Future 是否仍为线程池返回的 Runnable 任务。
     */
    public static void cancel(Future<?> task) {
        task.cancel(true);
        EXECUTOR.remove((Runnable) task);
    }
}
