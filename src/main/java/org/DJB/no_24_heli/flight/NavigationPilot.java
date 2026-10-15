package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.AirspaceState;
import org.DJB.no_24_heli.HeliConfig;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.RopeSeatEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 文件职责：在主线程制作禁区快照、接收后台 A* 曲线结果并连续跟随轨迹，复用原高度与运动控制。
 * 端侧：SERVER；关联通道：no_24_heli:navigation、no_24_heli:controls。
 * 依赖 MC API：net.minecraft.server.world.ServerWorld 提供地形和持久状态；
 * net.minecraft.entity.Entity#getWidth 提供稳定机体尺寸；net.minecraft.entity.player.PlayerEntity 接收提示。
 * 生命周期：每架飞机持有一个实例，由服务端 tick 调用，取消或移除时 reset。
 * 线程：世界和实体仅在 tick 访问；工作线程只计算不可变坐标，不回调实体。
 * 故障排查：旧路线恢复查 pending 的取消；卡顿查 Future 是否阻塞；实际切角查惯性通道限速。
 */
public final class NavigationPilot {
    /** 前视范围 256 格，地形净空 10 格；目标高度变化限制为每 tick 0.10 格。 */
    private static final double TERRAIN_LOOK=256,CLEARANCE=10,CLIMB_STEP=.10;
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
    private long revision=-1,retryAt,terrainAt;
    /** 当前目标及原地形前视缓存，单位格；NaN只标记尚未初始化。 */
    private double goalX=Double.NaN,goalZ,terrainAltitude,ridgeDistance;
    /** 下一个轨迹采样索引，供原地形扫描使用。 */
    private int node;
    /** 本机连续控制、局部跟踪和只读轨迹；只在取消时清除控制器记忆。 */
    private final NavigationController controller = new NavigationController();
    private final NavigationFollower follower = new NavigationFollower();
    private NavigationTrajectory trajectory;
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
        revision=-1;goalX=Double.NaN;route=List.of();circles=List.of();settings=null;
        node=0;retryAt=terrainAt=0;terrainAltitude=Double.NaN;attempted=false;
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
                    trajectory=planned.trajectory();route=trajectory.points();follower.bind(trajectory);node=1;terrainAt=0;
                }
                phase=Phase.READY;notify(h,"route_success");
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

    /**
     * 功能：推进安全路径点，沿用地形预判、原高度控制与姿态闭环。
     * 引用 MC API：net.minecraft.entity.Entity#getBoundingBox 与 ServerWorld 用于原局部碰撞检查。
     * 端侧/线程：服务端 tick。返回：无。副作用：只输出输入，不改实体坐标或实际速度。
     * 故障排查：切角查前视和制动距离；到点未退出查速度门槛。
     */
    private void follow(HelicopterEntity h,ServerWorld world,NavigationRoute.Point pos,NavigationRoute.Point goal){
        var s=h.flight;
        double speed=Math.hypot(s.vx,s.vz);
        if(NavigationRoute.distance(pos,goal)<ARRIVAL_DISTANCE&&speed<ARRIVAL_SPEED){
            h.finishAutopilot();phase=Phase.COMPLETED;notify(h,"route_arrived");return;
        }
        var sample=follower.sample(pos,speed,HeliConfig.INSTANCE.autopilotLookMin,
                HeliConfig.INSTANCE.autopilotLookMax,circles,settings);
        node=follower.nextNode();
        if(!sample.safe()||sample.deviation()>12){
            if(pending!=null){NavigationPlanningService.cancel(pending);pending=null;}
            route=List.of();trajectory=null;attempted=false;phase=Phase.REPLANNING;
            controller.step(s,h.getX(),h.getZ(),pos,0,0,0);return;
        }
        double remaining=Math.max(sample.remaining(),NavigationRoute.distance(pos,goal));
        var target=sample.target();
        double dx=target.x()-h.getX(),dz=target.z()-h.getZ(),distance=Math.hypot(dx,dz);
        double look=Math.min(distance,Math.max(24,speed*60+12));
        Vec3d ahead=distance<.1?Vec3d.ZERO:new Vec3d(dx/distance*look,0,dz/distance*look);
        // 实际前视与惯性通道独立检查，未知区块不强制加载。
        Box base=h.getBoundingBox().expand(2,.7,2);
        boolean forwardClear=corridorClear(world,h,base,ahead);
        boolean driftClear=corridorClear(world,h,base,new Vec3d(s.vx*(speed/(2*BRAKE_ACCELERATION)+BRAKE_RESPONSE),Math.min(0,s.vy*30),s.vz*(speed/(2*BRAKE_ACCELERATION)+BRAKE_RESPONSE)));
        double limit=forwardClear&&driftClear?Math.min(sample.speed(),NavigationRoute.speedLimit(pos,s.vx,s.vz,circles,settings,HeliConfig.INSTANCE.autopilotCruiseKmh/72.0)):0;
        if(world.getTime()>=terrainAt||Double.isNaN(terrainAltitude)){
            previewTerrain(world,h,pos);terrainAt=world.getTime()+10;
        }
        // 在山体尚未进入制动通道前就开始爬升；剩余距离决定水平限速，避免贴山急拉。
        double desired=Math.max(s.targetAltitude,terrainAltitude);
        if(!forwardClear){
            for(int rise=4;rise<=64;rise+=4)if(h.getY()+rise+4<world.getTopY()
                    &&clear(world,h,base.stretch(0,rise,0))&&corridorClear(world,h,base.offset(0,rise,0),ahead)){
                desired=Math.max(desired,h.getY()+rise);break;
            }
        }
        desired=Math.min(desired,world.getTopY()-5);
        if(desired>s.targetAltitude&&clear(world,h,base.stretch(0,2,0)))s.targetAltitude=Math.min(desired,s.targetAltitude+CLIMB_STEP);
        double rise=Math.max(0,terrainAltitude-h.getY());
        if(rise>2)limit=Math.min(limit,Math.max(.06,ridgeDistance*.08/(rise+8)));
        controller.step(s,h.getX(),h.getZ(),target,limit,remaining,sample.curvature());
        if(pending!=null)phase=Phase.REPLANNING;
        else if(remaining<8)phase=Phase.ARRIVING;
        else if(limit<speed-.03)phase=Phase.DECELERATING;
        else if(Math.abs(sample.curvature())>.002)phase=Phase.TURNING;
        else if(controller.commandedSpeed()>speed+.02)phase=Phase.ACCELERATING;
        else phase=Phase.CRUISING;
    }

    /** 沿缓存路线分段采样地形，不把弯道内侧或相邻山体误当作路线上的山峰。 */
    private void previewTerrain(ServerWorld world,HelicopterEntity h,NavigationRoute.Point start){
        terrainAltitude=h.flight.targetAltitude;ridgeDistance=TERRAIN_LOOK;
        var from=start;double travelled=0;
        for(int at=node;at<route.size()&&travelled<TERRAIN_LOOK;at++){
            var to=route.get(at);double length=NavigationRoute.distance(from,to);if(length<.01){from=to;continue;}
            double end=Math.min(length,TERRAIN_LOOK-travelled);
            for(double d=0;d<=end;d+=8){
                double x=from.x()+(to.x()-from.x())*d/length,z=from.z()+(to.z()-from.z())*d/length;
                double top=Double.NEGATIVE_INFINITY;
                for(int side=-1;side<=1;side++){
                    int bx=MathHelper.floor(x-(to.z()-from.z())/length*side*3),bz=MathHelper.floor(z+(to.x()-from.x())/length*side*3);
                    if(!world.getChunkManager().isChunkLoaded(bx>>4,bz>>4))return;
                    top=Math.max(top,world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,bx,bz)+CLEARANCE);
                }
                // 上方有实心顶棚时保持局部障碍检查，防止洞内导航追逐山顶高度。
                if(!world.isSkyVisible(h.getBlockPos().up(4)))return;
                if(top>terrainAltitude){terrainAltitude=top;ridgeDistance=Math.max(8,travelled+d);}
            }
            travelled+=end;from=to;
        }
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
        if(!world.getWorldBorder().contains(box)||world.getBlockCollisions(h,box).iterator().hasNext())return false;
        return world.getOtherEntities(h,box,e->!h.isConnectedThroughVehicle(e)&&!(e instanceof PlayerEntity)&&!(e instanceof RopeSeatEntity)
                &&!(e.getRootVehicle() instanceof RopeSeatEntity)&&!e.isSpectator()&&(e.isCollidable()||e instanceof net.minecraft.entity.LivingEntity)).isEmpty();
    }
    /** 功能：通过 net.minecraft.entity.player.PlayerEntity#sendMessage 向当前驾驶员显示双语动作栏；仅服务端调用。 */
    private static void notify(HelicopterEntity h,String key){if(h.getFirstPassenger() instanceof PlayerEntity p)p.sendMessage(net.minecraft.text.Text.translatable("message.no_24_heli."+key),true);}
}
