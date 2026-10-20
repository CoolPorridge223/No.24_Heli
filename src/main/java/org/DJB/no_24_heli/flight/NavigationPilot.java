package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.AirspaceState;
import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;


import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 文件职责：编排禁区引导、区块预加载、三维航段提前规划及连续飞行，按时间与顶棚风险选择绕行。
 * 端侧：SERVER；关联通道：no_24_heli:navigation、no_24_heli:controls。
 * 依赖 MC API：net.minecraft.server.world.ServerWorld 提供地形和持久状态；
 * net.minecraft.entity.Entity#getWidth 提供稳定机体尺寸；net.minecraft.entity.player.PlayerEntity 接收提示。
 * 生命周期：每架飞机持有一个实例，由服务端 tick 调用，取消或移除时 reset。
 * 线程：世界和实体仅在 tick 访问；工作线程只计算不可变坐标，不回调实体。
 * 故障排查：旧路线恢复查 pending 的取消；卡顿查 Future 是否阻塞；实际切角查惯性通道限速。
 */
public final class NavigationPilot {
    /** 每次采集最多前视 512 格，主动加载所需区块并提前计算续段；高度目标每 tick 最多变化 0.10 格。 */
    private static final double TERRAIN_LOOK=512,CLIMB_STEP=.10;
    /** 最终到点半径 0.5 格、停稳速度 0.01 格/tick；提交前漂移降至 0.03 格/tick。 */
    private static final double ARRIVAL_DISTANCE=.5,ARRIVAL_SPEED=.01,PLAN_SPEED=.03;
    /** 保守制动加速度 0.008 格/tick²，并为机体姿态响应留出 12 tick。 */
    private static final double BRAKE_ACCELERATION=NavigationController.BRAKE_ACCELERATION,
            BRAKE_RESPONSE=NavigationController.BRAKE_RESPONSE;
    /** 航段失效重试 40 tick，后台队列饱和重试 40 tick。 */
    private static final int REPLAN_TICKS=40,BUSY_TICKS=40;
    /** 全部不可变航路和圆快照；规划和每 tick 几何检查使用相同尺寸配置。 */
    private List<NavigationRoute.Point> route=List.of();
    private List<NavigationRoute.Circle> circles=List.of();
    private NavigationRoute.Settings settings;
    /** 主线程独占 Future；取消时立即丢弃，防止旧结果重新接管。 */
    private Future<NavigationPlanningService.Planned> pending;
    /** 失败后不重复大搜索，修改禁区或重新开启导航才再规划。 */
    private boolean attempted;
    /** 禁区快照版本及重试/地形采样时刻，单位服务端 tick；不进行网络同步。 */
    private long revision=-1,retryAt;
    /** 当前目标坐标，单位格；NaN只标记尚未初始化。 */
    private double goalX=Double.NaN,goalZ;
    /** 下一全局路线采样索引，交接续段时推进；侧绕期间不做全局最近点跳跃。 */
    private int node;
    /** 本机连续控制与只读全局轨迹；只在取消时清除控制器记忆。 */
    private final NavigationController controller = new NavigationController();
    private NavigationTrajectory trajectory;
    /** 当前航段、提前计算的续段和采集请求；飞行时持续推进续段，不在航段接缝重新悬停。 */
    private NavigationTerrainSnapshot terrainSnapshot;
    private Future<NavigationTerrainRoute.Result> terrainPending;
    private NavigationTerrainRoute.Result terrainRoute,terrainNext;
    private NavigationTerrainFollower terrainFollower;
    private NavigationChunkLoader chunkLoader;
    private int terrainGoalNode,planningGoalNode,nextGoalNode;
    /** 直达走廊确有障碍后才扩大绕行采集范围；成功或取消后重新启用快速直达探测。 */
    private boolean wideTerrain;
    /** 动态障碍持续四秒触发重规划；地形失败间隔五秒，安全失败最多重试两次，区块等待不计失败。 */
    private long terrainRetryAt,blockedAt=-1;
    private static final int TERRAIN_RETRY_TICKS=100;
    /** 同一地形安全失败最多重试两次；等待 FULL 区块不计次，成功或取消后清零。 */
    private static final int MAX_TERRAIN_SAFETY_RETRIES=2;
    /** 本轮安全失败已开始的重新采集次数，范围 0..2，仅服务端使用，不存档。 */
    private int terrainSafetyRetries;
    /** 安全重试是否仍在等待冷却和 FULL 区块；实际重新采集时清除，不进行网络同步。 */
    private boolean terrainRetryPending;
    /** 实时通道检查发现未知区块时保持制动；不将区块等待累计为真实障碍。 */
    private boolean waitingForChunks;
    /** 安全通道内十二秒无一格三维进展才重规划；同处最多两次，防止反复搜索同一路线。 */
    private static final int STALL_TICKS=240,MAX_STALL_REPLANS=2;
    private static final double PROGRESS_DISTANCE=1;
    /** 无进展计时及最近航段进度；新任务清零重试次数，单纯转向不能冒充前进。 */
    private long noProgressSince=-1;
    private double progressCheckpoint;
    private int stallReplans;
    private boolean stalled;
    /** 重复失败不每次刷新动作栏；新目标清除该诊断状态。 */
    private NavigationRoute.Status terrainFailure;
    /** 生命周期诊断状态；状态切换绝不清零实际速度，不进入存档和网络协议。 */
    public enum Phase { IDLE, PLANNING, READY, ACCELERATING, CRUISING, TURNING,
        DECELERATING, ARRIVING, COMPLETED, REPLANNING, FAILED, CANCELLED }
    /** 仅服务端维护的阶段，初始待机，取消后释放轨迹但保留诊断状态。 */
    private Phase phase = Phase.IDLE;
    /** 最近一次规划耗时与展开节点数，仅服务端诊断使用。 */
    private long planningNanos;
    private int expandedNodes;

