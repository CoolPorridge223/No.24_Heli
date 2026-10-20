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
                var a=new NavigationRoute.Point(x+(ix-.5)*CELL,z+(iz-.5)*CELL);
                // 外接圆覆盖整格四角；不使用当前偏航包围盒，防止转向时净空突然缩小。
                boolean legal=true;
                for(var circle:circles) {
                    double dx=Math.max(0,Math.max(a.x()-circle.x(),circle.x()-a.x()-CELL));
                    double dz=Math.max(0,Math.max(a.z()-circle.z(),circle.z()-a.z()-CELL));
                    if(Math.hypot(dx,dz)<=circle.radius()+Math.max(ENVELOPE,settings.clearance())) { legal=false; break; }
                }
                if(!legal)continue;
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
            for(int i=0;i<=steps;i++)if(covered(interpolate(a,b,(double)i/steps)))return Math.min(cruise,COVERED_SPEED);
            return cruise;
        }
        /**
         * 功能：连续线段的保守超覆盖检查，每两格检查所有跨过的配置格，禁止边角擦墙。
         * 无 MC API；后台平滑与服务端短前视复用。整格已含机体包络，所以不是只测航点。
         * 返回：全段安全；无副作用；未知格、禁区或中断均拒绝，不能跳过不安全段。
         */
        boolean clear(Point a,Point b) {
            if(!NavigationRoute.clear(a.horizontal(),b.horizontal(),circles,settings))return false;
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
        /** 功能：检查相邻配置格包围体，覆盖斜穿边缘时所有正交邻格；无 MC API。 */
        boolean boxClear(int a,int b) {
            int ax=a%nx,ay=a/(nx*nz),az=a/nx%nz,bx=b%nx,by=b/(nx*nz),bz=b/nx%nz;
            for(int iy=Math.min(ay,by);iy<=Math.max(ay,by);iy++)
                for(int iz=Math.min(az,bz);iz<=Math.max(az,bz);iz++)
                    for(int ix=Math.min(ax,bx);ix<=Math.max(ax,bx);ix++)if(!free[index(ix,iy,iz)])return false;
            return true;
        }
        /** 功能：连续段飞行时间加顶棚风险；用于平滑时禁止抄近路改穿洞，单位 tick。 */
        double cost(Point a,Point b) {
            int steps=Math.max(1,(int)Math.ceil(a.distance(b)/(CELL*.5)));
            double total=0; Point last=a; boolean wasCovered=covered(a);
            for(int i=1;i<=steps;i++) {
                Point p=interpolate(a,b,(double)i/steps); boolean roof=covered(p)||covered(last);
                total+=travel(last,p,roof?Math.min(cruise,COVERED_SPEED):cruise)*(roof?COVERED_WEIGHT:1);
                if(!wasCovered&&covered(p))total+=ENTRY_COST;
                wasCovered=covered(p);last=p;
            }
            return total;
        }
    }

    /** 受体素总数限制的索引最小堆，每格至多一个开放项，不使用无界重复队列。 */
    private final int[] heap,position,parent;
    /** g 与 f 以 tick 为单位；所有边权非负，水平距离/巡航速度是相容下界。 */
    private final double[] cost,score;
    /** 堆中有效项数量，仅当前工作线程修改。 */
    private int size;
    /** 功能：为本次有界三维搜索分配数组，无 MC API、无外部副作用。 */
    private NavigationTerrainRoute(int cells) {
        heap=new int[cells];position=new int[cells];parent=new int[cells];cost=new double[cells];score=new double[cells];
        Arrays.fill(position,-1);Arrays.fill(parent,-1);Arrays.fill(cost,Double.POSITIVE_INFINITY);
    }
    /**
     * 功能：二十六邻三维 A* 同时比较侧绕、升降与穿洞，出口必须在已确认无顶棚的位置。
     * 无 MC API；后台调用；输入为独占快照和真实起终点，终点高度自由选择，不擅自自动降落。
     * 返回：完整三维航路或精度/预算失败；副作用仅局部数组。
     * 排查：局部矩形外可能仍有路，GRID_RESOLUTION 绝不等同真实封闭。
     */
    static Result plan(Volume v,Point start,NavigationRoute.Point goal) {
        if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,0);
        int begin=v.cell(start),goalCell=v.cell(new Point(goal.x(),start.y,goal.z()));
        if(!v.free(begin)||goalCell<0)return failure(NavigationRoute.Status.GRID_RESOLUTION,v,0);
        NavigationTerrainRoute open=new NavigationTerrainRoute(v.free.length);
        open.cost[begin]=0;open.parent[begin]=begin;open.offer(begin,heuristic(start,goal,v.cruise));
        int expanded=0,limit=Math.min(MAX_NODES,v.settings.maxNodes());
        while(open.size>0) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
            if(expanded++>=limit)return failure(NavigationRoute.Status.NODE_LIMIT,v,expanded-1);
            int at=open.poll();Point p=v.point(at);
            if(at%(v.nx*v.nz)==goalCell%(v.nx*v.nz)&&!v.covered[at]) {
                Point end=new Point(goal.x(),p.y,goal.z());
                if(v.clear(p,end)&&!v.covered(end))return open.finish(v,at,start,end,expanded);
            }
            int ix=at%v.nx,iy=at/(v.nx*v.nz),iz=at/v.nx%v.nz;
            for(int dy=-1;dy<=1;dy++) for(int dz=-1;dz<=1;dz++) for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dy==0&&dz==0||ix+dx<0||iy+dy<0||iz+dz<0||ix+dx>=v.nx||iy+dy>=v.ny||iz+dz>=v.nz)continue;
                int next=v.index(ix+dx,iy+dy,iz+dz);
                if(!v.free[next]||open.position[next]==-2||!v.boxClear(at,next))continue;
                boolean roof=v.covered[at]||v.covered[next];Point q=v.point(next);
                double weight=travel(p,q,roof?Math.min(v.cruise,COVERED_SPEED):v.cruise)*(roof?COVERED_WEIGHT:1);
                if(!v.covered[at]&&v.covered[next])weight+=ENTRY_COST;
                double candidate=open.cost[at]+weight;
                if(candidate>=open.cost[next])continue;
                open.cost[next]=candidate;open.parent[next]=at;
                open.offer(next,candidate+heuristic(q,goal,v.cruise));
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
        int anchor=0;double total=0;
        while(anchor+1<raw.size()) {
            if(Thread.currentThread().isInterrupted())return failure(NavigationRoute.Status.INTERRUPTED,v,expanded);
            int best=anchor+1;double original=0;
            // 每次最多比较三十二个后继，防止长曲折通道的平滑退化为平方扫描。
            for(int next=anchor+1;next<raw.size()&&next<=anchor+32;next++) {
                original+=v.cost(raw.get(next-1),raw.get(next));
                if(v.clear(raw.get(anchor),raw.get(next))&&v.cost(raw.get(anchor),raw.get(next))<=original+1e-6)best=next;
            }
            if(!v.clear(raw.get(anchor),raw.get(best)))return failure(NavigationRoute.Status.TRAJECTORY_UNSAFE,v,expanded);
            total+=v.cost(raw.get(anchor),raw.get(best));
            // 搜索窗口仅限制运算，不应把同一直线每一百二十八格拆成需要减速的假转角。
            if(output.size()>1) {
                Point before=output.get(output.size()-2),last=output.getLast(),next=raw.get(best);
                if(Math.abs(before.distance(last)+last.distance(next)-before.distance(next))<1e-6)output.removeLast();
            }
            output.add(raw.get(best));anchor=best;
        }
        return new Result(NavigationRoute.Status.SUCCESS,output,v,expanded,total);
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
        while(at>0) {int p=(at-1)/2;if(score[heap[p]]<=priority)break;swap(at,p);at=p;}
    }
    /** 功能：弹出最小 f 值并关闭该格；无 MC API。 */
    private int poll() {
        int result=heap[0];swap(0,--size);position[result]=-2;int at=0;
        while(at*2+1<size) {int child=at*2+1;if(child+1<size&&score[heap[child+1]]<score[heap[child]])child++;
            if(score[heap[at]]<=score[heap[child]])break;swap(at,child);at=child;}
        return result;
    }
    /** 功能：交换堆元素并同步反向索引；无 MC API。 */
    private void swap(int a,int b) {int cell=heap[a];heap[a]=heap[b];heap[b]=cell;position[heap[a]]=a;position[heap[b]]=b;}
}
