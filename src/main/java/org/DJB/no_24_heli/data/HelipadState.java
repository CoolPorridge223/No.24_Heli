/** 文件：按维度保存停机坪中心、统一名称及默认编号；端侧：SERVER；通道：helipad_map、helipad_rename。 */
package org.DJB.no_24_heli.data;

import org.DJB.no_24_heli.No_24_heli;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 职责：每个维度持久保存完整停机坪的中心、名称及递增编号，供所有客户端统一显示。
 * net.minecraft.world.PersistentState 随世界保存，net.minecraft.server.world.ServerWorld 提供独立维度存储。
 * 端侧/线程：仅逻辑服务端主线程；方块实体加载时补录旧圆盘，真实拆除时删除，区块卸载不删除。
 * 排查：旧坪未显示先加载其区块完成首次登记；幽灵标记检查中心方块的拆除回调。
 */
public final class HelipadState extends PersistentState {
    /** 名称字符上限；服务端存档、网络编解码和客户端输入框共用。 */
    public static final int NAME_LIMIT=40;
    /** 服务器分配的稳定编号不受客户端手工点数量影响；空名称使用该编号显示默认名。 */
    public record Pad(BlockPos pos,int number,String name){}
    /** 中心去重且保持登记顺序；区块重新加载不会覆盖名称或重新编号。 */
    private final Map<BlockPos,Pad> pads=new LinkedHashMap<>();
    /** 下一个未使用的默认编号，从一开始，拆除停机坪不复用旧编号。 */
    private int nextNumber=1;
    /** 无数据修复器的独立停机坪目录；不修改既有禁飞区或飞机存档格式。 */
    private static final Type<HelipadState> TYPE=new Type<>(HelipadState::new,HelipadState::read,null);

    /** 从 net.minecraft.server.world.ServerWorld 的持久状态管理器取得本维度目录，不加载区块。 */
    public static HelipadState get(ServerWorld world){
        return world.getPersistentStateManager().getOrCreate(TYPE,"no_24_heli_helipads");
    }

    /** 返回固定地图的完整只读目录；与机载雷达的已加载、距离范围筛选互相独立。 */
    public Set<BlockPos> centers(){return Collections.unmodifiableSet(pads.keySet());}

    /** 返回服务端名称目录，供 helipad_map 创建不可变的 net.minecraft.network.packet.CustomPayload 快照。 */
    public Collection<Pad> pads(){return Collections.unmodifiableCollection(pads.values());}

    /** 登记新放置或首次加载的中心；仅新增坐标才标记需要保存。 */
    public void add(BlockPos pos){
        if(pads.containsKey(pos))return;
        BlockPos center=pos.toImmutable();pads.put(center,new Pad(center,nextNumber++,""));markDirty();
    }

    /** 仅真实拆除中心时移除；不能从方块实体 markRemoved 调用，以免卸载区块导致地图消失。 */
    public void remove(BlockPos pos){if(pads.remove(pos)!=null)markDirty();}

    /** 网络与存档边界拒绝超长、控制字符和格式控制符，避免名称影响列表渲染。 */
    public static boolean isValidName(String name){
        return name.length()<=NAME_LIMIT&&name.chars().noneMatch(c->Character.isISOControl(c)||c=='\u00a7');
    }

    /** 服务端主线程修改已登记停机坪；无需加载远处区块，空白名称恢复统一默认编号。 */
    public boolean rename(BlockPos pos,String name){
        Pad pad=pads.get(pos);
        if(pad==null||!isValidName(name))return false;
        String trimmed=name.strip();
        if(!pad.name.equals(trimmed)){pads.put(pos,new Pad(pad.pos,pad.number,trimmed));markDirty();}
        return true;
    }

    /** 从 net.minecraft.nbt.NbtCompound 恢复名称及编号；兼容 Version=1 的 Centers 数组并分配服务端编号。 */
    private static HelipadState read(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        HelipadState state=new HelipadState();
        if(nbt.getInt("Version")<2){
            for(long packed:nbt.getLongArray("Centers"))state.add(BlockPos.fromLong(packed));
            return state;
        }
        NbtList list=nbt.getList("Pads",NbtElement.COMPOUND_TYPE);
        for(int i=0;i<list.size();i++){
            NbtCompound entry=list.getCompound(i);BlockPos pos=BlockPos.fromLong(entry.getLong("Pos"));
            int number=entry.getInt("Number");String name=entry.getString("Name");
            if(number<1||number==Integer.MAX_VALUE||!isValidName(name)||state.pads.containsKey(pos)){
                No_24_heli.LOGGER.warn("忽略无效或重复的停机坪名称记录 {}",pos);continue;
            }
            state.pads.put(pos,new Pad(pos,number,name.strip()));state.nextNumber=Math.max(state.nextNumber,number+1);
        }
        state.nextNumber=Math.max(state.nextNumber,nbt.getInt("NextNumber"));
        return state;
    }

    /** net.minecraft.world.PersistentState 随世界保存统一名称，保留 NextNumber，重启不会重排默认编号。 */
    @Override public NbtCompound writeNbt(NbtCompound nbt,RegistryWrapper.WrapperLookup lookup){
        nbt.putInt("Version",2);nbt.putInt("NextNumber",nextNumber);NbtList list=new NbtList();
        for(Pad pad:pads.values()){
            NbtCompound entry=new NbtCompound();entry.putLong("Pos",pad.pos.asLong());
            entry.putInt("Number",pad.number);entry.putString("Name",pad.name);list.add(entry);
        }
        nbt.put("Pads",list);
        return nbt;
    }
}