    /** 功能：读取当前阶段供诊断；服务端，无 MC API，无副作用。 */
    public Phase phase() { return phase; }
    /** 功能：读取最近一次后台耗时，单位纳秒；无副作用。 */
    public long planningNanos() { return planningNanos; }
    /** 功能：读取最近一次全局展开节点数；无副作用。 */
    public int expandedNodes() { return expandedNodes; }
    /**
     * 功能：取消计算并丢弃临时路线，不改飞行速度和保存的导航点。
     * 引用 MC API：无。端侧/线程：服务端 tick 或实体移除回调。
     * 返回：无。副作用：中断纯算法。故障排查：旧路线复活时检查退出入口是否调用此方法。
     */
    public void reset(){
        if(pending!=null){NavigationPlanningService.cancel(pending);pending=null;}
        cancelTerrain();terrainRetryAt=0;terrainFailure=null;
        revision=-1;goalX=Double.NaN;route=List.of();circles=List.of();settings=null;
        node=0;retryAt=0;attempted=false;
        trajectory=null;controller.reset();phase=Phase.CANCELLED;
        noProgressSince=-1;progressCheckpoint=0;stallReplans=0;stalled=false;
    }

    /**
     * 功能：更新快照、非阻塞接收规划结果，并输出当前航段操纵意图。
     * 引用 MC API：net.minecraft.server.world.ServerWorld#getTime 用于限频；
     * net.minecraft.entity.Entity#getX/#getZ/#getWidth 制作纯数据快照。
     * 端侧/线程：仅服务端 tick。返回：无。副作用：更新导航缓存和 FlightState。
     * 故障排查：主线程停顿查 Future#get 是否仅在 isDone 后执行；失效查版本比较顺序。
     */
    public void step(HelicopterEntity h){
        var s=h.flight;var world=(ServerWorld)h.getWorld();var airspace=AirspaceState.get(world);
        var pos=new NavigationRoute.Point(h.getX(),h.getZ());var goal=new NavigationRoute.Point(s.waypointX,s.waypointZ);
        HeliConfig config=HeliConfig.INSTANCE;
        // 用稳定的实体尺寸；平移后 AABB 两端相减的浮点噪声不能触发整条航路失效。
        double halfWidth=h.getWidth()/2.0;
        var currentSettings=new NavigationRoute.Settings(config.gridCellSize,
                Math.hypot(halfWidth,halfWidth),
                config.pathSafetyMargin,config.pathBoundaryMargin,config.pathMaxCells,config.pathMaxNodes);
        boolean newTarget=goalX!=goal.x()||goalZ!=goal.z()||!currentSettings.equals(settings);
        boolean changed=newTarget||revision!=airspace.revision();
        if(changed){
            cancelTerrain();terrainRetryAt=0;
            noProgressSince=-1;stallReplans=0;stalled=false;
            if(newTarget) reset();
            else if(pending!=null){NavigationPlanningService.cancel(pending);pending=null;}
            revision=airspace.revision();goalX=goal.x();goalZ=goal.z();settings=currentSettings;
            circles=airspace.zones().stream().map(z->new NavigationRoute.Circle(z.pos().getX()+.5,z.pos().getZ()+.5,z.radius())).toList();
            attempted=false;phase=route.isEmpty()?Phase.PLANNING:Phase.REPLANNING;
            notify(h,"route_planning");
        }
        receive(h);
        if(!s.autopilot)return;
        long now=world.getTime();
        if(pending==null&&!attempted&&now>=retryAt
                &&(!route.isEmpty()||Math.hypot(s.vx,s.vz)<=PLAN_SPEED))request(h,pos,goal,now);
        if(route.isEmpty()){
            controller.step(s,h.getX(),h.getZ(),pos,0,0,0);
            return;
        }
        follow(h,world,pos,goal);
    }

