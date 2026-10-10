package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** 独立难度通道，不修改已有方块破坏设置包的线格式。 */
public record DifficultyPayload(boolean update,int mode,boolean editable,boolean saved) implements CustomPayload {
    public static final Id<DifficultyPayload> ID=new Id<>(No_24_heli.id("difficulty"));
    public static final PacketCodec<RegistryByteBuf,DifficultyPayload> CODEC=new PacketCodec<>(){
        public DifficultyPayload decode(RegistryByteBuf b){return new DifficultyPayload(b.readBoolean(),b.readVarInt(),b.readBoolean(),b.readBoolean());}
        public void encode(RegistryByteBuf b,DifficultyPayload p){b.writeBoolean(p.update);b.writeVarInt(p.mode);b.writeBoolean(p.editable);b.writeBoolean(p.saved);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
