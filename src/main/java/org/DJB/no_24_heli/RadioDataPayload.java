package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** S2C音频分片；空数组表示该请求无法继续，客户端须丢弃未完成文件，不能拿半首歌播放。 */
public record RadioDataPayload(int track,int offset,byte[] bytes) implements CustomPayload {
    public static final Id<RadioDataPayload> ID=new Id<>(No_24_heli.id("radio_data"));
    public static final PacketCodec<RegistryByteBuf,RadioDataPayload> CODEC=new PacketCodec<>(){
        public RadioDataPayload decode(RegistryByteBuf b){return new RadioDataPayload(b.readVarInt(),b.readVarInt(),b.readByteArray(RadioFiles.CHUNK_BYTES));}
        public void encode(RegistryByteBuf b,RadioDataPayload p){b.writeVarInt(p.track);b.writeVarInt(p.offset);b.writeByteArray(p.bytes);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
