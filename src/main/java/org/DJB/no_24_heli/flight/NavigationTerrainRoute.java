package org.DJB.no_24_heli.flight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 文件：在已加载地形快照内比较三维飞行时间与顶棚风险，归属既有 flight 飞控模块。
 * 端侧：COMMON；无注册项或 MC API，后台独占搜索，服务端只读完成后的航路。
 * 生命周期：NavigationPilot 分段提交；取消响应线程中断，不接触世界和实体。
 * 排查：洞道拒绝先查整机净空和格长；绕山选择查时间及顶棚惩罚；边界失败不代表全世界无路。
 */
final class NavigationTerrainRoute {
    /** 地形格长四格；先扩张整机包络，再要求整格安全，薄墙和对角接触都不可穿越。 */
    static final int CELL = 4;
    /** 整机任意姿态半径加一格控制余量；编译期常量，不在后台调用碰撞或世界 API。 */
    static final double ENVELOPE = org.DJB.no_24_heli.entity.HelicopterCollision.QUERY_RADIUS + 1;
    /** 时间单位 tick；升降低于原高度保持的 0.16 上限，留出响应余量。 */
    static final double CLIMB_SPEED = .10, DESCENT_SPEED = .08, COVERED_SPEED = .25;
    /** 顶棚内时间按三倍计入，并在每次进入时增加二十秒风险代价，避免轻微捷径诱导穿洞。 */
    private static final double COVERED_WEIGHT = 3, ENTRY_COST = 400;
    /** 单次局部搜索最多展开四十万个节点，不突破用户的 pathMaxNodes。 */
    private static final int MAX_NODES = 400_000;
    /** 搜索时允许沿父节点跨格直连，最远一百二十八格；避免把缓坡的同步升降算成逐格陡爬。 */
    private static final double SHORTCUT_DISTANCE = 128;
    /** 加权启发式优先获得可飞路线，权重 1.15；不承诺严格时间最优，净空和风险代价保持不变。 */
    private static final double HEURISTIC_WEIGHT = 1.15;
    /** 转角最多切二十四格并保留相邻直线六成，轨迹按两格以内加密供连续前视。 */
    private static final double TURN_CUT = 24, SPACING = 2;
    /** 三维坐标以实体原点为准，单位格；无 MC API、无可变字段。 */
    record Point(double x, double y, double z) {
        /** 功能：投影为原禁区规划坐标；无 MC API、无副作用。 */
        NavigationRoute.Point horizontal() { return new NavigationRoute.Point(x, z); }
        /** 功能：返回三维距离，供连续通道检查；无 MC API。 */
        double distance(Point other) { return Math.hypot(Math.hypot(x-other.x, z-other.z), y-other.y); }
    }
    /** 完整结果包含快照，供跟随器复查切线；失败不交付部分路径，代价含风险而非到达承诺。 */
    record Result(NavigationRoute.Status status, List<Point> path, Volume volume, int expanded,
                  double costTicks) {
        /** 功能：隔离结果列表，服务端与后台均不可修改；无 MC API。 */
        Result { path = List.copyOf(path); }
    }

    /**
     * 原始占用格与整机配置空间；无 MC API，采集结束后后台构建，之后仅只读。
     * 占用前缀和只在构造时使用；未知区块和世界外均按障碍处理，不能用于洞道出口。
     */
    static final class Volume {
        /** 格心原点、各轴格数；索引顺序 X、Z、Y。 */
        final double x, y, z;
        final int nx, ny, nz;
        /** 整格能容纳整机时为真；covered 表示上方存在障碍，二者只在构造时写。 */
        private final boolean[] free, covered;
        /** 本任务的只读禁区、设置和巡航速度，全部高度遵循同一禁区约束。 */
        private final List<NavigationRoute.Circle> circles;
        private final NavigationRoute.Settings settings;
        private final double cruise;

