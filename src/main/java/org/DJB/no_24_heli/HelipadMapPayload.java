package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定地图停机坪快照，独立于机载雷达距离与当前区块加载状态。
 * net.minecraft.network.packet.CustomPayload 仅用于服务端向乘员发送目录；导航权限仍由服务端检查。
 * 排查：远处标记消失先检查是否误用了雷达 contacts；数量错误在网络解码边界拒绝。
 */
public record HelipadMapPayload(List<Entry> helipads) implements CustomPayload {
    /** 地图仅需要中心；禁用状态结合独立禁飞区快照实时计算。 */
    public record Entry(BlockPos pos){}
    /** 每个 net.minecraft.util.math.BlockPos 在线路中固定占八字节。 */
    private static final int ENTRY_BYTES=Long.BYTES;
    /** 独立地图通道，不复用半径受限的 radar_contacts。 */
    public static final Id<HelipadMapPayload> ID=new Id<>(No_24_heli.id("helipad_map"));
    /** 先按剩余包字节校验目录数量；地图不沿用雷达的五百一十二目标上限。 */
    public static final PacketCodec<RegistryByteBuf,HelipadMapPayload> CODEC=new PacketCodec<>(){
        /** 在网络边界读取不可变中心列表，拒绝负数量和超出包内容的数量。 */
        @Override public HelipadMapPayload decode(RegistryByteBuf buffer){
            int count=buffer.readVarInt();
            if(count<0||count>buffer.readableBytes()/ENTRY_BYTES)
                throw new IllegalArgumentException("停机坪地图数量超过包内可读取数据");
            List<Entry> entries=new ArrayList<>();
            for(int i=0;i<count;i++)entries.add(new Entry(buffer.readBlockPos()));
            return new HelipadMapPayload(List.copyOf(entries));
        }
        /** 服务端发送完整目录；空目录同样发送，以清除已拆除或其他维度的旧标记。 */
        @Override public void encode(RegistryByteBuf buffer,HelipadMapPayload payload){
            buffer.writeVarInt(payload.helipads.size());
            for(Entry entry:payload.helipads)buffer.writeBlockPos(entry.pos);
        }
    };

    /** 在服务端主线程复制当前目录，后续保存或区块卸载不会修改已创建的包。 */
    public static HelipadMapPayload of(HelipadState state){
        return new HelipadMapPayload(state.centers().stream().map(Entry::new).toList());
    }

    /** 返回固定地图通道的 net.minecraft.network.packet.CustomPayload.Id。 */
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
