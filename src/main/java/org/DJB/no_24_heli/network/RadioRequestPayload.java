package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** C2S拉取一个分片；服务端还会检查槽位、偏移、听者距离及每人同时只有一个请求。 */
public record RadioRequestPayload(int track,int offset) implements CustomPayload {
    public static final Id<RadioRequestPayload> ID=new Id<>(No_24_heli.id("radio_request"));
    public static final PacketCodec<RegistryByteBuf,RadioRequestPayload> CODEC=new PacketCodec<>(){
        public RadioRequestPayload decode(RegistryByteBuf b){return new RadioRequestPayload(b.readVarInt(),b.readVarInt());}
        public void encode(RegistryByteBuf b,RadioRequestPayload p){b.writeVarInt(p.track);b.writeVarInt(p.offset);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
