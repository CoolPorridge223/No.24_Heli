package org.DJB.no_24_heli.block;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.HelipadState;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** 每格渲染圆盘的一部分；服务端按方块实体生命周期索引已加载的中心，供雷达查询。 */
public final class HelipadBlockEntity extends BlockEntity {
    /** 仅服务端主线程使用；每个完整圆盘只登记中心，弱世界键避免退出存档后保留世界。 */
    private static final Map<ServerWorld,Set<BlockPos>> CENTERS=new WeakHashMap<>();
    public HelipadBlockEntity(BlockPos pos,BlockState state){super(No_24_heli.HELIPAD_ENTITY,pos,state);}

    /** net.minecraft.block.entity.BlockEntity#setWorld 在区块加载或新放置时提供世界，旧存档无需重放。 */
    @Override public void setWorld(World world){
        unregister();super.setWorld(world);register();
    }

    /** net.minecraft.block.entity.BlockEntity#cancelRemoval 恢复有效方块实体时补回中心索引。 */
    @Override public void cancelRemoval(){super.cancelRemoval();register();}

    /** net.minecraft.block.entity.BlockEntity#markRemoved 在拆除或区块卸载时移除索引，避免幽灵标记。 */
    @Override public void markRemoved(){unregister();super.markRemoved();}

    /** 中心同时进入已加载雷达索引和持久地图目录；承重碎片不产生重复标记。 */
    private void register(){
        if(getWorld() instanceof ServerWorld world&&getCachedState().isOf(No_24_heli.HELIPAD)){
            CENTERS.computeIfAbsent(world,key->new HashSet<>()).add(getPos().toImmutable());
            HelipadState.get(world).add(getPos());
        }
    }

    /** 清理旧世界的登记；不加载区块，也不改变方块或存档。 */
    private void unregister(){
        if(getWorld() instanceof ServerWorld world){
            Set<BlockPos> positions=CENTERS.get(world);
            if(positions!=null){positions.remove(getPos());if(positions.isEmpty())CENTERS.remove(world);}
        }
    }

    /** 返回本维度当前已加载的中心只读视图；调用方须在服务端主线程完成查询。 */
    public static Set<BlockPos> centers(ServerWorld world){
        return Collections.unmodifiableSet(CENTERS.getOrDefault(world,Set.of()));
    }
}