    /**
     * 功能：提交坐标快照，队列饱和时限频重试并继续悬停。
     * 引用 MC API：无新增。端侧/线程：服务端 tick。返回：无。
     * 副作用：保存 Future 与重试时刻。故障排查：一直等待查速度与队列，禁止在此同步搜索。
     */
    private void request(HelicopterEntity h,NavigationRoute.Point pos,NavigationRoute.Point goal,long now){
        try{
            pending=NavigationPlanningService.submit(pos,goal,circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0,trajectory,node);attempted=true;retryAt=now+REPLAN_TICKS;
            phase=route.isEmpty()?Phase.PLANNING:Phase.REPLANNING;
        }catch(RejectedExecutionException e){
            retryAt=now+BUSY_TICKS;notify(h,"route_busy");
            No_24_heli.LOGGER.warn("导航规划队列已满：飞机={}，40 tick 后重试",h.getUuid());
        }
    }

    /**
     * 功能：仅读取已完成的结果，在主线程设置航路并记录状态与规模。
     * 引用 MC API：net.minecraft.entity.Entity#getUuid 标识日志中的飞机。
     * 端侧/线程：服务端 tick。返回：无。副作用：设置路线或失败悬停状态。
     * 故障排查：失败查状态及异常日志，不可达不得回退为终点直线飞行。
     */
    private void receive(HelicopterEntity h){
        if(pending==null||!pending.isDone())return;
        var completed=pending;pending=null;
        try{
            var planned=completed.get();var result=planned.route();
            planningNanos=planned.elapsedNanos();expandedNodes=result.expandedNodes();
            No_24_heli.LOGGER.info("导航规划：飞机={}，状态={}，搜索节点={}，路径点={}，起点封闭={}，终点封闭={}，格大小={}，耗时ms={}，复用={}",
                    h.getUuid(),result.status(),result.expandedNodes(),result.path().size(),result.startEnclosed(),result.goalEnclosed(),result.cellSize(),planningNanos/1_000_000.0,planned.reused());
            if(result.status()==NavigationRoute.Status.SUCCESS){
                if(!planned.reused()){
                    trajectory=planned.trajectory();route=trajectory.points();node=1;
                }
                phase=Phase.PLANNING;notify(h,"route_terrain_planning");
            }else if(result.status()==NavigationRoute.Status.ALREADY_ARRIVED){
                if(Math.hypot(h.getX()-goalX,h.getZ()-goalZ)<ARRIVAL_DISTANCE
                        &&Math.hypot(h.flight.vx,h.flight.vz)<ARRIVAL_SPEED){
                    h.finishAutopilot();phase=Phase.COMPLETED;notify(h,"route_arrived");
                }else {attempted=false;phase=Phase.ARRIVING;}
            }else {route=List.of();trajectory=null;phase=Phase.FAILED;notify(h,messageKey(result.status()));}
        }catch(CancellationException e){phase=Phase.FAILED;notify(h,"route_interrupted");
        }catch(InterruptedException e){
            phase=Phase.FAILED;Thread.currentThread().interrupt();notify(h,"route_interrupted");
            No_24_heli.LOGGER.warn("导航结果接收被中断：飞机={}",h.getUuid(),e);
        }catch(ExecutionException e){
            phase=Phase.FAILED;notify(h,"route_failed");No_24_heli.LOGGER.error("导航后台规划失败：飞机={}",h.getUuid(),e.getCause());
        }
    }