        /**
         * 功能：以三维前缀和膨胀占用格，保证整格内任意位置和任意机头方向均有整机净空。
         * 引用 MC API：无；后台调用；输入数组由采集器移交后不再修改。
         * 副作用：仅本实例数组；中断由调用方在构造前后及搜索中处理。
         * 排查：洞口宽度足够却拒绝时检查四格离散造成的额外保守余量，不缩小机体包络。
         */
        Volume(double x, double y, double z, int nx, int ny, int nz, byte[] occupied,
               List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings, double cruise) {
            this.x=x; this.y=y; this.z=z; this.nx=nx; this.ny=ny; this.nz=nz;
            this.circles=List.copyOf(circles); this.settings=settings; this.cruise=cruise;
            free=new boolean[occupied.length]; covered=new boolean[occupied.length];
            int sx=nx+1, sz=nz+1, layer=sx*sz;
            int[] sum=new int[layer*(ny+1)];
            for(int iy=1;iy<=ny;iy++) for(int iz=1;iz<=nz;iz++) for(int ix=1;ix<=nx;ix++) {
                if(ix==1&&Thread.currentThread().isInterrupted())return;
                int at=iy*layer+iz*sx+ix;
                sum[at]=occupied[index(ix-1,iy-1,iz-1)]+sum[at-1]+sum[at-sx]+sum[at-layer]
                        -sum[at-1-sx]-sum[at-1-layer]-sum[at-sx-layer]+sum[at-1-sx-layer];
            }
            int pad=(int)Math.ceil(ENVELOPE/CELL);
            for(int iz=pad;iz<nz-pad;iz++) for(int ix=pad;ix<nx-pad;ix++) {
                if(Thread.currentThread().isInterrupted())return;
                // 方块仍按整格膨胀；禁飞圆改为每条实际线段精确检查，避免四格栅格吞掉合法贴边终点。
                for(int iy=pad;iy<ny-pad;iy++) {
                    int at=index(ix,iy,iz);
                    free[at]=count(sum,sx,layer,ix-pad,iy-pad,iz-pad,ix+pad+1,iy+pad+1,iz+pad+1)==0;
                    covered[at]=count(sum,sx,layer,ix-pad,iy+pad+1,iz-pad,ix+pad+1,ny,iz+pad+1)>0;
                }
            }
        }
        /** 功能：查询半开长方体占用数；无 MC API，无副作用，坐标已由构造循环约束。 */
        private static int count(int[] sum,int sx,int layer,int x0,int y0,int z0,int x1,int y1,int z1) {
            return sum[y1*layer+z1*sx+x1]-sum[y0*layer+z1*sx+x1]-sum[y1*layer+z0*sx+x1]
                    -sum[y1*layer+z1*sx+x0]+sum[y0*layer+z0*sx+x1]+sum[y0*layer+z1*sx+x0]
                    +sum[y1*layer+z0*sx+x0]-sum[y0*layer+z0*sx+x0];
        }
        /** 功能：三维格坐标转连续索引；无 MC API，调用前须检查范围。 */
        int index(int ix,int iy,int iz) { return (iy*nz+iz)*nx+ix; }
        /** 功能：返回索引对应的世界格心；无 MC API。 */
        Point point(int at) { return new Point(x+at%nx*CELL,y+at/(nx*nz)*CELL,z+(at/nx%nz)*CELL); }
        /** 功能：获取实际坐标所在配置空间格；快照外返回 -1，不当作空气。 */
        int cell(Point p) {
            int ix=(int)Math.floor((p.x-x)/CELL+.5), iy=(int)Math.floor((p.y-y)/CELL+.5), iz=(int)Math.floor((p.z-z)/CELL+.5);
            return ix<0||iy<0||iz<0||ix>=nx||iy>=ny||iz>=nz?-1:index(ix,iy,iz);
        }
        /** 功能：读取安全配置格；越界为障碍，无 MC API。 */
        boolean free(int at) { return at>=0&&at<free.length&&free[at]; }
        /** 功能：检查点是否位于顶棚下；越界视为高风险，不输出未确认出口。 */
        boolean covered(Point p) { int at=cell(p); return at<0||covered[at]; }
        /** 功能：整段只要经过顶棚就提前采用洞内限速；不能只看露天的入口前点和出口后点。 */
        double speed(Point a,Point b) {
            int steps=Math.max(1,(int)Math.ceil(a.distance(b)/(CELL*.5)));
            int last=cell(a);
            if(!free(last))return Math.min(cruise,COVERED_SPEED);
            for(int i=1;i<=steps;i++) {
                int next=cell(interpolate(a,b,(double)i/steps));
                if(!free(next)||boxCovered(last,next))return Math.min(cruise,COVERED_SPEED);
                last=next;
            }
            return cruise;
        }
        /**
         * 功能：连续线段的保守超覆盖检查，每两格检查所有跨过的配置格，禁止边角擦墙。
         * 无 MC API；后台平滑与服务端短前视复用。整格已含机体包络，所以不是只测航点。
         * 返回：全段安全；无副作用；未知格、禁区或中断均拒绝，不能跳过不安全段。
         */
        boolean clear(Point a,Point b) {
            if(!airspaceClear(a,b))return false;
            int steps=Math.max(1,(int)Math.ceil(a.distance(b)/(CELL*.5)));
            Point last=a;
            for(int i=0;i<=steps;i++) {
                if(Thread.currentThread().isInterrupted())return false;
                Point p=interpolate(a,b,(double)i/steps);
                int first=cell(last),end=cell(p);
                if(!free(first)||!free(end)||!boxClear(first,end))return false;
                last=p;
            }
            return true;
        }
        /** 功能：精确检查整条线段与整机扩张禁飞圆；无 MC API，不附加体素四角的额外膨胀。 */
        private boolean airspaceClear(Point a,Point b) {
            for(var circle:circles)
                if(NavigationRoute.segmentDistance(a.horizontal(),b.horizontal(),circle)
                        <=circle.radius()+Math.max(ENVELOPE,settings.clearance()))return false;
            return !Thread.currentThread().isInterrupted();
        }
        /** 功能：检查相邻配置格包围体，覆盖斜穿边缘时所有正交邻格；无 MC API。 */
        boolean boxClear(int a,int b) {
            int ax=a%nx,ay=a/(nx*nz),az=a/nx%nz,bx=b%nx,by=b/(nx*nz),bz=b/nx%nz;
            for(int iy=Math.min(ay,by);iy<=Math.max(ay,by);iy++)
                for(int iz=Math.min(az,bz);iz<=Math.max(az,bz);iz++)
                    for(int ix=Math.min(ax,bx);ix<=Math.max(ax,bx);ix++)if(!free[index(ix,iy,iz)])return false;
            return true;
        }
        /** 功能：顶棚判定与净空使用同一超覆盖格集合，斜穿边角也按洞内限速和风险计价；无 MC API。 */
        private boolean boxCovered(int a,int b) {
            int ax=a%nx,ay=a/(nx*nz),az=a/nx%nz,bx=b%nx,by=b/(nx*nz),bz=b/nx%nz;
            for(int iy=Math.min(ay,by);iy<=Math.max(ay,by);iy++)
                for(int iz=Math.min(az,bz);iz<=Math.max(az,bz);iz++)
                    for(int ix=Math.min(ax,bx);ix<=Math.max(ax,bx);ix++)if(covered[index(ix,iy,iz)])return true;
            return false;
        }
        /**
         * 功能：连续段飞行时间加顶棚风险，搜索、父节点直连和平滑统一使用，单位 tick。
         * 无 MC API；后台调用。整段采用跟随器相同的最低限速，顶棚部分另加风险，每次进入单独收费。
         * 排查：不能只给洞内采样降速却让跟随器在整条长段限速，否则穿洞时间会被低估。
         */
        double cost(Point a,Point b) {
            int steps=Math.max(1,(int)Math.ceil(a.distance(b)/(CELL*.5)));
            int last=cell(a),roofSteps=0,entries=0;
            if(!free(last))return Double.POSITIVE_INFINITY;
            boolean wasCovered=covered[last];
            for(int i=1;i<=steps;i++) {
                if(Thread.currentThread().isInterrupted())return Double.POSITIVE_INFINITY;
                int next=cell(interpolate(a,b,(double)i/steps));
                if(!free(next))return Double.POSITIVE_INFINITY;
                boolean roof=boxCovered(last,next);
                if(roof)roofSteps++;
                if(!wasCovered&&roof)entries++;
                wasCovered=roof;last=next;
            }
            double speed=roofSteps>0?Math.min(cruise,COVERED_SPEED):cruise;
            return travel(a,b,speed)*(1+(COVERED_WEIGHT-1)*roofSteps/steps)+ENTRY_COST*entries;
        }
    }

