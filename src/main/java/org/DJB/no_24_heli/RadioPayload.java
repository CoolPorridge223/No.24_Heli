package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** 电台请求仅传-1（关闭）或0–7槽位，不允许客户端向服务器提交任意文件路径或音频数据。 */
public record RadioPayload(int track) implements CustomPayload {
    public static final Id<RadioPayload> ID=new Id<>(No_24_heli.id("radio_control"));
    public static final PacketCodec<RegistryByteBuf,RadioPayload> CODEC=new PacketCodec<>(){
        public RadioPayload decode(RegistryByteBuf b){return new RadioPayload(b.readVarInt());}
        public void encode(RegistryByteBuf b,RadioPayload p){b.writeVarInt(p.track);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