    /** 逐项对应双语提示，分别说明资源限制、精度不足与封闭原因。 */
    private static String messageKey(NavigationRoute.Status status){
        return switch(status){
            case SUCCESS -> "route_success";
            case ALREADY_ARRIVED -> "route_arrived";
            case START_FORBIDDEN -> "route_start_forbidden";
            case GOAL_FORBIDDEN -> "route_goal_forbidden";
            case START_AND_GOAL_FORBIDDEN -> "route_both_forbidden";
            case START_ENCLOSED -> "route_start_enclosed";
            case GOAL_ENCLOSED -> "route_goal_enclosed";
            case DIFFERENT_ENCLOSED_REGIONS -> "route_different_enclosed";
            case GRID_RESOLUTION -> "route_grid_resolution";
            case CELL_LIMIT -> "route_cell_limit";
            case NODE_LIMIT -> "route_node_limit";
            case GEOMETRY_LIMIT -> "route_geometry_limit";
            case INTERRUPTED -> "route_interrupted";
            case INVALID_INPUT -> "route_invalid_input";
            case TRAJECTORY_UNSAFE -> "route_trajectory_unsafe";
        };
    }

    /** 功能：取消采集、搜索和跟随，释放 ServerChunkManager 的本机临时票据，不改变真实速度。 */
    private void cancelTerrain(){
        if(terrainPending!=null){NavigationPlanningService.cancel(terrainPending);terrainPending=null;}
        if(chunkLoader!=null){chunkLoader.close();chunkLoader=null;}
        terrainSnapshot=null;terrainRoute=null;terrainNext=null;terrainFollower=null;wideTerrain=false;blockedAt=-1;
        terrainSafetyRetries=0;terrainRetryPending=false;terrainFailure=null;waitingForChunks=false;
    }

