/** 文件：创造模式取消跳伞请求；端侧：COMMON；通道：no_24_heli:parachute_cancel（C2S）。 */
package org.DJB.no_24_heli.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：只请求退出发包者自己的伞载体，不允许客户端指定玩家、坐标或伞包状态。
 * 引用 net.minecraft.network.codec.PacketCodec 编解码空载荷；在 ModParachutes 注册并调度服务端主线程。
 * 排查：取消失败先检查创造权限、当前乘伞关系和通道注册；生存模式请求不会生效。
 */
public record ParachuteCancelPayload() implements CustomPayload {
    /** 独立空载荷保留现有 parachute_control 数据格式。 */
    public static final Id<ParachuteCancelPayload> ID=new Id<>(No_24_heli.id("parachute_cancel"));
    /** net.minecraft.network.codec.PacketCodec#unit 不接受额外动作参数。 */
    public static final PacketCodec<RegistryByteBuf,ParachuteCancelPayload> CODEC=PacketCodec.unit(new ParachuteCancelPayload());

    /** 返回 net.minecraft.network.packet.CustomPayload 的固定取消通道。 */
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
