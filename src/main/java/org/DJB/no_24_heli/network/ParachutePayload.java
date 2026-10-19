/** 文件：跳伞意图载荷；端侧：COMMON；通道：no_24_heli:parachute_control（C2S）。 */
package org.DJB.no_24_heli.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：传递开伞边沿、WASD 意图及鼠标观察角，不传坐标或速度。
 * 引用：net.minecraft.network.codec.PacketCodec 负责编解码，接收在 ModParachutes 注册。
 * 生命周期：客户端每 tick 发意图，逻辑服务端主线程复核真实装备及乘骑。
 * 排查：方向相反查 forward 的 W=+1、turn 的 D=+1；无响应查通道注册。
 * @param forward 前进/后退意图，-1..1，W 为正
 * @param turn 左右意图，-1..1，D 为正；未开伞时平移，展开后转向
 * @param deploy 本次是否请求展开，长按不重复触发
 * @param lookYaw 鼠标航向，归一化至 -180..180 度
 * @param lookPitch 鼠标俯仰，-90..90 度，低头为正
 */
public record ParachutePayload(float forward, float turn, boolean deploy, float lookYaw, float lookPitch) implements CustomPayload {
    /** 独立 C2S 通道，避免改变旧直升机控制包格式。 */
    public static final Id<ParachutePayload> ID = new Id<>(No_24_heli.id("parachute_control"));
    /** net.minecraft.network.codec.PacketCodecs 编码平移轴、开伞边沿及两个观察角，2.3.0 两端同步升级。 */
    public static final PacketCodec<RegistryByteBuf, ParachutePayload> CODEC = PacketCodec.tuple(
            PacketCodecs.FLOAT, ParachutePayload::forward, PacketCodecs.FLOAT, ParachutePayload::turn,
            PacketCodecs.BOOL, ParachutePayload::deploy, PacketCodecs.FLOAT, ParachutePayload::lookYaw,
            PacketCodecs.FLOAT, ParachutePayload::lookPitch, ParachutePayload::new);

    /** 网络系统边界拒绝非有限数和超范围输入，不把异常值悄悄夹成有效命令。 */
    public boolean valid() {
        return Float.isFinite(forward) && Float.isFinite(turn)
                && Math.abs(forward) <= 1 && Math.abs(turn) <= 1
                && Float.isFinite(lookYaw) && Float.isFinite(lookPitch)
                && Math.abs(lookYaw) <= 180 && Math.abs(lookPitch) <= 90;
    }

    /** net.minecraft.network.packet.CustomPayload 返回注册通道。 */
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
