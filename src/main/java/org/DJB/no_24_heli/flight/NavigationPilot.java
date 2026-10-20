package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.AirspaceState;
import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;


import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 文件职责：编排禁区全局路线与地形三维局部路线，按飞行时间和顶棚风险选择升降及绕行。
 * 端侧：SERVER；关联通道：no_24_heli:navigation、no_24_heli:controls。
 * 依赖 MC API：net.minecraft.server.world.ServerWorld 提供地形和持久状态；
 * net.minecraft.entity.Entity#getWidth 提供稳定机体尺寸；net.minecraft.entity.player.PlayerEntity 接收提示。
 * 生命周期：每架飞机持有一个实例，由服务端 tick 调用，取消或移除时 reset。
 * 线程：世界和实体仅在 tick 访问；工作线程只计算不可变坐标，不回调实体。
 * 故障排查：旧路线恢复查 pending 的取消；卡顿查 Future 是否阻塞；实际切角查惯性通道限速。
 */
public final class NavigationPilot {
    /** 每次沿全局路线取最多 256 格局部目标，四侧另留搜索空间；高度目标每 tick 最多变化 0.10 格。 */
    private static final double TERRAIN_LOOK=256,CLIMB_STEP=.10;
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
    /** 下一个全局路线采样索引，只有到达对应局部终点后才推进，侧绕期间不做全局最近点跳跃。 */
    private int node;
    /** 本机连续控制与只读全局轨迹；只在取消时清除控制器记忆。 */
    private final NavigationController controller = new NavigationController();
    private NavigationTrajectory trajectory;
    /** 地形采集、后台结果和跟随段状态；全部由服务端主线程独占管理，取消时一起失效。 */
    private NavigationTerrainSnapshot terrainSnapshot;
    private Future<NavigationTerrainRoute.Result> terrainPending;
    private NavigationTerrainRoute.Result terrainRoute;
    private int terrainNode,terrainGoalNode;
    /** 动态障碍持续两秒触发重规划；采集失败五秒后重试以等待新区块或障碍变化。 */
    private long terrainRetryAt,blockedAt=-1;
    private static final int TERRAIN_RETRY_TICKS=100;
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

    /** 功能：同时取消地形采集、搜索和跟随；无 MC API，不改变飞机真实速度。 */
    private void cancelTerrain(){
        if(terrainPending!=null){NavigationPlanningService.cancel(terrainPending);terrainPending=null;}
        terrainSnapshot=null;terrainRoute=null;terrainNode=0;blockedAt=-1;
    }

