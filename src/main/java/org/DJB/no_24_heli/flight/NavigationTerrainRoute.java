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
    static final double CLIMB_SPEED = .14, DESCENT_SPEED = .12, COVERED_SPEED = .25;
    /** 升降额外计入一点五倍垂直用时；低于参考巡航净高最多加三成半，避免软净高压过侧绕收益。 */
    private static final double VERTICAL_WEIGHT=1.5, LOW_ALTITUDE_WEIGHT=.35;
    /** 禁区外十二格为软余量，不改变整机硬净空或无限高禁区边界。 */
    private static final double COMFORT_MARGIN=12;
    /** 顶棚内时间按三倍计入，并在每次进入时增加二十秒风险代价，避免轻微捷径诱导穿洞。 */
    private static final double COVERED_WEIGHT = 3, ENTRY_COST = 400;
    /** 单次局部搜索最多展开四十万个节点，不突破用户的 pathMaxNodes。 */
    private static final int MAX_NODES = 400_000;
    /** 同高度绕行先使用至多一万六千节点及总预算四分之一；代价不超过直飞下界 1.8 倍时优先采用。 */
    private static final int PLANAR_NODES=16_000;
    private static final double SIDE_DETOUR_RATIO=1.8;
    /** 搜索时允许沿父节点跨格直连，最远一百二十八格；避免把缓坡的同步升降算成逐格陡爬。 */
    private static final double SHORTCUT_DISTANCE = 128;
    /** 加权启发式优先获得可飞路线，权重 1.15；不承诺严格时间最优，净空和风险代价保持不变。 */
    private static final double HEURISTIC_WEIGHT = 1.15;
    /** 转角最多切四十八格并保留相邻直线六成，轨迹按两格以内加密供连续前视。 */
    private static final double TURN_CUT = 48, SPACING = 2;
    /** 确需避障爬升时参考局部平均地表上方五十六格；终点优先悬停三十二格，候选不超过四十八格。 */
    static final double CRUISE_CLEARANCE=56, ARRIVAL_CLEARANCE=32;
    /** 终点高度在默认值上下各十六格内按体素步长寻找完整净空，不突破五十格降落范围。 */
    private static final int ARRIVAL_RANGE=16;
    /** 地表均值使用三十二格半径，按十六格步长沿航段取最高均值，避免巡航追随每个小土坡起伏。 */
    private static final int SURFACE_RADIUS=32, SURFACE_STEP=16;
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
        /** 每格额外时间比例的百分数，范围 0..100；优先远离山壁与禁区，不能代替硬净空。 */
        private final byte[] proximity;
        /** 本任务的只读禁区、设置和巡航速度，全部高度遵循同一禁区约束。 */
        private final List<NavigationRoute.Circle> circles;
        private final NavigationRoute.Settings settings;
        private final double cruise;
        /** 已加载方柱的最高地表；未知列为 NaN，不能参与平均值或终点高度选择。 */
        private final double[] surface;

        /**
         * 功能：以三维前缀和膨胀占用格，保证整格内任意位置和任意机头方向均有整机净空。
         * 引用 MC API：无；后台调用；输入数组由采集器移交后不再修改。
         * 副作用：仅本实例数组；中断由调用方在构造前后及搜索中处理。
         * 排查：洞口宽度足够却拒绝时检查四格离散造成的额外保守余量，不缩小机体包络。
         */
        Volume(double x, double y, double z, int nx, int ny, int nz, byte[] occupied,
               List<NavigationRoute.Circle> circles, NavigationRoute.Settings settings, double cruise, double[] surface) {
            this.x=x; this.y=y; this.z=z; this.nx=nx; this.ny=ny; this.nz=nz;
            this.circles=List.copyOf(circles); this.settings=settings; this.cruise=cruise;
            this.surface=surface;
            free=new boolean[occupied.length]; covered=new boolean[occupied.length];
            proximity=new byte[occupied.length];
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
                double circlePenalty=0;
                for(var circle:circles) {
                    double gap=Math.hypot(x+ix*CELL-circle.x(),z+iz*CELL-circle.z())
                            -circle.radius()-Math.max(ENVELOPE,settings.clearance());
                    circlePenalty=Math.max(circlePenalty,Math.clamp(1-gap/COMFORT_MARGIN,0,1)*.75);
                }
                // 方块仍按整格膨胀；禁飞圆改为每条实际线段精确检查，避免四格栅格吞掉合法贴边终点。
                for(int iy=pad;iy<ny-pad;iy++) {
                    int at=index(ix,iy,iz);
                    free[at]=count(sum,sx,layer,ix-pad,iy-pad,iz-pad,ix+pad+1,iy+pad+1,iz+pad+1)==0;
                    covered[at]=count(sum,sx,layer,ix-pad,iy+pad+1,iz-pad,ix+pad+1,ny,iz+pad+1)>0;
                    int comfort=pad+3;
                    boolean nearTerrain=count(sum,sx,layer,Math.max(0,ix-comfort),Math.max(0,iy-comfort),Math.max(0,iz-comfort),
                            Math.min(nx,ix+comfort+1),Math.min(ny,iy+comfort+1),Math.min(nz,iz+comfort+1))>0;
                    proximity[at]=(byte)Math.round(100*Math.min(1,circlePenalty+(nearTerrain?.8:0)));
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
        /** 功能：查询局部已知最高地表的平均值；无 MC API，未知列不被当作低地。 */
        double meanSurface(NavigationRoute.Point p) {
            int cx=(int)Math.round((p.x()-x)/CELL),cz=(int)Math.round((p.z()-z)/CELL),radius=SURFACE_RADIUS/CELL;
            double sum=0;int count=0;
            for(int iz=Math.max(0,cz-radius);iz<=Math.min(nz-1,cz+radius);iz++)
                for(int ix=Math.max(0,cx-radius);ix<=Math.min(nx-1,cx+radius);ix++) {
                    double height=surface[iz*nx+ix];
                    if(Double.isFinite(height)){sum+=height;count++;}
                }
            return count==0?Double.NaN:sum/count;
        }
        /**
         * 功能：直达安全时保持原高度，确有地形阻挡才一次选择覆盖本航段的爬升高度；无 MC API。
         * 禁飞圆不可靠爬升越过，交由同高度绕行；均值参考不足以越过尖峰时逐格验证更高走廊。
         * 返回高度仍受世界顶界限制；没有安全的原地爬升走廊时保留参考高度交给三维绕障搜索。
         */
        double cruiseAltitude(Point start,NavigationRoute.Point goal) {
            Point levelEnd=new Point(goal.x(),start.y,goal.z());
            if(!airspaceClear(start,levelEnd)||clear(start,levelEnd)&&!covered(levelEnd))return start.y;
            int steps=Math.max(1,(int)Math.ceil(NavigationRoute.distance(start.horizontal(),goal)/SURFACE_STEP));
            double altitude=start.y;
            for(int i=0;i<=steps;i++) {
                double t=(double)i/steps;
                double mean=meanSurface(new NavigationRoute.Point(start.x+(goal.x()-start.x)*t,start.z+(goal.z()-start.z)*t));
                if(Double.isFinite(mean))altitude=Math.max(altitude,mean+CRUISE_CLEARANCE);
            }
            double ceiling=y+(ny-1-Math.ceil(ENVELOPE/CELL))*CELL;
            altitude=Math.min(altitude,ceiling);
            for(double candidate=altitude;candidate<=ceiling;candidate+=CELL) {
                if(Thread.currentThread().isInterrupted())return altitude;
                Point up=new Point(start.x,candidate,start.z),end=new Point(goal.x(),candidate,goal.z());
                if(clear(start,up)&&clear(up,end)&&!covered(end))return candidate;
            }
            return altitude;
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
         * 功能：连续段飞行时间加高差、贴障碍及顶棚风险，搜索、直连和平滑统一计价，单位 tick。
         * 无 MC API；后台调用。整段采用跟随器相同的最低限速，顶棚部分另加风险，每次进入单独收费。
         * 排查：不能只给洞内采样降速却让跟随器在整条长段限速，否则穿洞时间会被低估。
         */
        double cost(Point a,Point b) {
            int steps=Math.max(1,(int)Math.ceil(a.distance(b)/(CELL*.5)));
            int last=cell(a),roofSteps=0,entries=0;
            if(!free(last))return Double.POSITIVE_INFINITY;
            boolean wasCovered=covered[last];
            double proximitySum=0;
            for(int i=1;i<=steps;i++) {
                if(Thread.currentThread().isInterrupted())return Double.POSITIVE_INFINITY;
                int next=cell(interpolate(a,b,(double)i/steps));
                if(!free(next))return Double.POSITIVE_INFINITY;
                boolean roof=boxCovered(last,next);
                if(roof)roofSteps++;
                if(!wasCovered&&roof)entries++;
                wasCovered=roof;last=next;
                proximitySum+=Byte.toUnsignedInt(proximity[next])/100.0;
                // 巡航净高是软偏好；必要的起降、洞口脱离和世界顶界仍由硬净空决定是否可飞。
                double ground=surface[(next/nx%nz)*nx+next%nx];
                if(Double.isFinite(ground))proximitySum+=LOW_ALTITUDE_WEIGHT*Math.clamp((ground+CRUISE_CLEARANCE-y-next/(nx*nz)*CELL)/CRUISE_CLEARANCE,0,1);
            }
            double speed=roofSteps>0?Math.min(cruise,COVERED_SPEED):cruise;
            double vertical=Math.abs(b.y-a.y)/(b.y>=a.y?CLIMB_SPEED:DESCENT_SPEED);
            return travel(a,b,speed)*(1+(COVERED_WEIGHT-1)*roofSteps/steps+proximitySum/steps)
                    +VERTICAL_WEIGHT*vertical+ENTRY_COST*entries;
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
     * 功能：沿全局引导选择最多九十度累计转向的空间航段，不再把每条切圆短弦都拆成规划接缝。
     * 无 MC API；服务端创建地形请求时调用，返回单调前进的引导索引，不修改输入列表。
     * 重规划时 first 是未抵达的旧局部终点；即使当前位置因漂移暂不可直达，也保留该绕行目标。
     */
    static int goalNode(NavigationRoute.Point start,List<NavigationRoute.Point> route,int first,double look) {
        var from=start;double distance=0,turn=0,previousX=0,previousZ=0;int selected=first;
        for(int at=first;at<route.size();at++) {
            double segment=NavigationRoute.distance(from,route.get(at));
            double dx=route.get(at).x()-from.x(),dz=route.get(at).z()-from.z();
            if(segment>1e-6) {
                dx/=segment;dz/=segment;
                if(previousX!=0||previousZ!=0)turn+=Math.acos(Math.clamp(previousX*dx+previousZ*dz,-1,1));
            }
            if(at>first&&(distance+segment>look||turn>Math.PI/2))break;
            distance+=segment;from=route.get(at);selected=at;
            if(segment>1e-6){previousX=dx;previousZ=dz;}
        }
        return selected;
    }
    /**
     * 功能：直接比较侧绕与升降，不预先强制爬到越障高度；无 MC API，后台调用。
     * 终点最高地表由主线程高度图提供；优先上方三十二格，无净空时在十六至四十八格内选择。
     * 中途终点净空允许时保持当前高度，否则才选爬升参考；窄走廊遇障碍必须扩大采集后再比较。
     */
    static Result profile(Volume v,Point start,NavigationRoute.Point goal,boolean finalGoal,double ground,boolean directOnly) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,0);
        Point end=new Point(goal.x(),start.y,goal.z());
        if(finalGoal) {
            end=null;
            for(int offset=0;offset<=ARRIVAL_RANGE&&end==null;offset+=CELL) {
                for(int sign:new int[]{1,-1}) {
                    Point candidate=new Point(goal.x(),ground+ARRIVAL_CLEARANCE+sign*offset,goal.z());
                    if(v.clear(candidate,candidate)&&!v.covered(candidate)){end=candidate;break;}
                }
            }
            if(end==null)return failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
        }else if(!v.clear(end,end)||v.covered(end)) {
            end=new Point(goal.x(),v.cruiseAltitude(start,goal),goal.z());
        }
        int budget=Math.min(MAX_NODES,v.settings.maxNodes());
        return directOnly?direct(v,start,end):plan(v,start,end,budget);
    }

    /**
     * 功能：从重叠接点按旧航段切线进入续段，跳过旧末端回接折线；无 MC API，后台调用。
     * 三次曲线两端分别沿入段和出段切线，每个两格以内的小段检查整机净空。
     * 只替换续段前一百二十八格；找不到安全切线连接就拒绝该续段，不能用尖角强行接上。
     */
    static Result alignEntry(Result result,Point incoming) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,result.volume(),result.expanded());
        if(incoming==null||result.status()!=NavigationRoute.Status.SUCCESS||result.path().size()<2)return result;
        List<Point> path=result.path();Point start=path.getFirst();
        double length=incoming.distance(start);
        if(length<1e-6)return result;
        Point first=path.get(1);
        double agreement=((start.x-incoming.x)*(first.x-start.x)+(start.y-incoming.y)*(first.y-start.y)
                +(start.z-incoming.z)*(first.z-start.z))/(length*start.distance(first));
        if(agreement>1-1e-6)return result;
        int last=1;double along=0;
        while(last+1<path.size()&&along+path.get(last-1).distance(path.get(last))<=SHORTCUT_DISTANCE) {
            along+=path.get(last-1).distance(path.get(last));last++;
        }
        // 从较远的接回点开始，给绕山后的大转向足够半径；窄通道逐步缩短控制柄。
        for(int at=Math.max(1,last-1);at>=1;at-=Math.max(1,(last-1)/16)) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,result.volume(),result.expanded());
            Point end=path.get(at),after=path.get(Math.min(at+1,path.size()-1));
            if(end.distance(after)<1e-6) {
                Point before=path.get(at-1);
                after=new Point(end.x+end.x-before.x,end.y+end.y-before.y,end.z+end.z-before.z);
            }
            double handle=Math.min(TURN_CUT,start.distance(end)/3);
            // 极短终点也保留至少四分之一格的控制柄，避免仅有两个采样点时无法连接。
            for(int attempt=0;attempt<5&&handle>=.25;attempt++,handle*=.5) {
                Point c1=new Point(start.x+(start.x-incoming.x)*handle/length,
                        start.y+(start.y-incoming.y)*handle/length,start.z+(start.z-incoming.z)*handle/length);
                double outgoing=end.distance(after);
                Point c2=new Point(end.x-(after.x-end.x)*handle/outgoing,
                        end.y-(after.y-end.y)*handle/outgoing,end.z-(after.z-end.z)*handle/outgoing);
                int steps=Math.max(8,(int)Math.ceil((start.distance(c1)+c1.distance(c2)+c2.distance(end))/SPACING));
                List<Point> output=new ArrayList<>();output.add(start);boolean safe=true;
                for(int n=1;n<=steps;n++) {
                    double t=(double)n/steps;
                    Point a=interpolate(start,c1,t),b=interpolate(c1,c2,t),c=interpolate(c2,end,t);
                    Point point=interpolate(interpolate(a,b,t),interpolate(b,c,t),t);
                    if(!result.volume().clear(output.getLast(),point)){safe=false;break;}
                    append(output,point);
                }
                if(!safe)continue;
                output.addAll(path.subList(at+1,path.size()));
                double cost=0;
                for(int i=1;i<output.size();i++)cost+=result.volume().cost(output.get(i-1),output.get(i));
                if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,result.volume(),result.expanded());
                return new Result(NavigationRoute.Status.SUCCESS,output,result.volume(),result.expanded(),cost);
            }
        }
        return failure(NavigationRoute.Status.TRAJECTORY_UNSAFE,result.volume(),result.expanded());
    }
    /** 功能：只在整机直线走廊已验证且无顶棚时交付快速路线；失败要求扩大采集范围，不猜测侧方地形。 */
    static Result direct(Volume v,Point start,Point goal) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,0);
        Point end=goal;
        if(!v.covered(end)&&v.clear(start,end)&&v.speed(start,end)==v.cruise)return success(v,List.of(start,end),0);
        return failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
    }
    /**
     * 功能：先尝试八邻同高度侧绕，再按共享预算执行二十六邻空间搜索，出口必须无顶棚。
     * 无 MC API；后台调用；输入为独占快照和三维阶段端点，高度由飞行剖面约束。
     * 返回：完整三维航路或精度/预算失败；副作用仅局部数组。
     * 排查：局部矩形外可能仍有路，GRID_RESOLUTION 绝不等同真实封闭。
     */
    static Result plan(Volume v,Point start,Point goal,int budget) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,0);
        int begin=v.cell(start),goalCell=v.cell(goal);
        if(!v.free(begin)||goalCell<0||!v.airspaceClear(start,start)
                ||!v.airspaceClear(goal,goal))
            return failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
        // 露天空域整段验证后直接交付，不为平直航线分配搜索堆或展开三维网格。
        Result direct=direct(v,start,goal);
        if(direct.status()!=NavigationRoute.Status.GRID_RESOLUTION)return direct;
        // 最终悬停高度可以低于巡航高度；先侧绕再在终点升降，仍应参与同高度方案比较。
        Point levelGoal=new Point(goal.x,start.y,goal.z);
        Result level=v.clear(levelGoal,goal)&&!v.covered(levelGoal)
                ?search(v,start,levelGoal,true,Math.min(PLANAR_NODES,budget/4))
                :failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
        if(level.status()==NavigationRoute.Status.INTERRUPTED)return level;
        if(level.status()==NavigationRoute.Status.SUCCESS&&levelGoal.distance(goal)>1e-6) {
            List<Point> path=new ArrayList<>(level.path());path.add(goal);
            level=success(v,path,level.expanded());
        }
        if(level.status()==NavigationRoute.Status.SUCCESS&&level.costTicks()<=heuristic(start,goal,v.cruise)*SIDE_DETOUR_RATIO)
            return level;
        Result spatial=search(v,start,goal,false,budget-level.expanded());
        if(spatial.status()==NavigationRoute.Status.INTERRUPTED)return spatial;
        Result chosen=level.status()==NavigationRoute.Status.SUCCESS
                &&(spatial.status()!=NavigationRoute.Status.SUCCESS||level.costTicks()<=spatial.costTicks())?level:spatial;
        return new Result(chosen.status(),chosen.path(),v,level.expanded()+spatial.expanded(),chosen.costTicks());
    }

    /**
     * 功能：共用八邻同高度/二十六邻空间搜索实现，两个阶段共享调用方给定的节点预算。
     * 无 MC API；后台执行；所有高度都检查同一组无限高圆柱，重叠圆按并集阻挡。
     * 返回完整安全路线或明确失败；同高度阶段失败不能被当作三维不可达。
     */
    private static Result search(Volume v,Point start,Point goal,boolean levelOnly,int limit) {
        int begin=v.cell(start);
        NavigationTerrainRoute open=new NavigationTerrainRoute(v.free.length,v.nx*v.nz,begin);
        open.cost[begin]=0;open.parent[begin]=begin;open.offer(begin,HEURISTIC_WEIGHT*heuristic(start,goal,v.cruise));
        int expanded=0;
        while(open.size>0) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
            if(expanded++>=limit)return failure(NavigationRoute.Status.NODE_LIMIT,v,expanded-1);
            int at=open.poll();Point p=at==begin?start:v.point(at);
            // 真实终点可能在合法的半格内而格心落入禁区，允许从相邻格完整验证后接入终点。
            if(p.distance(goal)<=CELL*Math.sqrt(3)&&!v.covered[at]) {
                Point end=goal;
                if(v.clear(p,end)&&!v.covered(end))return open.finish(v,at,start,end,expanded);
            }
            int ix=at%v.nx,iy=at/(v.nx*v.nz),iz=at/v.nx%v.nz;
            for(int dy=levelOnly?0:-1;dy<=(levelOnly?0:1);dy++) for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
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
        // 阶段拼接前恢复直线长边，不能让两格采样把可用圆角切距限制到不足一格。
        List<Point> compact=new ArrayList<>();
        for(Point point:raw) {
            if(!compact.isEmpty()&&compact.getLast().distance(point)<1e-6)continue;
            while(compact.size()>1) {
                Point a=compact.get(compact.size()-2),b=compact.getLast();
                if(Math.abs(a.distance(b)+b.distance(point)-a.distance(point))>1e-6)break;
                compact.removeLast();
            }
            compact.add(point);
        }
        raw=compact;
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
    /** 功能：三维阶段终点的同步飞行时间下界；无 MC API，不漏算爬升或下降距离。 */
    private static double heuristic(Point p,Point goal,double cruise) {
        return travel(p,goal,cruise);
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
