package org.DJB.no_24_heli.flight;

import java.util.List;

/**
 * 文件：覆盖全部扩张圆的动态网格，整格接触圆即不可通行。
 * 端侧：COMMON；规划线程创建，无 Minecraft API、无注册项。
 * 排查：边界封死查留白，合法点障碍格查格长，规模失败查 CELL_LIMIT。
 */
final class NavigationGrid {
    /** 动态矩形原点和格长，单位为格，创建后不可变。 */
    private final double minX,minZ,cellSize;
    /** 网格列数、行数，总单元数受 maxCells 限制。 */
    private final int width,height;
    /** 障碍圆并集掩码，true 表示整格不可通行，创建后只读。 */
    private final boolean[] blocked;
    /** 功能：接收已验证的几何和障碍数组；无 MC API、无外部副作用。 */
    private NavigationGrid(double minX,double minZ,double cellSize,int width,int height,boolean[] blocked) {
        this.minX=minX;this.minZ=minZ;this.cellSize=cellSize;
        this.width=width;this.height=height;this.blocked=blocked;
    }
    /** 构建结果；失败 grid 为 null，仅 SUCCESS 分支读取网格。 */
    record Build(NavigationRoute.Status status,NavigationGrid grid) {}
    /**
     * 单次几何与节点预算；无 MC API，每次规划独占，工作线程响应中断。
     * 排查：几何循环耗尽与 A* 耗尽分别报告，不将失败冒充禁区封闭。
     */
    static final class Budget {
        /** 每格折算最多32次几何操作，以限制大量重叠圆扫描。 */
        private static final int OPERATIONS_PER_CELL=32;
        /** 小网格仍允许512个雷达的基础交叠检查。 */
        private static final int MIN_OPERATIONS=262_144;
        /** 本次几何操作上限和 A* 节点上限。 */
        private final long geometryLimit;
        private final int nodeLimit;
        /** 已执行操作和两端累计展开数，不跨任务累积。 */
        private long geometryOperations;
        private int expandedNodes;
        /** 功能：生成有限独立预算；无 MC API、无副作用。 */
        Budget(int maxCells,int maxNodes) {
            geometryLimit=Math.max(MIN_OPERATIONS,(long)maxCells*OPERATIONS_PER_CELL);nodeLimit=maxNodes;
        }
        /** 功能：记账一次几何操作并响应中断；无 MC API，只修改本次计数。 */
        NavigationRoute.Status geometry() {
            if(Thread.currentThread().isInterrupted()) return NavigationRoute.Status.INTERRUPTED;
            if(geometryOperations>=geometryLimit) return NavigationRoute.Status.GEOMETRY_LIMIT;
            geometryOperations++;return NavigationRoute.Status.SUCCESS;
        }
        /** 功能：记账一个展开节点并响应中断；无 MC API，只修改本次计数。 */
        NavigationRoute.Status node() {
            if(Thread.currentThread().isInterrupted()) return NavigationRoute.Status.INTERRUPTED;
            if(expandedNodes>=nodeLimit) return NavigationRoute.Status.NODE_LIMIT;
            expandedNodes++;return NavigationRoute.Status.SUCCESS;
        }
        /** 功能：返回合计实际展开数；无 MC API、无副作用。 */
        int expandedNodes() { return expandedNodes; }
        /** 功能：返回剩余几何预算，圆交叠图在分配数组前检查最低工作量。 */
        long remainingGeometry() { return geometryLimit-geometryOperations; }
    }
    /**
     * 功能：动态覆盖起终点和所有扩张圆，再栅格化闭圆并集。
     * 引用 MC API：无。端侧/线程：规划工作线程。参数：不可变快照和设置。
     * 返回：网格或独立资源状态。副作用：只分配本次掩码。
     * 故障排查：远圆使网格过大须报 CELL_LIMIT，禁止截取圆或矩形边缘充当围墙。
     */
    static Build build(NavigationRoute.Point start,NavigationRoute.Point goal,
                       List<NavigationRoute.Circle> zones,NavigationRoute.Settings s,Budget budget) {
        double minX=Math.min(start.x(),goal.x()),minZ=Math.min(start.z(),goal.z());
        double maxX=Math.max(start.x(),goal.x()),maxZ=Math.max(start.z(),goal.z());
        for(NavigationRoute.Circle c:zones) {
            NavigationRoute.Status limit=budget.geometry();
            if(limit!=NavigationRoute.Status.SUCCESS) return new Build(limit,null);
            double r=c.radius()+s.clearance();
            minX=Math.min(minX,c.x()-r);minZ=Math.min(minZ,c.z()-r);
            maxX=Math.max(maxX,c.x()+r);maxZ=Math.max(maxZ,c.z()+r);
        }
        // 至少两格留白，外围必须完整开放，不能由搜索矩形制造封闭。
        double margin=Math.max(s.boundaryMargin(),s.cellSize()*2.0);
        minX-=margin;minZ-=margin;
        double columns=Math.ceil((maxX+margin-minX)/s.cellSize());
        double rows=Math.ceil((maxZ+margin-minZ)/s.cellSize());
        if(!Double.isFinite(columns)||!Double.isFinite(rows)||columns<1||rows<1
                ||columns*rows>s.maxCells()||columns*rows>Integer.MAX_VALUE)
            return new Build(NavigationRoute.Status.CELL_LIMIT,null);
        NavigationGrid grid=new NavigationGrid(minX,minZ,s.cellSize(),(int)columns,(int)rows,
                new boolean[(int)(columns*rows)]);
        NavigationRoute.Status status=grid.rasterize(zones,s.clearance(),budget);
        return new Build(status,status==NavigationRoute.Status.SUCCESS?grid:null);
    }
    /** 功能：逐圆扫描包围矩形，闭格与扩张圆接触则合入障碍并集。 */
    private NavigationRoute.Status rasterize(List<NavigationRoute.Circle> zones,double clearance,Budget budget) {
        for(NavigationRoute.Circle c:zones) {
            double r=c.radius()+clearance;
            int firstX=Math.max(0,(int)Math.floor((c.x()-r-minX)/cellSize)-1);
            int firstZ=Math.max(0,(int)Math.floor((c.z()-r-minZ)/cellSize)-1);
            int lastX=Math.min(width-1,(int)Math.floor((c.x()+r-minX)/cellSize));
            int lastZ=Math.min(height-1,(int)Math.floor((c.z()+r-minZ)/cellSize));
            for(int z=firstZ;z<=lastZ;z++) for(int x=firstX;x<=lastX;x++) {
                NavigationRoute.Status limit=budget.geometry();if(limit!=NavigationRoute.Status.SUCCESS)return limit;
                int index=z*width+x;if(blocked[index])continue;
                double left=minX+x*cellSize,bottom=minZ+z*cellSize;
                double dx=Math.max(0.0,Math.max(left-c.x(),c.x()-left-cellSize));
                double dz=Math.max(0.0,Math.max(bottom-c.z(),c.z()-bottom-cellSize));
                blocked[index]=Math.hypot(dx,dz)<=r;
            }
        }
        return NavigationRoute.Status.SUCCESS;
    }
    /** 功能：将已覆盖的真实坐标映射到所在格；无 MC API、无副作用。 */
    int cell(NavigationRoute.Point p) {
        int x=(int)Math.floor((p.x()-minX)/cellSize),z=(int)Math.floor((p.z()-minZ)/cellSize);
        return z*width+x;
    }
    /** 功能：返回格心水平航点；无 MC API、无副作用。 */
    NavigationRoute.Point center(int index) {
        return new NavigationRoute.Point(minX+(index%width+0.5)*cellSize,minZ+(index/width+0.5)*cellSize);
    }
    /** 功能：查询障碍掩码；无 MC API、无副作用。 */
    boolean blocked(int index) { return blocked[index]; }
    /** 功能：返回网格总单元数；无 MC API、无副作用。 */
    int size() { return blocked.length; }
    /** 功能：返回列数用于四邻边界判断；无 MC API、无副作用。 */
    int width() { return width; }
}
