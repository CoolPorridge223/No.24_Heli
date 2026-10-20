package org.DJB.no_24_heli.flight;

import java.util.List;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

/**
 * 文件：逐 tick 采集有限地形体素，归属 flight 飞控模块；端侧 SERVER，无注册项。
 * 引用 net.minecraft.server.world.ServerWorld 查询已加载区块、方块碰撞和流体，绝不把世界交给后台。
 * 生命周期：NavigationPilot 创建并推进；完成后仅移交坐标和占用数组，取消直接丢弃。
 * 排查：等待时间查采集预算；未知区域一直停车查区块加载，禁止调用同步生成区块接口。
 */
final class NavigationTerrainSnapshot {
    /** 起终点矩形四侧各留九十六格用于侧绕；整个世界高度可参与升降选择。 */
    private static final int SIDE_MARGIN = 96;
    /** 全服务器同一 tick 共用两毫秒采集预算，每次调用另限最多一万六千三百八十四格。 */
    private static final long TICK_NANOS = 2_000_000;
    private static final int SLICE_CELLS = 16384;
    /** 单服务端主线程的采集计时，不持有世界；不同维度共享预算，不随飞机数量放大。 */
    private static int budgetTick = Integer.MIN_VALUE;
    private static long spentNanos;
    /** 格心原点和尺寸；起点严格落在格心，避免悬停十格时因向下取整误报嵌地。 */
    private final double x,y,z;
    private final int nx,ny,nz;
    /** 原始占用数组，一字节一格；完成后不再写，后台构建配置空间。 */
    private final byte[] occupied;
    /** 纯坐标请求数据，不引用实体、世界、区块。 */
    private final NavigationTerrainRoute.Point start;
    private final NavigationRoute.Point goal;
    private final List<NavigationRoute.Circle> circles;
    private final NavigationRoute.Settings settings;
    private final double cruise;
    /** 列采集游标及当前列已加载/最高地形缓存；从低到高采集。 */
    private int column,level,top;
    private boolean loaded;

    /**
     * 功能：建立有界矩形规格；超过 pathMaxCells 时只记录失败，不分配大数组。
     * MC API：ServerWorld#getBottomY/#getTopY 限定维度高度；仅服务端调用。
     * 参数：已停稳起点、局部终点与配置快照；副作用：只分配采集状态。
     * 排查：CELL_LIMIT 需缩短航程或调整预算，不偷偷截掉障碍。
     */
    NavigationTerrainSnapshot(ServerWorld world,NavigationTerrainRoute.Point start,NavigationRoute.Point goal,
                              List<NavigationRoute.Circle> circles,NavigationRoute.Settings settings,double cruise) {
        this.start=start;this.goal=goal;this.circles=List.copyOf(circles);this.settings=settings;this.cruise=cruise;
        int cell=NavigationTerrainRoute.CELL;
        x=start.x()+Math.floor(Math.min(0,goal.x()-start.x())/cell)*cell-SIDE_MARGIN;
        z=start.z()+Math.floor(Math.min(0,goal.z()-start.z())/cell)*cell-SIDE_MARGIN;
        y=start.y()-Math.floor((start.y()-world.getBottomY())/cell)*cell;
        nx=(int)Math.ceil((Math.max(start.x(),goal.x())+SIDE_MARGIN-x)/cell)+1;
        nz=(int)Math.ceil((Math.max(start.z(),goal.z())+SIDE_MARGIN-z)/cell)+1;
        // 顶部不采集跨出世界的半格，避免把人为世界上界识别成覆盖所有天空的顶棚。
        ny=(int)Math.floor((world.getTopY()-cell*.5-y)/cell)+1;
        long count=(long)nx*ny*nz;
        occupied=count<=0||count>settings.maxCells()||count>Integer.MAX_VALUE?null:new byte[(int)count];
    }
    /** 功能：报告预算失败；无 MC API、无副作用，失败时禁止提交后台。 */
    boolean tooLarge() { return occupied==null; }
    /** 功能：检查是否完成采集；无 MC API、无副作用。 */
    boolean complete() { return occupied!=null&&column>=nx*nz; }
    /**
     * 功能：在共享时间预算内推进采集，未知区块、水和碰撞方块均视为障碍。
     * MC API：ServerWorld#getBlockCollisions 与 containsFluid 查询原版形状和流体；主线程调用。
     * 返回：无；副作用仅快照和预算。高度图只用于跳过确定在最高障碍上方的空气，不覆盖洞穴结构。
     * 排查：不能将高度图以下整列填实，否则洞穴永远不能成为备选路线。
     */
    void step(ServerWorld world,HelicopterEntity heli) {
        int tick=world.getServer().getTicks();
        if(tick!=budgetTick) {budgetTick=tick;spentNanos=0;}
        if(spentNanos>=TICK_NANOS)return;
        long began=System.nanoTime(),deadline=began+TICK_NANOS-spentNanos;
        int checked=0,cell=NavigationTerrainRoute.CELL;
        while(!complete()&&checked++<SLICE_CELLS&&System.nanoTime()<deadline) {
            int ix=column%nx,iz=column/nx;
            double cx=x+ix*cell,cz=z+iz*cell,cy=y+level*cell;
            if(level==0)prepareColumn(world,cx,cz);
            // 占用数组初始为空，已加载列在最高碰撞形状上方可整段跳过，不逐格查询天空。
            if(loaded&&cy-cell*.5>top+2) {level=0;column++;continue;}
            Box box=new Box(cx-cell*.5,cy-cell*.5,cz-cell*.5,cx+cell*.5,cy+cell*.5,cz+cell*.5);
            boolean blocked=!loaded||box.minY<world.getBottomY()||box.maxY>world.getTopY()
                    ||!world.getWorldBorder().contains(box);
            if(!blocked&&box.minY<=top+2)blocked=world.getBlockCollisions(heli,box).iterator().hasNext()||world.containsFluid(box);
            occupied[(level*nz+iz)*nx+ix]=(byte)(blocked?1:0);
            if(++level>=ny) {level=0;column++;}
        }
        spentNanos+=System.nanoTime()-began;
    }
    /**
     * 功能：检查含邻块碰撞形状的一圈区块并读取当前四格方柱内最高高度。
     * MC API：区块管理器 isChunkLoaded 不加载区块；MOTION_BLOCKING 含树叶和流体。
     * 仅服务端调用；更新当前列缓存，未知列整列阻挡，不以邻列高度代替。
     */
    private void prepareColumn(ServerWorld world,double cx,double cz) {
        int half=NavigationTerrainRoute.CELL/2;
        loaded=true;top=world.getBottomY();
        if(!world.getWorldBorder().contains(new Box(cx-half,world.getBottomY(),cz-half,
                cx+half,world.getTopY(),cz+half))) {loaded=false;return;}
        for(int bx=MathHelper.floor(cx-half)-1;bx<=MathHelper.floor(cx+half)+1;bx++)
            for(int bz=MathHelper.floor(cz-half)-1;bz<=MathHelper.floor(cz+half)+1;bz++) {
                if(!world.getChunkManager().isChunkLoaded(bx>>4,bz>>4)) {loaded=false;return;}
                top=Math.max(top,world.getTopY(Heightmap.Type.MOTION_BLOCKING,bx,bz));
            }
    }
    /** 功能：后台只读完成数组并建立三维路径；无 MC API，调用后主线程不得再推进本采集器。 */
    NavigationTerrainRoute.Result plan() {
        var volume=new NavigationTerrainRoute.Volume(x,y,z,nx,ny,nz,occupied,circles,settings,cruise);
        return NavigationTerrainRoute.plan(volume,start,goal);
    }
}