    /**
     * 功能：主动加载规划区域，飞行期间提前采集和计算续段，非阻塞接收三维路线。
     * MC API：net.minecraft.server.world.ServerWorld#getTime 限频；其余世界读取仅采集器主线程执行。
     * 返回：是否已有可用路线；副作用：更新 Future 与规划诊断，不修改实体位置或速度。
     * 排查：地图设点或禁区变化须同时取消两级 Future；局部失败不能退回无地形直飞。
     */
    private boolean prepareTerrain(HelicopterEntity h,ServerWorld world){
        long now=world.getTime();
        if(chunkLoader==null)chunkLoader=new NavigationChunkLoader(world);
        if(terrainPending!=null&&terrainPending.isDone()){
            var completed=terrainPending;terrainPending=null;
            try{
                var result=completed.get();expandedNodes+=result.expanded();
                if(result.status()==NavigationRoute.Status.SUCCESS&&result.path().size()>1
                        &&(terrainRoute!=null||result.volume().clear(terrainPosition(h),result.path().getFirst()))){
                    if(terrainRoute==null)useTerrain(result,planningGoalNode);
                    else {terrainNext=result;nextGoalNode=planningGoalNode;}
                    terrainFailure=null;terrainSnapshot=null;wideTerrain=false;terrainSafetyRetries=0;
                    terrainRetryPending=false;terrainRetryAt=0;retainChunks();phase=Phase.READY;
                    No_24_heli.LOGGER.info("三维地形规划：飞机={}，节点={}，航点={}，风险加权tick={}",
                            h.getUuid(),result.expanded(),result.path().size(),result.costTicks());
                    if(terrainNext==null)notify(h,"route_success");
                }else if(terrainSnapshot.directOnly&&result.status()==NavigationRoute.Status.GRID_RESOLUTION){
                    wideTerrain=true;terrainSnapshot=null;retainChunks();
                }else {terrainFailed(h,result.status()==NavigationRoute.Status.SUCCESS
                        ?NavigationRoute.Status.TRAJECTORY_UNSAFE:result.status(),now);
                }
            }catch(CancellationException e){terrainSnapshot=null;retainChunks();terrainFailed(h,NavigationRoute.Status.INTERRUPTED,now);
            }catch(InterruptedException e){
                terrainSnapshot=null;retainChunks();Thread.currentThread().interrupt();terrainFailed(h,NavigationRoute.Status.INTERRUPTED,now);
            }catch(ExecutionException e){
                No_24_heli.LOGGER.error("三维地形规划失败：飞机={}",h.getUuid(),e.getCause());
                terrainFailed(h,NavigationRoute.Status.TRAJECTORY_UNSAFE,now);
            }
        }
        if(terrainRetryPending){
            chunkLoader.step();
            if(now<terrainRetryAt||!chunkLoader.ready())return terrainRoute!=null;
            // 保留失败采集区的票据直到加载完成；真正重试时丢弃旧体素，从当前机位重新采集。
            terrainSafetyRetries++;terrainRetryPending=false;terrainSnapshot=null;
            notify(h,"route_terrain_retry");
            No_24_heli.LOGGER.info("三维地形安全重试：飞机={}，次数={}/{}",h.getUuid(),
                    terrainSafetyRetries,MAX_TERRAIN_SAFETY_RETRIES);
        }
        if(pending!=null||terrainPending!=null||terrainNext!=null||now<terrainRetryAt
                ||terrainRoute!=null&&terrainGoalNode==route.size()-1){chunkLoader.step();return terrainRoute!=null;}
        if(terrainSnapshot==null){
            var start=terrainRoute==null?terrainPosition(h):terrainRoute.path().getLast();
            var from=start.horizontal();double distance=0;
            int first=Math.min(terrainRoute==null?node:terrainGoalNode+1,route.size()-1);
            planningGoalNode=first;
            for(int at=first;at<route.size();at++){
                double segment=NavigationRoute.distance(from,route.get(at));
                if(distance+segment>TERRAIN_LOOK&&at>first)break;
                distance+=segment;from=route.get(at);planningGoalNode=at;
            }
            terrainSnapshot=new NavigationTerrainSnapshot(world,start,route.get(planningGoalNode),
                    circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0,!wideTerrain);
            // 超出体素预算时缩短一次采集范围，区块加载距离不再限制整条导航任务。
            while(terrainSnapshot.tooLarge()&&planningGoalNode>first){
                planningGoalNode--;
                terrainSnapshot=new NavigationTerrainSnapshot(world,start,route.get(planningGoalNode),
                        circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0,!wideTerrain);
            }
            if(terrainSnapshot.tooLarge()){
                terrainSnapshot=null;retainChunks();terrainFailed(h,NavigationRoute.Status.CELL_LIMIT,now);return terrainRoute!=null;
            }
            retainChunks();if(terrainRoute==null)phase=Phase.PLANNING;
        }
        chunkLoader.step();
        terrainSnapshot.step(world,h);
        if(terrainSnapshot.complete()){
            try{
                terrainPending=NavigationPlanningService.submitTerrain(terrainSnapshot);
            }catch(RejectedExecutionException e){terrainRetryAt=now+BUSY_TICKS;notify(h,"route_busy");}
        }
        return terrainRoute!=null;
    }

    /** 功能：交接已完成航段并预计算连续速度包络，不重置真实速度或导航控制器。 */
    private void useTerrain(NavigationTerrainRoute.Result result,int goalNode){
        terrainRoute=result;terrainGoalNode=goalNode;
        terrainFollower=new NavigationTerrainFollower();
        terrainFollower.bind(result,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0);
        noProgressSince=-1;progressCheckpoint=0;
    }

    /** 功能：仅保留当前、续段及在途采集的区块票；主线程调用，已飞过的区块可以正常卸载。 */
    private void retainChunks(){
        chunkLoader.retain(terrainRoute,terrainNext,terrainSnapshot);
    }

