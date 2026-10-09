package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** 客户端只发送操纵意图，绝不发送坐标、油量、耐久或伤害。 */
public record ControlPayload(float pitch,float roll,float pedal,float collective,int actions) implements CustomPayload {
    public static final Id<ControlPayload> ID=new Id<>(No_24_heli.id("controls"));
    public static final int ENGINE=1, ALTITUDE=2, TRIM=4, CRUISE=8, GEAR=16, ROPE=32, EXIT_ROPE=64, MENU=128;
    public static final PacketCodec<RegistryByteBuf,ControlPayload> CODEC=new PacketCodec<>() {
        public ControlPayload decode(RegistryByteBuf b) { return new ControlPayload(b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt()); }
        public void encode(RegistryByteBuf b,ControlPayload p) { b.writeFloat(p.pitch);b.writeFloat(p.roll);b.writeFloat(p.pedal);b.writeFloat(p.collective);b.writeVarInt(p.actions); }
    };
    public boolean valid() { return Float.isFinite(pitch)&&Float.isFinite(roll)&&Float.isFinite(pedal)&&Float.isFinite(collective)&&(actions&~255)==0; }
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
