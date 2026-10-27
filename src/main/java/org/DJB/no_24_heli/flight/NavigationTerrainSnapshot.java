package org.DJB.no_24_heli.flight;

import java.util.List;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

/**
 * 文件：逐 tick 采集有限地形体素，归属 flight 飞控模块；端侧 SERVER，无注册项。
 * 引用 net.minecraft.server.world.ServerWorld 查询已加载区块、方块碰撞和流体，绝不把世界交给后台。
 * 生命周期：NavigationPilot 创建并推进；完成后仅移交坐标和占用数组，取消直接丢弃。
 * 排查：等待时间查采集预算及导航区块票；未生成的列保留采集游标，不作为封闭地形写入。
 */
final class NavigationTerrainSnapshot {
    /** 起终点矩形四侧各留九十六格用于侧绕；整个世界高度可参与升降选择。 */
    private static final int SIDE_MARGIN = 96;
    /** 露天直达优先只采集中心线十六格邻域，边界留白二十四格；遇障碍再扩展到完整绕行矩形。 */
    private static final int DIRECT_MARGIN=24,DIRECT_RADIUS=16;
    /** 全服务器同一 tick 共用四毫秒采集预算，每次调用另限最多一万六千三百八十四格。 */
    private static final long TICK_NANOS = 4_000_000;
    private static final int SLICE_CELLS = 16384;
    /** 单服务端主线程的采集计时，不持有世界；不同维度共享预算，不随飞机数量放大。 */
    private static int budgetTick = Integer.MIN_VALUE;
    private static long spentNanos;
    /** 格心原点和尺寸；起点严格落在格心，避免悬停十格时因向下取整误报嵌地。 */
    final double x,y,z;
    final int nx,ny,nz;
    /** 原始占用数组，一字节一格；完成后不再写，后台构建配置空间。 */
    private final byte[] occupied;
    /** 已加载方柱的最高地表，未知列为 NaN；供后台计算平均净高，不携带世界引用。 */
    private final double[] surface;
    /** 纯坐标请求数据，不引用实体、世界、区块。 */
    private final NavigationTerrainRoute.Point start;
    private final NavigationRoute.Point goal;
    private final List<NavigationRoute.Circle> circles;
    private final NavigationRoute.Settings settings;
    private final double cruise;
    /** 仅全局最终航段指定目标地表上方的悬停高度；incoming 是重叠接点之前的切线参考点。 */
    private final boolean finalGoal;
    private final NavigationTerrainRoute.Point incoming;
    /** 最终目标所在方块列的精确高度图值，在全部采集完成后由主线程读取。 */
    private double goalSurface;
    /** 窄走廊只用于证明直达安全，不在尚未加载的侧方空间搜索绕行。 */
    final boolean directOnly;
    /** 列采集游标及当前列已加载/最高地形缓存；从低到高采集。 */
    private int column,level,top;
    private boolean loaded,outside;
    /** 当前方柱各区块段是否全空气，仅主线程缓存布尔值，不向后台携带区块引用。 */
    private final boolean[] emptySections;

