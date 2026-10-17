package org.DJB.no_24_heli.data;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 职责：每个维度持久保存完整停机坪的中心，供固定地图标记和服务端导航校验使用。
 * net.minecraft.world.PersistentState 随世界保存，net.minecraft.server.world.ServerWorld 提供独立维度存储。
 * 端侧/线程：仅逻辑服务端主线程；方块实体加载时补录旧圆盘，真实拆除时删除，区块卸载不删除。
 * 排查：旧坪未显示先加载其区块完成首次登记；幽灵标记检查中心方块的拆除回调。
 */
public final class HelipadState extends PersistentState {
    /** 坐标以中心方块保存；插入顺序稳定，中心重复加载不会产生重复标记。 */
    private final Set<BlockPos> centers=new LinkedHashSet<>();
    /** 无数据修复器的独立停机坪目录；不修改既有禁飞区或飞机存档格式。 */
    private static final Type<HelipadState> TYPE=new Type<>(HelipadState::new,HelipadState::read,null);

    /** 从 net.minecraft.server.world.ServerWorld 的持久状态管理器取得本维度目录，不加载区块。 */
    public static HelipadState get(ServerWorld world){
        return world.getPersistentStateManager().getOrCreate(TYPE,"no_24_heli_helipads");
    }

    /** 返回固定地图的完整只读目录；与机载雷达的已加载、距离范围筛选互相独立。 */
    public Set<BlockPos> centers(){return Collections.unmodifiableSet(centers);}

    /** 登记新放置或首次加载的中心；仅新增坐标才标记需要保存。 */
    public void add(BlockPos pos){if(centers.add(pos.toImmutable()))markDirty();}

    /** 仅真实拆除中心时移除；不能从方块实体 markRemoved 调用，以免卸载区块导致地图消失。 */
    public void remove(BlockPos pos){if(centers.remove(pos))markDirty();}

    /** 从 net.minecraft.nbt.NbtCompound 的中心坐标长整型数组恢复，集合自动合并重复位置。 */
    private static HelipadState read(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        HelipadState state=new HelipadState();
        for(long packed:nbt.getLongArray("Centers"))state.centers.add(BlockPos.fromLong(packed));
        return state;
    }

    /** 只写中心坐标与独立版本；net.minecraft.world.PersistentState 负责随世界落盘。 */
    @Override public NbtCompound writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        nbt.putInt("Version",1);
        nbt.putLongArray("Centers",centers.stream().mapToLong(BlockPos::asLong).toArray());
        return nbt;
    }
}
