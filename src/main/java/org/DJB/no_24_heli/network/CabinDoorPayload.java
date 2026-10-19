/** 文件：自动开门乘降意图；端侧：COMMON；通道：no_24_heli:cabin_door（C2S）。 */
package org.DJB.no_24_heli.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：只提交目标实体 ID，侧别和距离由服务端按真实玩家位置推导。
 * 引用 net.minecraft.network.codec.PacketCodec 编解码；在模组入口注册服务端接收器。
 * 生命周期：客户端按下边沿发送，服务端世界线程处理；排查：无响应检查注册方向、距离和动画限流。
 * @param entityId 被操作的直升机网络实体 ID
 */
public record CabinDoorPayload(int entityId) implements CustomPayload {
    /** 独立于飞控和开伞的操作通道，舱外无需伪造乘员身份。 */
    public static final Id<CabinDoorPayload> ID = new Id<>(No_24_heli.id("cabin_door"));
    /** net.minecraft.network.codec.PacketCodecs.VAR_INT 编码目标 ID。 */
    public static final PacketCodec<RegistryByteBuf, CabinDoorPayload> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, CabinDoorPayload::entityId, CabinDoorPayload::new);
    /** net.minecraft.network.packet.CustomPayload 返回已注册通道。 */
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
