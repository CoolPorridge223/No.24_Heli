package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** 客户端只发送操纵意图，绝不发送坐标、油量、耐久或伤害。 */
public record ControlPayload(float pitch,float roll,float pedal,float collective,int actions) implements CustomPayload {
    public static final Id<ControlPayload> ID=new Id<>(No_24_heli.id("controls"));
    public static final int ENGINE=1, ALTITUDE=2, TRIM=4, CRUISE=8, GEAR=16, ROPE=32, EXIT_ROPE=64, MENU=128, AUTOPILOT=256, DISMOUNT=512, AUTO_FLIGHT=1024;
    /** 绳上 E 的按下边沿，只允许服务端在顶部转入空闲副驾。 */
    public static final int ROPE_BOARD=2048;
    /** N 开关与方向键连续瞄准；只影响探照灯，不参与飞控轴或自动驾驶接管。 */
    public static final int SEARCHLIGHT=4096, LIGHT_LEFT=8192, LIGHT_RIGHT=16384, LIGHT_UP=32768, LIGHT_DOWN=65536;
    /** 现有十二位动作及五位灯具动作，包的四轴与 VarInt 布局保持。 */
    private static final int VALID_ACTIONS=(1<<17)-1;
    public static final PacketCodec<RegistryByteBuf,ControlPayload> CODEC=new PacketCodec<>() {
        public ControlPayload decode(RegistryByteBuf b) { return new ControlPayload(b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt()); }
        public void encode(RegistryByteBuf b,ControlPayload p) { b.writeFloat(p.pitch);b.writeFloat(p.roll);b.writeFloat(p.pedal);b.writeFloat(p.collective);b.writeVarInt(p.actions); }
    };
    public boolean valid() { return Float.isFinite(pitch)&&Float.isFinite(roll)&&Float.isFinite(pedal)&&Float.isFinite(collective)&&(actions&~VALID_ACTIONS)==0; }
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
