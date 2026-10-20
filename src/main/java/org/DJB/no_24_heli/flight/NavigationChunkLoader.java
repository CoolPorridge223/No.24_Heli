package org.DJB.no_24_heli.flight;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * 文件：为导航当前航段和提前规划的航段持有临时区块票，归属 flight 飞控模块，端侧 SERVER。
 * 引用 net.minecraft.server.world.ServerChunkManager#addTicket/#removeTicket 异步加载并固定区块；
 * 不调用会等待生成结束的 getChunk 或 getChunkFutureSyncOnMainThread。
 * 生命周期：NavigationPilot 在主线程创建、更新，取消、到达和实体移除时 close；不进入后台任务。
 * 排查：远处停车查待加载队列；卸载泄漏查 close；多架飞机使用独立 UUID，不能互相移除票。
 */
final class NavigationChunkLoader {
    /** 半径零对应 FULL 级别，供地形读取使用，不把整条航线升级为实体运算区块。 */
    private static final ChunkTicketType<UUID> TICKET = ChunkTicketType.create(
            org.DJB.no_24_heli.No_24_heli.MOD_ID+"_navigation", Comparator.<UUID>naturalOrder());
    /** 每 tick 最多新请求十六个区块，让原版生成队列分摊加载工作。 */
    private static final int REQUESTS_PER_TICK = 16;
    /** 直达航线的区块中心距上限，含十六格采集走廊、区块半对角线和查询邻块，单位格。 */
    private static final int DIRECT_CHUNK_RADIUS=32;
    /** 世界和票据只在服务端主线程访问，不属于地形计算快照。 */
    private final ServerWorld world;
    private final UUID owner = UUID.randomUUID();
    private final Set<ChunkPos> held = new HashSet<>(), waiting = new LinkedHashSet<>();
    /** 同一 tick 多次推进仍共享请求上限；不持有 Future 或生成线程引用。 */
    private int requestTick=Integer.MIN_VALUE,issued;

    /** 功能：绑定当前维度，尚不加载任何区块；仅服务端，无副作用。 */
    NavigationChunkLoader(ServerWorld world) { this.world=world; }

    /** 功能：保留当前、已完成续段和采集区域的区块，释放已飞过的区域；仅主线程修改票据。 */
    void retain(NavigationTerrainRoute.Result active,NavigationTerrainRoute.Result next,NavigationTerrainSnapshot request) {
        Set<ChunkPos> needed=new LinkedHashSet<>();
        if(active!=null)addRoute(needed,active);
        if(next!=null)addRoute(needed,next);
        if(request!=null) {
            Set<ChunkPos> region=new LinkedHashSet<>();addRegion(region,request.x,request.z,request.nx,request.nz);
            region.removeIf(pos->!request.needsChunk(pos));needed.addAll(region);
        }
        var iterator=held.iterator();
        while(iterator.hasNext()) {
            ChunkPos pos=iterator.next();
            if(!needed.contains(pos)) {world.getChunkManager().removeTicket(TICKET,pos,0,owner);iterator.remove();}
        }
        waiting.retainAll(needed);needed.removeAll(held);waiting.addAll(needed);
    }

    /** 功能：直达路线只保留真实飞行走廊；需要搜索绕行的路线保留其完整配置空间。 */
    private void addRoute(Set<ChunkPos> output,NavigationTerrainRoute.Result route) {
        var volume=route.volume();Set<ChunkPos> region=new LinkedHashSet<>();
        addRegion(region,volume.x,volume.z,volume.nx,volume.nz);
        if(route.expanded()==0)region.removeIf(pos->NavigationRoute.segmentDistance(
                route.path().getFirst().horizontal(),route.path().getLast().horizontal(),
                new NavigationRoute.Circle(pos.getStartX()+8,pos.getStartZ()+8,0))>DIRECT_CHUNK_RADIUS);
        output.addAll(region);
    }

    /** 功能：包含碰撞查询的一格邻域；WorldBorder#contains 排除完全在世界边界外的区块。 */
    private void addRegion(Set<ChunkPos> output,double x,double z,int nx,int nz) {
        int cell=NavigationTerrainRoute.CELL;
        int minX=(int)Math.floor(x-cell*.5-1)>>4,minZ=(int)Math.floor(z-cell*.5-1)>>4;
        int maxX=(int)Math.floor(x+(nx-.5)*cell+1)>>4,maxZ=(int)Math.floor(z+(nz-.5)*cell+1)>>4;
        for(int cz=minZ;cz<=maxZ;cz++)for(int cx=minX;cx<=maxX;cx++) {
            ChunkPos pos=new ChunkPos(cx,cz);
            if(world.getWorldBorder().contains(pos))output.add(pos);
        }
    }

    /** 功能：非阻塞推进加载请求；生成完成由快照通过 getWorldChunk 查询，不在世界 tick 等待 Future。 */
    void step() {
        int tick=world.getServer().getTicks();
        if(tick!=requestTick){requestTick=tick;issued=0;}
        var iterator=waiting.iterator();
        while(issued<REQUESTS_PER_TICK&&iterator.hasNext()) {
            ChunkPos pos=iterator.next();iterator.remove();
            world.getChunkManager().addTicket(TICKET,pos,0,owner);held.add(pos);issued++;
        }
    }

    /** 功能：取消或卸载时释放本机全部票据和待加载请求；服务端调用，不改变其他加载来源。 */
    void close() {
        for(ChunkPos pos:held)world.getChunkManager().removeTicket(TICKET,pos,0,owner);
        held.clear();waiting.clear();
    }
}