    /**
     * 功能：选择全局路线前方局部终点，停稳后分片采集，非阻塞提交及接收三维路线。
     * MC API：net.minecraft.server.world.ServerWorld#getTime 限频；其余世界读取仅采集器主线程执行。
     * 返回：是否已有可用路线；副作用：更新 Future 与规划诊断，不修改实体位置或速度。
     * 排查：地图设点或禁区变化须同时取消两级 Future；局部失败不能退回无地形直飞。
     */
    private boolean prepareTerrain(HelicopterEntity h,ServerWorld world){
        if(terrainRoute!=null)return true;
        long now=world.getTime();
        if(terrainPending!=null&&terrainPending.isDone()){
            var completed=terrainPending;terrainPending=null;
            try{
                var result=completed.get();expandedNodes+=result.expanded();
                if(result.status()==NavigationRoute.Status.SUCCESS&&result.path().size()>1
                        &&result.volume().clear(terrainPosition(h),result.path().getFirst())){
                    terrainRoute=result;terrainNode=1;terrainFailure=null;phase=Phase.READY;
                    No_24_heli.LOGGER.info("三维地形规划：飞机={}，节点={}，航点={}，风险加权tick={}",
                            h.getUuid(),result.expanded(),result.path().size(),result.costTicks());
                    notify(h,"route_success");return true;
                }
                terrainFailed(h,result.status()==NavigationRoute.Status.SUCCESS
                        ?NavigationRoute.Status.TRAJECTORY_UNSAFE:result.status(),now);
            }catch(CancellationException e){terrainFailed(h,NavigationRoute.Status.INTERRUPTED,now);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();terrainFailed(h,NavigationRoute.Status.INTERRUPTED,now);
            }catch(ExecutionException e){
                No_24_heli.LOGGER.error("三维地形规划失败：飞机={}",h.getUuid(),e.getCause());
                terrainFailed(h,NavigationRoute.Status.TRAJECTORY_UNSAFE,now);
            }
        }
        if(pending!=null||terrainPending!=null||now<terrainRetryAt)return false;
        if(terrainSnapshot==null){
            if(Math.hypot(h.flight.vx,h.flight.vz)>PLAN_SPEED||Math.abs(h.flight.vy)>.02)return false;
            var from=new NavigationRoute.Point(h.getX(),h.getZ());double distance=0;
            terrainGoalNode=Math.min(node,route.size()-1);
            for(int at=terrainGoalNode;at<route.size();at++){
                double segment=NavigationRoute.distance(from,route.get(at));
                if(distance+segment>TERRAIN_LOOK&&at>node)break;
                if(!loadedAround(world,route.get(at)))break;
                distance+=segment;from=route.get(at);terrainGoalNode=at;
            }
            if(!loadedAround(world,route.get(terrainGoalNode))){
                terrainFailed(h,NavigationRoute.Status.GRID_RESOLUTION,now);return false;
            }
            terrainSnapshot=new NavigationTerrainSnapshot(world,terrainPosition(h),route.get(terrainGoalNode),
                    circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0);
            if(terrainSnapshot.tooLarge()){
                terrainSnapshot=null;terrainFailed(h,NavigationRoute.Status.CELL_LIMIT,now);return false;
            }
            phase=Phase.PLANNING;
        }
        terrainSnapshot.step(world,h);
        if(terrainSnapshot.complete()){
            try{
                terrainPending=NavigationPlanningService.submitTerrain(terrainSnapshot);terrainSnapshot=null;
            }catch(RejectedExecutionException e){terrainRetryAt=now+BUSY_TICKS;notify(h,"route_busy");}
        }
        return false;
    }

    /** 功能：记录局部精度或资源失败，等待已加载世界变化后重试；不宣称整个空间不可达。 */
    private void terrainFailed(HelicopterEntity h,NavigationRoute.Status status,long now){
        terrainRetryAt=now+TERRAIN_RETRY_TICKS;phase=Phase.FAILED;
        if(terrainFailure!=status){
            notify(h,switch(status){
                case GRID_RESOLUTION,TRAJECTORY_UNSAFE -> "route_terrain_wait";
                case NODE_LIMIT -> "route_terrain_limit";
                default -> messageKey(status);
            });terrainFailure=status;
            No_24_heli.LOGGER.info("三维地形规划等待：飞机={}，状态={}",h.getUuid(),status);}
    }

    /** 功能：获取实体原点的纯三维快照；MC API：net.minecraft.entity.Entity#getX/#getY/#getZ；仅主线程。 */
    private static NavigationTerrainRoute.Point terrainPosition(HelicopterEntity h){
        return new NavigationTerrainRoute.Point(h.getX(),h.getY(),h.getZ());
    }

    /** 功能：局部终点只取整机邻域均已加载的位置；MC API：区块管理器 isChunkLoaded，不强制加载远方区块。 */
    private static boolean loadedAround(ServerWorld world,NavigationRoute.Point point){
        double radius=NavigationTerrainRoute.ENVELOPE+NavigationTerrainRoute.CELL;
        for(int x=MathHelper.floor(point.x()-radius)>>4;x<=MathHelper.floor(point.x()+radius)>>4;x++)
            for(int z=MathHelper.floor(point.z()-radius)>>4;z<=MathHelper.floor(point.z()+radius)>>4;z++)
                if(!world.getChunkManager().isChunkLoaded(x,z))return false;
        return true;
    }