    /**
     * 功能：安全失败保留采集区票据，加载齐全并等待 100 tick 后最多重新采集两次；资源失败沿用限频。
     * MC API：无新增；仅服务端主线程。副作用：保存重试状态，耗尽后保留已验证航段并在末端悬停。
     * 排查：等待加载不计次数，不能立即释放票据或重复提交失败快照；新目标/空域变化会清零。
     */
    private void terrainFailed(HelicopterEntity h,NavigationRoute.Status status,long now){
        boolean safety=status==NavigationRoute.Status.GRID_RESOLUTION||status==NavigationRoute.Status.TRAJECTORY_UNSAFE;
        terrainRetryPending=safety&&terrainSafetyRetries<MAX_TERRAIN_SAFETY_RETRIES;
        terrainRetryAt=safety&&!terrainRetryPending?Long.MAX_VALUE:now+TERRAIN_RETRY_TICKS;
        phase=terrainRetryPending?Phase.REPLANNING:Phase.FAILED;
        if(!safety)terrainSnapshot=null;
        retainChunks();
        if(terrainFailure!=status||safety){
            notify(h,switch(status){
                case GRID_RESOLUTION,TRAJECTORY_UNSAFE -> terrainRetryPending?"route_terrain_wait":"route_terrain_stopped";
                case NODE_LIMIT -> "route_terrain_limit";
                default -> messageKey(status);
            });terrainFailure=status;
            No_24_heli.LOGGER.info("三维地形规划等待：飞机={}，状态={}",h.getUuid(),status);}
    }

    /** 功能：获取实体原点的纯三维快照；MC API：net.minecraft.entity.Entity#getX/#getY/#getZ；仅主线程。 */
    private static NavigationTerrainRoute.Point terrainPosition(HelicopterEntity h){
        return new NavigationTerrainRoute.Point(h.getX(),h.getY(),h.getZ());
    }

