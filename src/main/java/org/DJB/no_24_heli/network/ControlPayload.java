/** 文件：飞行/乘降操作意图载荷；端侧：COMMON；通道：no_24_heli:controls（C2S）。 */
package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/**
 * 职责：客户端只发送连续轴意图和离散动作位，绝不发送坐标、油量、耐久或伤害。
 * 引用：net.minecraft.network.packet.CustomPayload 标识通道，PacketCodec 编解码四轴与 VarInt 动作位。
 * 生命周期：客户端每 tick 发送，服务端世界线程接收并交给 HelicopterEntity#control 复核。
 * 排查：操纵无响应查通道注册方向、服务端是否校验乘骑/驾驶权；动作错位查位掩码顺序。
 */
public record ControlPayload(float pitch,float roll,float pedal,float collective,int actions) implements CustomPayload {
    /** 通道名 no_24_heli:controls（C2S），与客户端发送和服务端注册必须一致。 */
    public static final Id<ControlPayload> ID=new Id<>(No_24_heli.id("controls"));
    /** 离散动作位，可与连续轴同包发送；位值顺序是对外协议，新增只能追加，不能重排。 */
    public static final int ENGINE=1, ALTITUDE=2, TRIM=4, CRUISE=8, GEAR=16, ROPE=32, EXIT_ROPE=64, MENU=128, AUTOPILOT=256, DISMOUNT=512, AUTO_FLIGHT=1024;
    /** 绳上 E 的按下边沿，只允许服务端在顶部转入空闲副驾。 */
    public static final int ROPE_BOARD=2048;
    /** N 开关与方向键连续瞄准；只影响探照灯，不参与飞控轴或自动驾驶接管。 */
    public static final int SEARCHLIGHT=4096, LIGHT_LEFT=8192, LIGHT_RIGHT=16384, LIGHT_UP=32768, LIGHT_DOWN=65536;
    /** Ctrl+1/2/3/4 的固定目标座位，只允许一个目标座位；沿用四轴与 VarInt 布局。 */
    public static final int SEAT_PILOT=1<<17, SEAT_COPILOT=1<<18, SEAT_REAR_LEFT=1<<19, SEAT_REAR_RIGHT=1<<20;
    public static final int SEAT_ACTIONS=SEAT_PILOT|SEAT_COPILOT|SEAT_REAR_LEFT|SEAT_REAR_RIGHT;
    /** 原有十七位动作加四位换座，超出掩码或同时请求多个座位均拒绝。 */
    private static final int VALID_ACTIONS=(1<<21)-1;
    /** 线格式固定为四轴 Float + 动作 VarInt；顺序与客户端/服务端协议表必须同步修改。 */
    public static final PacketCodec<RegistryByteBuf,ControlPayload> CODEC=new PacketCodec<>() {
        public ControlPayload decode(RegistryByteBuf b) { return new ControlPayload(b.readFloat(),b.readFloat(),b.readFloat(),b.readFloat(),b.readVarInt()); }
        public void encode(RegistryByteBuf b,ControlPayload p) { b.writeFloat(p.pitch);b.writeFloat(p.roll);b.writeFloat(p.pedal);b.writeFloat(p.collective);b.writeVarInt(p.actions); }
    };
    /** 系统边界校验：拒绝 NaN/Infinity 和未定义动作位；有限轴随后由 control() 限幅到 -1..1。 */
    public boolean valid() { return Float.isFinite(pitch)&&Float.isFinite(roll)&&Float.isFinite(pedal)&&Float.isFinite(collective)&&(actions&~VALID_ACTIONS)==0&&Integer.bitCount(actions&SEAT_ACTIONS)<=1; }
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