    /**
     * 功能：执行已验证的三维折线，升降和水平速度联动，顶棚通道限速且不追逐山顶高度。
     * MC API：ServerWorld 与 net.minecraft.util.math.Box 复查当前完整机体及惯性通道；仅服务端 tick。
     * 返回：无；副作用仅正常操纵输入与目标高度，不修改手动飞行、碰撞或实际速度。
     * 排查：转角先减速再换段；高度偏差先修正再前进；动态障碍持续存在则停车重新采集。
     */
    private void follow(HelicopterEntity h,ServerWorld world,NavigationRoute.Point pos,NavigationRoute.Point goal){
        var s=h.flight;double speed=Math.hypot(s.vx,s.vz);
        if(!prepareTerrain(h,world)){
            controller.step(s,h.getX(),h.getZ(),pos,0,0,0);return;
        }
        var points=terrainRoute.path();var here=terrainPosition(h);
        var a=points.get(terrainNode-1);var b=points.get(terrainNode);
        double distance=NavigationRoute.distance(pos,b.horizontal());
        if(distance<ARRIVAL_DISTANCE&&Math.abs(h.getY()-b.y())<.6&&speed<.08){
            if(++terrainNode>=points.size()){
                if(terrainGoalNode==route.size()-1&&NavigationRoute.distance(pos,goal)<ARRIVAL_DISTANCE
                        &&speed<ARRIVAL_SPEED&&Math.abs(s.vy)<.02){
                    h.finishAutopilot();phase=Phase.COMPLETED;notify(h,"route_arrived");return;
                }
                if(terrainGoalNode==route.size()-1){terrainNode--;}
                else {node=terrainGoalNode+1;cancelTerrain();s.targetAltitude=h.getY();
                    controller.step(s,h.getX(),h.getZ(),pos,0,0,0);return;}
            }else {a=points.get(terrainNode-1);b=points.get(terrainNode);distance=NavigationRoute.distance(pos,b.horizontal());}
        }
        double dx=b.x()-a.x(),dz=b.z()-a.z(),length=Math.hypot(dx,dz);
        double fraction=length<1e-6?1:Math.clamp(((h.getX()-a.x())*dx+(h.getZ()-a.z())*dz)/(length*length),0,1);
        double expected=a.y()+(b.y()-a.y())*fraction;
        // 原高度闭环增益 .025，需要约四十 tick 的前馈；只在本段高度范围内提前升降。
        double lead=length<1e-6?b.y():expected+(b.y()-a.y())/length*Math.max(.08,speed)*40;
        double desired=Math.clamp(lead,Math.min(a.y(),b.y()),Math.max(a.y(),b.y()));
        double limit=terrainRoute.volume().speed(here,b);
        double vertical=Math.abs(b.y()-h.getY());
        if(vertical>1)limit=Math.min(limit,distance*(b.y()>h.getY()?NavigationTerrainRoute.CLIMB_SPEED
                :NavigationTerrainRoute.DESCENT_SPEED)/(vertical+4));
        // 只在高度进度落后时等候；已提前爬升或下降时不能又拉回旧坡线造成往复。
        if(length>1e-6&&((b.y()>a.y()&&h.getY()<expected-1)
                ||(b.y()<a.y()&&h.getY()>expected+1)))limit=0;
        if(b.y()>a.y())desired=Math.max(desired,Math.min(h.getY(),b.y()));
        else if(b.y()<a.y())desired=Math.min(desired,Math.max(h.getY(),b.y()));
        if(distance<.5)desired=b.y();
        // 所有方向采用整机旋转包络，连未来尾梁转动也必须有空间，不依赖当前机头朝向。
        double radius=NavigationTerrainRoute.ENVELOPE;
        Box base=new Box(h.getX()-radius,h.getY()-radius,h.getZ()-radius,
                h.getX()+radius,h.getY()+radius,h.getZ()+radius);
        double look=Math.min(here.distance(b),Math.max(16,speed*60+12));
        double factor=here.distance(b)<1e-6?0:look/here.distance(b);
        Vec3d ahead=new Vec3d((b.x()-h.getX())*factor,(b.y()-h.getY())*factor,(b.z()-h.getZ())*factor);
        double stopTime=speed/(2*BRAKE_ACCELERATION)+BRAKE_RESPONSE;
        boolean forwardClear=terrainRoute.volume().clear(here,b)&&corridorClear(world,h,base,ahead);
        boolean driftClear=corridorClear(world,h,base,new Vec3d(s.vx*stopTime,s.vy*30,s.vz*stopTime));
        // 坡线尚未对准时可先安全垂直升降到本段高度；前方不通不能同时冻结解围所需的爬升。
        if(!forwardClear&&vertical>.6&&corridorClear(world,h,base,new Vec3d(0,b.y()-h.getY(),0)))desired=b.y();
        boolean altitudeClear=corridorClear(world,h,base,new Vec3d(0,desired-h.getY(),0));
        if(driftClear&&altitudeClear){
            s.targetAltitude+=Math.clamp(desired-s.targetAltitude,-CLIMB_STEP,CLIMB_STEP);
        }else s.targetAltitude=h.getY();
        boolean adjusting=driftClear&&altitudeClear&&Math.abs(desired-h.getY())>.6;
        if(forwardClear&&driftClear&&altitudeClear)blockedAt=-1;
        else{
            limit=0;
            if(adjusting)blockedAt=-1;
            else{
                if(blockedAt<0)blockedAt=world.getTime();
                if(world.getTime()-blockedAt>=REPLAN_TICKS){
                    cancelTerrain();terrainRetryAt=world.getTime()+REPLAN_TICKS;phase=Phase.REPLANNING;
                }
            }
        }
        limit=Math.min(limit,NavigationRoute.speedLimit(pos,s.vx,s.vz,circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0));
        // 每段末端按剩余距离制动，禁止未验证的 Bezier 切角穿过洞壁；真实惯性由原控制器消除。
        controller.step(s,h.getX(),h.getZ(),b.horizontal(),limit,distance,0);
        if(terrainRoute==null)phase=Phase.REPLANNING;
        else if(terrainGoalNode==route.size()-1&&distance<8)phase=Phase.ARRIVING;
        else if(limit<speed-.03)phase=Phase.DECELERATING;
        else if(controller.commandedSpeed()>speed+.02)phase=Phase.ACCELERATING;
        else phase=Phase.CRUISING;
    }
    /** 小段扫掠避免斜向航线的大包围盒把旁侧障碍误报为整条通道堵塞。 */
    private static boolean corridorClear(ServerWorld world,HelicopterEntity h,Box base,Vec3d delta){
        int steps=Math.max(1,(int)Math.ceil(delta.length()/4));Vec3d part=delta.multiply(1.0/steps);
        for(int i=0;i<steps;i++)if(!clear(world,h,base.offset(part.multiply(i)).stretch(part)))return false;
        return true;
    }
    /**
     * 功能：复用已加载世界的净空检查，不因二维规划而同步加载未知区块。
     * 引用 MC API：net.minecraft.server.world.ServerWorld#getBlockCollisions/#getOtherEntities
     * 检查方块与实体；net.minecraft.world.border.WorldBorder#contains 检查世界边界。
     * 端侧/线程：服务端 tick。返回：整个通道可用时为 true。副作用：无。
     * 故障排查：世界边缘或未知地形停车查加载范围，禁止后台线程调用此方法。
     */
    private static boolean clear(ServerWorld world,HelicopterEntity h,Box box){
        for(int x=MathHelper.floor(box.minX)>>4;x<=MathHelper.floor(box.maxX)>>4;x++)
            for(int z=MathHelper.floor(box.minZ)>>4;z<=MathHelper.floor(box.maxZ)>>4;z++)if(!world.getChunkManager().isChunkLoaded(x,z))return false;
        if(box.minY<world.getBottomY()||box.maxY>world.getTopY()||!world.getWorldBorder().contains(box)
                ||world.getBlockCollisions(h,box).iterator().hasNext()||world.containsFluid(box))return false;
        return world.getOtherEntities(h,box,e->!h.isConnectedThroughVehicle(e)&&!(e instanceof PlayerEntity)&&!(e instanceof RopeSeatEntity)
                &&!(e.getRootVehicle() instanceof RopeSeatEntity)&&!e.isSpectator()
                &&(e.isCollidable()||e instanceof net.minecraft.entity.LivingEntity||e instanceof HelicopterEntity)).isEmpty();
    }
    /** 功能：通过 net.minecraft.entity.player.PlayerEntity#sendMessage 向当前驾驶员显示双语动作栏；仅服务端调用。 */
    private static void notify(HelicopterEntity h,String key){if(h.getFirstPassenger() instanceof PlayerEntity p)p.sendMessage(net.minecraft.text.Text.translatable("message.no_24_heli."+key),true);}
}
