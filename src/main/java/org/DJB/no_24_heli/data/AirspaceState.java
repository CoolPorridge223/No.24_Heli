package org.DJB.no_24_heli.data;

import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import java.util.*;

/** 每个维度独立保存地面雷达。禁飞区不依赖区块加载，因此飞近前也能显示告警。 */
public final class AirspaceState extends PersistentState {
    /** 雷达设置半径仍沿用 16～512 格；持久化圆数量不设固定上限。 */
    public static final int MIN_RADIUS=16, MAX_RADIUS=512;
    public record Zone(BlockPos pos,int radius,UUID owner) {
        /** 禁飞范围为覆盖全部高度的圆柱，不因飞得高而失效。 */
        public boolean contains(double x,double z){return Math.hypot(x-pos.getX()-.5,z-pos.getZ()-.5)<=radius;}
    }
    private long revision;
    public long revision(){return revision;}
    private final Map<BlockPos,Zone> zones=new LinkedHashMap<>();
    private static final Type<AirspaceState> TYPE=new Type<>(AirspaceState::new,AirspaceState::read,null);
    public static AirspaceState get(ServerWorld world){return world.getPersistentStateManager().getOrCreate(TYPE,"no_24_heli_airspace");}
    public Collection<Zone> zones(){return Collections.unmodifiableCollection(zones.values());}
    public Zone at(BlockPos pos){return zones.get(pos);}
    /** 保持旧接口的成功返回值；任意数量与位置均可登记，规划预算不截断持久数据。 */
    public boolean put(BlockPos pos,int radius,UUID owner){
        zones.put(pos.toImmutable(),new Zone(pos.toImmutable(),Math.clamp(radius,MIN_RADIUS,MAX_RADIUS),owner));revision++;markDirty();return true;
    }
    public void remove(BlockPos pos){if(zones.remove(pos)!=null){revision++;markDirty();}}
    private static AirspaceState read(NbtCompound n,RegistryWrapper.WrapperLookup lookup){
        AirspaceState state=new AirspaceState();NbtList list=n.getList("Zones",NbtElement.COMPOUND_TYPE);
        for(int i=0;i<list.size();i++){
            NbtCompound z=list.getCompound(i);
            if(z.containsUuid("Owner"))state.put(BlockPos.fromLong(z.getLong("Pos")),z.getInt("Radius"),z.getUuid("Owner"));
        }
        state.setDirty(false);return state;
    }
    @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup lookup){
        n.putInt("Version",1);NbtList list=new NbtList();
        for(Zone zone:zones.values()){NbtCompound z=new NbtCompound();z.putLong("Pos",zone.pos.asLong());z.putInt("Radius",zone.radius);z.putUuid("Owner",zone.owner);list.add(z);}
        n.put("Zones",list);return n;
    }
}