    /**
     * 功能：建立有界矩形规格；超过 pathMaxCells 时只记录失败，不分配大数组。
     * MC API：ServerWorld#getBottomY/#getTopY 限定维度高度；仅服务端调用。
     * 参数：真实起点或当前航段的固定末端、下一目标与配置快照；副作用：只分配采集状态。
     * 排查：CELL_LIMIT 需缩短航程或调整预算，不偷偷截掉障碍。
     */
    NavigationTerrainSnapshot(ServerWorld world,NavigationTerrainRoute.Point start,NavigationRoute.Point goal,
                              List<NavigationRoute.Circle> circles,NavigationRoute.Settings settings,double cruise,boolean directOnly,
                              boolean finalGoal,NavigationTerrainRoute.Point incoming) {
        this.start=start;this.goal=goal;this.circles=List.copyOf(circles);this.settings=settings;this.cruise=cruise;
        this.directOnly=directOnly;
        this.finalGoal=finalGoal;this.incoming=incoming;
        int cell=NavigationTerrainRoute.CELL;
        int margin=directOnly?DIRECT_MARGIN:SIDE_MARGIN;
        x=start.x()+Math.floor(Math.min(0,goal.x()-start.x())/cell)*cell-margin;
        z=start.z()+Math.floor(Math.min(0,goal.z()-start.z())/cell)*cell-margin;
        y=start.y()-Math.floor((start.y()-world.getBottomY())/cell)*cell;
        nx=(int)Math.ceil((Math.max(start.x(),goal.x())+margin-x)/cell)+1;
        nz=(int)Math.ceil((Math.max(start.z(),goal.z())+margin-z)/cell)+1;
        // 顶部不采集跨出世界的半格，避免把人为世界上界识别成覆盖所有天空的顶棚。
        ny=(int)Math.floor((world.getTopY()-cell*.5-y)/cell)+1;
        long count=(long)nx*ny*nz;
        occupied=count<=0||count>settings.maxCells()||count>Integer.MAX_VALUE?null:new byte[(int)count];
        surface=occupied==null?null:new double[nx*nz];
        if(surface!=null)java.util.Arrays.fill(surface,Double.NaN);
        emptySections=new boolean[world.countVerticalSections()];
    }
    /** 功能：报告预算失败；无 MC API、无副作用，失败时禁止提交后台。 */
    boolean tooLarge() { return occupied==null; }
    /** 功能：检查是否完成采集；无 MC API、无副作用。 */
    boolean complete() { return occupied!=null&&column>=nx*nz; }
    /**
     * 功能：在共享时间预算内推进采集，等待导航票加载未知列，水和碰撞方块视为障碍。
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
        BlockPos.Mutable center=new BlockPos.Mutable();
        while(!complete()&&checked++<SLICE_CELLS&&System.nanoTime()<deadline) {
            int ix=column%nx,iz=column/nx;
            double cx=x+ix*cell,cz=z+iz*cell,cy=y+level*cell;
            if(level==0&&directOnly&&!nearLine(cx,cz,DIRECT_RADIUS)) {
                for(int iy=0;iy<ny;iy++)occupied[(iy*nz+iz)*nx+ix]=1;
                column++;continue;
            }
            if(level==0)prepareColumn(world,cx,cz);
            if(outside) {
                for(int iy=0;iy<ny;iy++)occupied[(iy*nz+iz)*nx+ix]=1;
                level=0;column++;continue;
            }
            if(!loaded)break;
            surface[column]=top;
            // 占用数组初始为空，已加载列在最高碰撞形状上方可整段跳过，不逐格查询天空。
            if(loaded&&cy-cell*.5>top+2) {level=0;column++;continue;}
            Box box=new Box(cx-cell*.5,cy-cell*.5,cz-cell*.5,cx+cell*.5,cy+cell*.5,cz+cell*.5);
            boolean blocked=!loaded||box.minY<world.getBottomY()||box.maxY>world.getTopY()
                    ||!world.getWorldBorder().contains(box);
            if(!blocked&&box.minY<=top+2&&!empty(world,box)) {
                center.set(MathHelper.floor(cx),MathHelper.floor(cy),MathHelper.floor(cz));
                var state=world.getBlockState(center);
                // 实心岩层与水体先用格内一点确认占用，省掉对整个四格方盒的重复碰撞扫描；空洞仍精查。
                blocked=state.isFullCube(world,center)||!state.getFluidState().isEmpty()
                        ||world.getBlockCollisions(heli,box).iterator().hasNext()||world.containsFluid(box);
            }
            occupied[(level*nz+iz)*nx+ix]=(byte)(blocked?1:0);
            if(++level>=ny) {level=0;column++;}
        }
        spentNanos+=System.nanoTime()-began;
        if(complete())goalSurface=world.getTopY(Heightmap.Type.MOTION_BLOCKING,MathHelper.floor(goal.x()),MathHelper.floor(goal.z()));
    }
    /**
     * 功能：检查含邻块碰撞形状的一圈区块并读取当前四格方柱内最高高度。
     * MC API：ServerChunkManager#getWorldChunk 仅读取已完成区块；MOTION_BLOCKING 含树叶和流体。
     * 仅服务端调用；合并 ChunkSection#isEmpty 和当前方柱高度，未知列等待异步加载完成。
     */
    private void prepareColumn(ServerWorld world,double cx,double cz) {
        int half=NavigationTerrainRoute.CELL/2;
        loaded=true;outside=false;top=world.getBottomY();
        java.util.Arrays.fill(emptySections,true);
        if(!world.getWorldBorder().contains(new Box(cx-half,world.getBottomY(),cz-half,
                cx+half,world.getTopY(),cz+half))) {outside=true;return;}
        for(int bx=MathHelper.floor(cx-half)-1>>4;bx<=MathHelper.floor(cx+half)+1>>4;bx++)
            for(int bz=MathHelper.floor(cz-half)-1>>4;bz<=MathHelper.floor(cz+half)+1>>4;bz++) {
                var chunk=world.getChunkManager().getWorldChunk(bx,bz);
                if(chunk==null) {loaded=false;return;}
                for(int section=0;section<emptySections.length;section++)
                    emptySections[section]&=chunk.getSection(section).isEmpty();
            }
        for(int bx=MathHelper.floor(cx-half)-1;bx<=MathHelper.floor(cx+half)+1;bx++)
            for(int bz=MathHelper.floor(cz-half)-1;bz<=MathHelper.floor(cz+half)+1;bz++) {
                top=Math.max(top,world.getTopY(Heightmap.Type.MOTION_BLOCKING,bx,bz));
            }
    }
    /** 功能：相邻区块段均为空时跳过空气盒碰撞查询；含一格形状外伸余量，流体所在段不会被跳过。 */
    private boolean empty(ServerWorld world,Box box) {
        int min=world.getSectionIndex(MathHelper.floor(box.minY)-1);
        int max=world.getSectionIndex(MathHelper.floor(box.maxY)+1);
        if(min<0||max>=emptySections.length)return false;
        for(int section=min;section<=max;section++)if(!emptySections[section])return false;
        return true;
    }
    /** 功能：直达探测只加载走廊和查询邻块；三十二格含区块半对角线及碰撞邻域，端侧主线程。 */
    boolean needsChunk(ChunkPos pos) { return !directOnly||nearLine(pos.getStartX()+8,pos.getStartZ()+8,DIRECT_RADIUS+16); }
    /** 功能：计算到真实起终点线段的水平距离；无 MC API，不将走廊外未知区域当空气。 */
    private boolean nearLine(double cx,double cz,double radius) {
        return NavigationRoute.segmentDistance(start.horizontal(),goal,new NavigationRoute.Circle(cx,cz,0))<=radius;
    }
    /** 功能：后台只读完成数组并建立三维路径；无 MC API，调用后主线程不得再推进本采集器。 */
    NavigationTerrainRoute.Result plan() {
        var volume=new NavigationTerrainRoute.Volume(x,y,z,nx,ny,nz,occupied,circles,settings,cruise,surface);
        var result=NavigationTerrainRoute.profile(volume,start,goal,finalGoal,goalSurface,directOnly);
        return NavigationTerrainRoute.alignEntry(result,incoming);
    }
}
