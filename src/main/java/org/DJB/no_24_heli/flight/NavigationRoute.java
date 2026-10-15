package org.DJB.no_24_heli.flight;

import java.util.List;

/**
 * 文件：二维规划入口，汇总八邻 A* 搜索、禁飞圆并集和安全路径点。
 * 端侧：COMMON；服务端快照交给规划线程，不依赖 Minecraft API，无注册项。
 * 排查：非法坐标先查原始圆，精度不足查格长，耗尽查独立预算状态。
 */
public final class NavigationRoute {
    /** 水平位置，单位为格；高度由驾驶器单独控制。 */
    public record Point(double x, double z) {}
    /** 原始禁飞圆，覆盖全部高度，半径单位为格，边界不可进入。 */
    public record Circle(double x, double z, double radius) {}
    /**
     * 不可变规划设置；前四项单位为格，后两项限制单元数和合计展开节点数。
     * 无 MC API；服务端创建、工作线程只读；合法点无路时先查格长和安全扩张量。
     */
    public record Settings(double cellSize, double horizontalRadius, double safetyMargin,
                           double boundaryMargin, int maxCells, int maxNodes) {
        /** 功能：返回机体与安全间距的圆半径扩张量；无 MC API、无副作用。 */
        public double clearance() { return horizontalRadius + safetyMargin; }
    }
    /** 规划状态；真实封闭、精度不足及资源失败分别报告，不将耗尽称为无路。 */
    public enum Status {
        SUCCESS, ALREADY_ARRIVED, START_FORBIDDEN, GOAL_FORBIDDEN, START_AND_GOAL_FORBIDDEN,
        START_ENCLOSED, GOAL_ENCLOSED, DIFFERENT_ENCLOSED_REGIONS, GRID_RESOLUTION,
        CELL_LIMIT, GEOMETRY_LIMIT, NODE_LIMIT, INTERRUPTED, INVALID_INPUT, TRAJECTORY_UNSAFE
    }
    /**
     * 不可变结果；path 不含起点，成功时最后一点为原始终点；两端封闭不等于不可达。
     * 无 MC API；工作线程创建、服务端读取；问题先查 status，再查 cellSize 和节点数。
     */
    public record Result(Status status, List<Point> path, int expandedNodes,
                         boolean startEnclosed, boolean goalEnclosed, double cellSize) {
        /** 功能：复制输出列表以隔离后台结果；无 MC API，无外部副作用。 */
        public Result { path = List.copyOf(path); }
    }
    /** 文档默认两格精度；宽度 3.4 格机体使用水平包围盒外接圆。 */
    public static final Settings DEFAULT_SETTINGS =
            new Settings(2.0, Math.hypot(1.7, 1.7), 2.0, 16.0, 1_000_000, 1_000_000);
    /** 同位置和重复航点的容差，单位为格。 */
    static final double POSITION_EPSILON = 1.0e-6;
    /** 跟随额外余量的浮点比较容差，单位为格；原扩张圆边界仍严格禁止接触。 */
    private static final double BUFFER_EPSILON = 1.0e-9;