    /**
     * 功能：连续前视跟随已验证的三维轨迹，提前交接续段，只有最终点或真实障碍要求停稳。
     * MC API：ServerWorld 与 net.minecraft.util.math.Box 复查当前完整机体及惯性通道；仅服务端 tick。
     * 返回：无；副作用仅正常操纵输入与目标高度，不修改手动飞行、碰撞或实际速度。
     * 排查：转弯速度查曲率包络；接缝等待查续段加载；持续障碍才触发重新采集。
     */
    private void follow(HelicopterEntity h,ServerWorld world,NavigationRoute.Point pos,NavigationRoute.Point goal){
        var s=h.flight;double speed=Math.hypot(s.vx,s.vz);
        if(stalled){
            s.targetAltitude=h.getY();controller.step(s,h.getX(),h.getZ(),pos,0,0,0);return;
        }
        if(!prepareTerrain(h,world)){
            // 等待重规划时同时捕获当前高度，不能继续追逐旧航段留下的爬升或下降目标。
            s.targetAltitude=h.getY();
            controller.step(s,h.getX(),h.getZ(),pos,0,0,0);return;
        }
        var here=terrainPosition(h);
        if(terrainNext!=null&&terrainFollower.atEnd(here,speed)){
            node=terrainGoalNode+1;useTerrain(terrainNext,nextGoalNode);terrainNext=null;
            retainChunks();prepareTerrain(h,world);
        }
        double distance=NavigationRoute.distance(pos,goal);
        if(terrainGoalNode==route.size()-1&&distance<ARRIVAL_DISTANCE
                &&Math.abs(h.getY()-terrainRoute.path().getLast().y())<.6&&speed<ARRIVAL_SPEED&&Math.abs(s.vy)<.02){
            h.finishAutopilot();phase=Phase.COMPLETED;notify(h,"route_arrived");return;
        }
        var sample=terrainFollower.sample(here,speed,HeliConfig.INSTANCE.autopilotLookMin,
                HeliConfig.INSTANCE.autopilotLookMax,terrainNext);
        var target=sample.target();
        double desired=sample.altitude(),limit=sample.speed();
        double horizontal=NavigationRoute.distance(pos,target.horizontal()),vertical=Math.abs(target.y()-h.getY());
        if(vertical>1)limit=Math.min(limit,horizontal*(target.y()>h.getY()?NavigationTerrainRoute.CLIMB_SPEED
                :NavigationTerrainRoute.DESCENT_SPEED)/vertical);
        // 前视可跨入续段，其净空和顶棚限速已由跟随器联合两份快照验证，不能再用旧快照越界降速。
        // 所有方向采用整机旋转包络，连未来尾梁转动也必须有空间，不依赖当前机头朝向。
        double radius=NavigationTerrainRoute.ENVELOPE;
        Box base=new Box(h.getX()-radius,h.getY()-radius,h.getZ()-radius,
                h.getX()+radius,h.getY()+radius,h.getZ()+radius);
        Vec3d ahead=new Vec3d(target.x()-h.getX(),target.y()-h.getY(),target.z()-h.getZ());
        double stopTime=speed/(2*BRAKE_ACCELERATION)+BRAKE_RESPONSE;
        boolean wasWaiting=waitingForChunks;waitingForChunks=false;chunkLoader.beginChecks();
        boolean forwardClear=corridorClear(world,h,base,ahead)&&sample.safe();
        boolean driftClear=corridorClear(world,h,base,new Vec3d(s.vx*stopTime,s.vy*30,s.vz*stopTime));
        // 坡线尚未对准时可先安全垂直升降到本段高度；前方不通不能同时冻结解围所需的爬升。
        if(!forwardClear&&vertical>.6&&corridorClear(world,h,base,new Vec3d(0,target.y()-h.getY(),0)))desired=target.y();
        boolean altitudeClear=corridorClear(world,h,base,new Vec3d(0,desired-h.getY(),0));
        if(waitingForChunks||wasWaiting)retainChunks();
        chunkLoader.step();
        if(waitingForChunks&&!wasWaiting)notify(h,"route_chunk_wait");
        if(!waitingForChunks&&driftClear&&altitudeClear){
            s.targetAltitude+=Math.clamp(desired-s.targetAltitude,-CLIMB_STEP,CLIMB_STEP);
        }else s.targetAltitude=h.getY();
        boolean adjusting=!waitingForChunks&&driftClear&&altitudeClear&&Math.abs(desired-h.getY())>.6;
        if(forwardClear&&driftClear&&altitudeClear)blockedAt=-1;
        else{
            limit=0;
            if(adjusting||waitingForChunks)blockedAt=-1;
            else{
                if(blockedAt<0)blockedAt=world.getTime();
                // 短暂横穿的实体只减速等待，连续四秒确实堵塞才丢弃已计算的后续路线。
                if(world.getTime()-blockedAt>=REPLAN_TICKS*2&&!terrainRetryPending&&terrainRetryAt!=Long.MAX_VALUE){
                    cancelTerrain();terrainRetryAt=world.getTime()+REPLAN_TICKS;phase=Phase.REPLANNING;
                }
            }
        }
        limit=Math.min(limit,NavigationRoute.speedLimit(pos,s.vx,s.vz,circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0));
        // 最终停车按真实水平距离限速，三维剩余弧长中的升降距离不能延迟水平制动。
        double remaining=terrainGoalNode==route.size()-1?Math.min(sample.remaining(),distance):sample.remaining();
        // 最终点越过后仍保留低速返回权限，续段接缝不作为到点减速目标。
        if(terrainGoalNode==route.size()-1&&remaining<4){remaining=Math.max(remaining,distance);limit=Math.max(limit,.12);}
        if(!forwardClear||!driftClear||!altitudeClear)limit=0;
        if(replanIfStalled(h,world,forwardClear&&driftClear&&altitudeClear&&!waitingForChunks)){
            s.targetAltitude=h.getY();controller.step(s,h.getX(),h.getZ(),pos,0,0,0);return;
        }
        controller.step(s,h.getX(),h.getZ(),target.horizontal(),limit,remaining,sample.curvature());
        if(terrainRoute==null||waitingForChunks||terrainRetryPending)phase=Phase.REPLANNING;
        else if(terrainRetryAt==Long.MAX_VALUE)phase=Phase.FAILED;
        else if(terrainGoalNode==route.size()-1&&distance<8)phase=Phase.ARRIVING;
        else if(limit<speed-.03)phase=Phase.DECELERATING;
        else if(controller.commandedSpeed()>speed+.02)phase=Phase.ACCELERATING;
        else if(Math.abs(sample.curvature())>1e-4)phase=Phase.TURNING;
        else phase=Phase.CRUISING;
    }
    /**
     * 功能：整段通道安全却长期无三维航路进展时，从实际机位重规划，耗尽后明确提示并制动悬停。
     * 引用 net.minecraft.server.world.ServerWorld#getTime 使用固定 tick；服务端主线程，无直接位移。
     * 区块等待、真实堵塞及已耗尽的地形重试不累计；重规划次数只在确实前进一格或新任务时重置。
     * 排查：原地旋转不推进弧长；垂直升降可推进，不能仅用水平速度误判正常爬升。
     */
    private boolean replanIfStalled(HelicopterEntity h,ServerWorld world,boolean clear){
        if(terrainRoute==null||terrainRetryPending||terrainRetryAt==Long.MAX_VALUE||!clear
                ||terrainNext==null&&terrainGoalNode<route.size()-1
                &&terrainFollower.atEnd(terrainPosition(h),Math.hypot(h.flight.vx,h.flight.vz))){
            noProgressSince=-1;return false;
        }
        double progress=terrainFollower.progress();long now=world.getTime();
        if(noProgressSince<0||progress-progressCheckpoint>=PROGRESS_DISTANCE){
            if(progress-progressCheckpoint>=PROGRESS_DISTANCE)stallReplans=0;
            progressCheckpoint=progress;noProgressSince=now;return false;
        }
        if(now-noProgressSince<STALL_TICKS)return false;
        if(stallReplans>=MAX_STALL_REPLANS){
            cancelTerrain();stalled=true;phase=Phase.FAILED;notify(h,"route_stalled_stopped");return true;
        }
        stallReplans++;cancelTerrain();terrainRetryAt=now+REPLAN_TICKS;
        phase=Phase.REPLANNING;notify(h,"route_stalled_retry");return true;
    }
    /** 小段扫掠避免斜向航线的大包围盒把旁侧障碍误报为整条通道堵塞。 */
    private boolean corridorClear(ServerWorld world,HelicopterEntity h,Box base,Vec3d delta){
        int steps=Math.max(1,(int)Math.ceil(delta.length()/4));Vec3d part=delta.multiply(1.0/steps);
        for(int i=0;i<steps;i++)if(!clear(world,h,base.offset(part.multiply(i)).stretch(part)))return false;
        return true;
    }
    /**
     * 功能：先异步请求未知区块，加载完成后再检查真实方块、流体与动态实体净空。
     * 引用 MC API：net.minecraft.server.world.ServerWorld#getBlockCollisions/#getOtherEntities
     * 检查方块与实体；net.minecraft.world.border.WorldBorder#contains 检查世界边界。
     * 端侧/线程：服务端 tick。返回：整个通道可用时为 true。副作用：登记加载请求及等待状态。
     * 故障排查：世界边缘或未知地形停车查加载范围，禁止后台线程调用此方法。
     */
    private boolean clear(ServerWorld world,HelicopterEntity h,Box box){
        if(!chunkLoader.load(box)){waitingForChunks=true;return false;}
        if(box.minY<world.getBottomY()||box.maxY>world.getTopY()||!world.getWorldBorder().contains(box)
                ||world.getBlockCollisions(h,box).iterator().hasNext()||world.containsFluid(box))return false;
        return world.getOtherEntities(h,box,e->!h.isConnectedThroughVehicle(e)&&!(e instanceof PlayerEntity)&&!(e instanceof RopeSeatEntity)
                &&!(e.getRootVehicle() instanceof RopeSeatEntity)&&!e.isSpectator()
                &&(e.isCollidable()||e instanceof net.minecraft.entity.LivingEntity||e instanceof HelicopterEntity)).isEmpty();
    }
    /** 功能：通过 net.minecraft.entity.player.PlayerEntity#sendMessage 向当前驾驶员显示双语动作栏；仅服务端调用。 */
    private static void notify(HelicopterEntity h,String key){if(h.getFirstPassenger() instanceof PlayerEntity p)p.sendMessage(net.minecraft.text.Text.translatable("message.no_24_heli."+key),true);}
}