    /** 受体素总数限制的索引最小堆，每格至多一个开放项，不使用无界重复队列。 */
    private final int[] heap,position,parent;
    /** g 与 f 以 tick 为单位；边权非负，水平时间下界乘 1.15 后用于优先获得可飞路线。 */
    private final double[] cost,score;
    /** 堆中有效项数量，仅当前工作线程修改。 */
    private int size;
    /** 起点高度格及每层格数；时间相同时优先保持原高度，避免露天空域无收益地起伏。 */
    private final int startLevel,layerCells;
    /** 功能：为本次有界三维搜索分配数组，无 MC API、无外部副作用。 */
    private NavigationTerrainRoute(int cells,int layerCells,int begin) {
        this.layerCells=layerCells;startLevel=begin/layerCells;
        heap=new int[cells];position=new int[cells];parent=new int[cells];cost=new double[cells];score=new double[cells];
        Arrays.fill(position,-1);Arrays.fill(parent,-1);Arrays.fill(cost,Double.POSITIVE_INFINITY);
    }
    /**
     * 功能：沿全局引导顺序选择本侧可见的局部终点，首次被禁区遮挡即停止，不跳到圆的另一侧。
     * 无 MC API；服务端创建地形请求时调用，返回单调前进的引导索引，不修改输入列表。
     * 重规划时 first 是未抵达的旧局部终点；即使当前位置因漂移暂不可直达，也保留该绕行目标。
     */
    static int goalNode(NavigationRoute.Point start,List<NavigationRoute.Point> route,int first,double look,
                        List<NavigationRoute.Circle> circles,NavigationRoute.Settings settings) {
        var from=start;double distance=0;int selected=first;
        for(int at=first;at<route.size();at++) {
            double segment=NavigationRoute.distance(from,route.get(at));
            if(at>first&&(distance+segment>look||!NavigationRoute.clear(start,route.get(at),circles,settings)))break;
            distance+=segment;from=route.get(at);selected=at;
        }
        return selected;
    }
    /** 功能：只在整机直线走廊已验证且无顶棚时交付快速路线；失败要求扩大采集范围，不猜测侧方地形。 */
    static Result direct(Volume v,Point start,NavigationRoute.Point goal) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,0);
        Point end=new Point(goal.x(),start.y,goal.z());
        if(!v.covered(end)&&v.clear(start,end)&&v.speed(start,end)==v.cruise)return success(v,List.of(start,end),0);
        return failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
    }
    /**
     * 功能：二十六邻三维 A* 加父节点可见直连，同时比较侧绕、缓坡升降与穿洞，出口必须无顶棚。
     * 无 MC API；后台调用；输入为独占快照和真实起终点，终点高度自由选择，不擅自自动降落。
     * 返回：完整三维航路或精度/预算失败；副作用仅局部数组。
     * 排查：局部矩形外可能仍有路，GRID_RESOLUTION 绝不等同真实封闭。
     */
    static Result plan(Volume v,Point start,NavigationRoute.Point goal) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,0);
        int begin=v.cell(start),goalCell=v.cell(new Point(goal.x(),start.y,goal.z()));
        if(!v.free(begin)||goalCell<0||!v.airspaceClear(start,start)
                ||!v.airspaceClear(new Point(goal.x(),start.y,goal.z()),new Point(goal.x(),start.y,goal.z())))
            return failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
        // 露天空域整段验证后直接交付，不为平直航线分配搜索堆或展开三维网格。
        Result direct=direct(v,start,goal);
        if(direct.status()!=NavigationRoute.Status.GRID_RESOLUTION)return direct;
        NavigationTerrainRoute open=new NavigationTerrainRoute(v.free.length,v.nx*v.nz,begin);
        open.cost[begin]=0;open.parent[begin]=begin;open.offer(begin,HEURISTIC_WEIGHT*heuristic(start,goal,v.cruise));
        int expanded=0,limit=Math.min(MAX_NODES,v.settings.maxNodes());
        while(open.size>0) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
            if(expanded++>=limit)return failure(NavigationRoute.Status.NODE_LIMIT,v,expanded-1);
            int at=open.poll();Point p=at==begin?start:v.point(at);
            // 真实终点可能在合法的半格内而格心落入禁区，允许从相邻格完整验证后接入终点。
            if(NavigationRoute.distance(p.horizontal(),goal)<=CELL*Math.sqrt(2)&&!v.covered[at]) {
                Point end=new Point(goal.x(),p.y,goal.z());
                if(v.clear(p,end)&&!v.covered(end))return open.finish(v,at,start,end,expanded);
            }
            int ix=at%v.nx,iy=at/(v.nx*v.nz),iz=at/v.nx%v.nz;
            for(int dy=-1;dy<=1;dy++) for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dy==0&&dz==0||ix+dx<0||iy+dy<0||iz+dz<0||ix+dx>=v.nx||iy+dy>=v.ny||iz+dz>=v.nz)continue;
                int next=v.index(ix+dx,iy+dy,iz+dz);
                if(!v.free[next]||open.position[next]==-2||!v.boxClear(at,next))continue;
                Point q=v.point(next);
                if(!v.airspaceClear(p,q))continue;
                double candidate=open.cost[at]+v.cost(p,q);int predecessor=at;
                int ancestor=open.parent[at];Point origin=ancestor==begin?start:v.point(ancestor);
                // 在搜索竞争阶段就比较同步升降时间；只在终点之后平滑无法纠正已经选错的绕山方向。
                if(ancestor!=at&&origin.distance(q)<=SHORTCUT_DISTANCE
                        &&open.cost[ancestor]+travel(origin,q,v.cruise)<=Math.min(candidate,open.cost[next])+1e-6
                        &&v.clear(origin,q)) {
                    double shortcut=open.cost[ancestor]+v.cost(origin,q);
                    if(shortcut<=candidate) {candidate=shortcut;predecessor=ancestor;}
                }
                if(candidate>=open.cost[next])continue;
                open.cost[next]=candidate;open.parent[next]=predecessor;
                open.offer(next,candidate+HEURISTIC_WEIGHT*heuristic(q,goal,v.cruise));
            }
        }
        return failure(NavigationRoute.Status.GRID_RESOLUTION,v,expanded);
    }
    /** 功能：恢复真实起终点，有限可见性平滑只接受不增加风险代价的完整安全段；无 MC API。 */
    private Result finish(Volume v,int at,Point start,Point end,int expanded) {
        List<Point> raw=new ArrayList<>();
        for(int p=at;;p=parent[p]) { raw.add(v.point(p));if(p==parent[p])break; }
        Collections.reverse(raw);raw.set(0,start);
        if(raw.getLast().distance(end)>1e-6)raw.add(end);
        List<Point> output=new ArrayList<>();output.add(start);
        int anchor=0;
        while(anchor+1<raw.size()) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
            int best=anchor+1;double original=0;
            // 每次最多比较三十二个后继，防止长曲折通道的平滑退化为平方扫描。
            for(int next=anchor+1;next<raw.size()&&next<=anchor+32;next++) {
                original+=v.cost(raw.get(next-1),raw.get(next));
                if(v.clear(raw.get(anchor),raw.get(next))&&v.cost(raw.get(anchor),raw.get(next))<=original+1e-6)best=next;
            }
            if(!v.clear(raw.get(anchor),raw.get(best)))return failure(NavigationRoute.Status.TRAJECTORY_UNSAFE,v,expanded);
            // 搜索窗口仅限制运算，不应把同一直线每一百二十八格拆成需要减速的假转角。
            if(output.size()>1) {
                Point before=output.get(output.size()-2),last=output.getLast(),next=raw.get(best);
                if(Math.abs(before.distance(last)+last.distance(next)-before.distance(next))<1e-6
                        &&v.clear(before,next)&&v.cost(before,next)<=v.cost(before,last)+v.cost(last,next)+1e-6)
                    output.removeLast();
            }
            output.add(raw.get(best));anchor=best;
        }
        return success(v,output,expanded);
    }
    /**
     * 功能：验证三维转弯曲线后加密轨迹，保留窄角原折线；无 MC API，后台执行。
     * 每条曲线的整段机体净空及风险代价均检查，不能通过放宽包络来提高连贯性。
     * 返回：完整轨迹；取消不交付半条路线。排查：窄洞无法圆角时由跟随器降低转角速度。
     */
    private static Result success(Volume v,List<Point> raw,int expanded) {
        List<Point> output=new ArrayList<>();output.add(raw.getFirst());
        for(int i=1;i+1<raw.size();i++) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
            Point a=raw.get(i-1),corner=raw.get(i),b=raw.get(i+1);
            double cut=Math.min(TURN_CUT,Math.min(a.distance(corner),corner.distance(b))*.4);
            boolean accepted=false;
            for(int attempt=0;attempt<5&&cut>=SPACING;attempt++,cut*=.5) {
                Point entry=interpolate(corner,a,cut/corner.distance(a));
                Point exit=interpolate(corner,b,cut/corner.distance(b));
                List<Point> curve=new ArrayList<>();curve.add(entry);
                int steps=Math.max(4,(int)Math.ceil(cut*2/SPACING));
                double cost=0;boolean safe=true;
                for(int n=1;n<=steps;n++) {
                    double t=(double)n/steps;
                    Point p=interpolate(interpolate(entry,corner,t),interpolate(corner,exit,t),t);
                    Point last=curve.getLast();
                    if(!v.clear(last,p)) {safe=false;break;}
                    cost+=v.cost(last,p);curve.add(p);
                }
                if(!safe||cost>v.cost(entry,corner)+v.cost(corner,exit)+1e-6
                        ||!v.clear(output.getLast(),entry))continue;
                append(output,entry);output.addAll(curve.subList(1,curve.size()));accepted=true;break;
            }
            if(!accepted)append(output,corner);
        }
        append(output,raw.getLast());
        double total=0;
        for(int i=1;i<output.size();i++) {
            if(!v.clear(output.get(i-1),output.get(i)))return failure(NavigationRoute.Status.TRAJECTORY_UNSAFE,v,expanded);
            total+=v.cost(output.get(i-1),output.get(i));
        }
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
        return new Result(NavigationRoute.Status.SUCCESS,output,v,expanded,total);
    }
    /** 功能：以不超过两格的间距追加直线，重复点不产生零长航段；无 MC API。 */
    private static void append(List<Point> output,Point end) {
        Point a=output.getLast();int steps=(int)Math.ceil(a.distance(end)/SPACING);
        for(int i=1;i<=steps;i++)output.add(interpolate(a,end,(double)i/steps));
    }
    /** 功能：水平和垂直运动可同时进行，取较慢轴的时间；无 MC API，单位 tick。 */
    private static double travel(Point a,Point b,double cruise) {
        return Math.max(Math.hypot(b.x-a.x,b.z-a.z)/cruise,Math.abs(b.y-a.y)/(b.y>=a.y?CLIMB_SPEED:DESCENT_SPEED));
    }
    /** 功能：终点未指定高度，水平飞行时间是可采纳的一致启发式；无 MC API。 */
    private static double heuristic(Point p,NavigationRoute.Point goal,double cruise) {
        return Math.hypot(p.x-goal.x(),p.z-goal.z())/cruise;
    }
    /** 功能：内插三维点；无 MC API、无副作用，调用者限定 t 在 0..1。 */
    static Point interpolate(Point a,Point b,double t) { return new Point(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t); }
    /** 功能：返回独立失败状态，不让未验证的部分航路进入控制器；无 MC API。 */
    private static Result failure(NavigationRoute.Status status,Volume v,int expanded) {
        return new Result(status,List.of(),v,expanded,Double.POSITIVE_INFINITY);
    }
    /** 功能：插入或降低索引堆优先级；无 MC API，仅修改本次搜索。 */
    private void offer(int cell,double priority) {
        score[cell]=priority;int at=position[cell];
        if(at<0) {at=size++;heap[at]=cell;position[cell]=at;}
        while(at>0) {int p=(at-1)/2;if(!less(heap[at],heap[p]))break;swap(at,p);at=p;}
    }
    /** 功能：弹出最小 f 值并关闭该格；无 MC API。 */
    private int poll() {
        int result=heap[0];swap(0,--size);position[result]=-2;int at=0;
        while(at*2+1<size) {int child=at*2+1;if(child+1<size&&less(heap[child+1],heap[child]))child++;
            if(!less(heap[child],heap[at]))break;swap(at,child);at=child;}
        return result;
    }
    /** 功能：先比较时间与风险，再按原高度和前进进度打破同值，减少无收益升降及等值展开；无 MC API。 */
    private boolean less(int a,int b) {
        int priority=Double.compare(score[a],score[b]);
        if(priority!=0)return priority<0;
        int heightA=Math.abs(a/layerCells-startLevel),heightB=Math.abs(b/layerCells-startLevel);
        return heightA!=heightB?heightA<heightB:cost[a]>cost[b];
    }
    /** 功能：交换堆元素并同步反向索引；无 MC API。 */
    private void swap(int a,int b) {int cell=heap[a];heap[a]=heap[b];heap[b]=cell;position[heap[a]]=a;position[heap[b]]=b;}
}
