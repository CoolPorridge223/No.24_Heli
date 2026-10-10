package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/** 双向雷达配置：客户端仅提交位置和半径，editable/saved 只以服务器回执为准。 */
public record RadarPayload(BlockPos pos,int radius,boolean update,boolean editable,boolean saved) implements CustomPayload {
    public static final Id<RadarPayload> ID=new Id<>(No_24_heli.id("radar_settings"));
    public static final PacketCodec<RegistryByteBuf,RadarPayload> CODEC=new PacketCodec<>() {
        public RadarPayload decode(RegistryByteBuf b){return new RadarPayload(b.readBlockPos(),b.readVarInt(),b.readBoolean(),b.readBoolean(),b.readBoolean());}
        public void encode(RegistryByteBuf b,RadarPayload p){b.writeBlockPos(p.pos);b.writeVarInt(p.radius);b.writeBoolean(p.update);b.writeBoolean(p.editable);b.writeBoolean(p.saved);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