    /**
     * 功能：按真实速度射线与扩张圆的首次交点限速，不把圆的径向距离误当切向制动距离。
     * 引用 MC API：无。端侧/线程：服务端 tick；输入位置及实际格/tick速度。
     * 返回：带12 tick姿态反应余量的安全速度；副作用无。排查：弯道反复停车先查射线方向。
     */
    public static double speedLimit(Point pos,double vx,double vz,List<Circle> circles,Settings settings,double cruise){
        double speed=Math.hypot(vx,vz);
        if(speed<.001)return cruise;
        double distance=Double.POSITIVE_INFINITY,ux=vx/speed,uz=vz/speed;
        for(var circle:circles){
            double dx=circle.x()-pos.x(),dz=circle.z()-pos.z();
            double radius=circle.radius()+settings.clearance();
            double projection=dx*ux+dz*uz;
            double perpendicular=dx*dx+dz*dz-projection*projection;
            if(perpendicular>=radius*radius||projection<=0)continue;
            distance=Math.min(distance,Math.max(0,projection-Math.sqrt(radius*radius-perpendicular)));
        }
        double response=NavigationController.BRAKE_ACCELERATION*NavigationController.BRAKE_RESPONSE;
        return Math.min(cruise,
                Math.sqrt(response*response+2*NavigationController.BRAKE_ACCELERATION*distance)-response);
    }
    /** 功能：禁止实例化工具入口；无 MC API、无副作用。 */
    private NavigationRoute() {}
    /** 功能：返回二维欧氏距离；无 MC API，任意线程，无副作用。 */
    public static double distance(Point a, Point b) { return Math.hypot(a.x-b.x, a.z-b.z); }
    /** 功能：返回圆心到完整闭线段的最短距离；无 MC API、无副作用。 */
    public static double segmentDistance(Point a, Point b, Circle c) {
        double dx=b.x-a.x, dz=b.z-a.z, length=dx*dx+dz*dz;
        double t=length==0.0 ? 0.0 : Math.max(0.0, Math.min(1.0,
                ((c.x-a.x)*dx+(c.z-a.z)*dz)/length));
        return Math.hypot(a.x+t*dx-c.x, a.z+t*dz-c.z);
    }
    /**
     * 功能：按机体半径与安全间距检查完整线段，包括两端。
     * 引用 MC API：无。端侧/线程：COMMON 任意线程。参数：只读圆快照及当前设置。
     * 返回：与全部扩张圆严格分离时为 true。副作用：无。
     * 故障排查：擦边查是否误用原始半径；圈内驶离也必须返回 false。
     */
    public static boolean clear(Point a, Point b, List<Circle> zones, Settings settings) {
        if (Thread.currentThread().isInterrupted()) return false;
        for (Circle circle : zones) {
            if (Thread.currentThread().isInterrupted()
                    || segmentDistance(a,b,circle)<=circle.radius+settings.clearance()) return false;
        }
        return true;
    }
    /**
     * 功能：检查完整航段相对单圆的跟随余量；不改变网格、封闭区域或真实终点合法性。
     * 引用 MC API：无。端侧/线程：COMMON 任意线程。返回：原圆严格分离且满足额外余量时为 true。
     * 副作用：无。故障排查：额外余量边界允许相等，避免终点自身净空被当成非法擦边。
     */
    static boolean followingSegmentClear(Point a, Point b, Circle circle, Settings settings) {
        double safeRadius=circle.radius+settings.clearance();
        double segmentClearance=segmentDistance(a,b,circle)-safeRadius;
        if(segmentClearance<=0.0) return false;
        double endpointClearance=Math.min(Math.hypot(a.x-circle.x,a.z-circle.z),
                Math.hypot(b.x-circle.x,b.z-circle.z))-safeRadius;
        double extra=Math.max(0.0,Math.min(settings.cellSize()/2.0,endpointClearance));
        return segmentClearance+BUFFER_EPSILON>=extra;
    }
    /**
     * 功能：构建八邻网格、A* 搜索及真实圆并集封闭校验。
     * 引用 MC API：无。端侧/线程：后台规划线程，只读快照，支持中断。
     * 参数：机体半径由调用者按当前碰撞箱计算。返回：不可变路径及独立状态。
     * 副作用：只分配本次数组。故障排查：合法点无路先分辨精度状态与预算状态。
     */
    public static Result plan(Point start,Point goal,List<Circle> zones,Settings settings) {
        int remainingNodes=settings.maxNodes(),expanded=0;
        Result result=planAtResolution(start,goal,zones,settings);
        expanded+=result.expandedNodes();remainingNodes-=result.expandedNodes();
        // 只为可疑的栅格误堵细化，最多三轮且最细0.25格；共享节点预算，资源失败不重试。
        for(int attempt=0;attempt<3&&result.status()==Status.GRID_RESOLUTION&&settings.cellSize()>.25;attempt++) {
            if(remainingNodes<=0) return new Result(Status.NODE_LIMIT,List.of(),expanded,
                    result.startEnclosed(),result.goalEnclosed(),result.cellSize());
            settings=new Settings(Math.max(.25,settings.cellSize()/2),settings.horizontalRadius(),
                    settings.safetyMargin(),settings.boundaryMargin(),settings.maxCells(),remainingNodes);
            result=planAtResolution(start,goal,zones,settings);
            expanded+=result.expandedNodes();remainingNodes-=result.expandedNodes();
        }
        return new Result(result.status(),result.path(),expanded,result.startEnclosed(),result.goalEnclosed(),result.cellSize());
    }
    /** 功能：执行一次指定精度的有限规划；仅后台纯计算，预算与状态独立返回。 */
    private static Result planAtResolution(Point start,Point goal,List<Circle> zones,Settings settings) {
        NavigationGrid.Budget budget=new NavigationGrid.Budget(settings.maxCells(),settings.maxNodes());
        Status status=validate(start,goal,zones,settings,budget);
        if(status!=Status.SUCCESS) return result(status,List.of(),budget,false,false,settings);
        if(distance(start,goal)<=POSITION_EPSILON)
            return result(Status.ALREADY_ARRIVED,List.of(),budget,false,false,settings);
        if (!clear(start,start,zones,settings) || !clear(goal,goal,zones,settings))
            return result(Status.GRID_RESOLUTION,List.of(),budget,false,false,settings);
        NavigationRegions.Classification regions=NavigationRegions.classify(start,goal,zones,settings,budget);
        if(regions.status()!=Status.SUCCESS) return result(regions.status(),List.of(),budget,false,false,settings);
        if(!regions.sameRegion()) return result(disconnectedStatus(regions),List.of(),budget,
                regions.startEnclosed(),regions.goalEnclosed(),settings);
        // 空旷长航线直接连接，不为远处无关圆分配巨型网格；仍验证所有圆和两端封闭性质。
        if(clear(start,goal,zones,settings)) return result(Status.SUCCESS,List.of(goal),budget,
                regions.startEnclosed(),regions.goalEnclosed(),settings);
        NavigationGrid.Build built=NavigationGrid.build(start,goal,zones,settings,budget);
        if(built.status()!=Status.SUCCESS) return result(built.status(),List.of(),budget,false,false,settings);
        NavigationGrid grid=built.grid(); int startCell=grid.cell(start),goalCell=grid.cell(goal);
        if(grid.blocked(startCell)||grid.blocked(goalCell))
            return result(Status.GRID_RESOLUTION,List.of(),budget,false,false,settings);
        NavigationAStar.Search search=NavigationAStar.search(grid,startCell,goalCell,budget);
        if(search.status()!=Status.SUCCESS) return result(search.status(),List.of(),budget,false,false,settings);
        if(!search.targetReached()) return result(Status.GRID_RESOLUTION,List.of(),budget,
                regions.startEnclosed(),regions.goalEnclosed(),settings);
        NavigationPath.Conversion converted=NavigationPath.convert(start,goal,zones,settings,grid,
                startCell,goalCell,search.previous(),budget);
        return result(converted.status(),converted.path(),budget,regions.startEnclosed(),regions.goalEnclosed(),settings);
    }
    /** 功能：先检查有限输入和真实圆含边界，再允许已到达；无 MC API、无外部副作用。 */
    private static Status validate(Point start,Point goal,List<Circle> zones,Settings s,NavigationGrid.Budget budget) {
        if (Thread.currentThread().isInterrupted()) return Status.INTERRUPTED;
        if(!finite(start)||!finite(goal)||!Double.isFinite(s.cellSize())||s.cellSize()<=0.0
                ||!Double.isFinite(s.horizontalRadius())||s.horizontalRadius()<0.0
                ||!Double.isFinite(s.safetyMargin())||s.safetyMargin()<0.0||!Double.isFinite(s.clearance())
                ||!Double.isFinite(s.boundaryMargin())||s.boundaryMargin()<0.0
                ||s.maxCells()<=0||s.maxNodes()<=0) return Status.INVALID_INPUT;
        boolean startForbidden=false,goalForbidden=false;
        for(Circle circle:zones) {
            Status limit=budget.geometry(); if(limit!=Status.SUCCESS) return limit;
            if(!Double.isFinite(circle.x)||!Double.isFinite(circle.z)
                    ||!Double.isFinite(circle.radius)||circle.radius<0.0) return Status.INVALID_INPUT;
            startForbidden|=Math.hypot(start.x-circle.x,start.z-circle.z)<=circle.radius;
            goalForbidden|=Math.hypot(goal.x-circle.x,goal.z-circle.z)<=circle.radius;
        }
        if(startForbidden&&goalForbidden) return Status.START_AND_GOAL_FORBIDDEN;
        if(startForbidden) return Status.START_FORBIDDEN;
        return goalForbidden?Status.GOAL_FORBIDDEN:Status.SUCCESS;
    }
    /** 功能：检查两个坐标均有限；无 MC API、无副作用。 */
    private static boolean finite(Point p) { return Double.isFinite(p.x)&&Double.isFinite(p.z); }
    /** 功能：区分单端封闭、不同封闭区域与网格误堵；无 MC API、无副作用。 */
    private static Status disconnectedStatus(NavigationRegions.Classification r) {
        if(r.startEnclosed()&&r.goalEnclosed()&&!r.sameRegion()) return Status.DIFFERENT_ENCLOSED_REGIONS;
        if(r.startEnclosed()&&!r.goalEnclosed()) return Status.START_ENCLOSED;
        if(r.goalEnclosed()&&!r.startEnclosed()) return Status.GOAL_ENCLOSED;
        return Status.GRID_RESOLUTION;
    }
    /** 功能：统一封装实际展开数及设置；无 MC API、无副作用。 */
    private static Result result(Status status,List<Point> path,NavigationGrid.Budget budget,
                                 boolean startEnclosed,boolean goalEnclosed,Settings settings) {
        return new Result(status,path,budget.expandedNodes(),startEnclosed,goalEnclosed,settings.cellSize());
    }
}
