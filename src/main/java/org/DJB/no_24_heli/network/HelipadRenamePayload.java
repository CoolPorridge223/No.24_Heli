/** 文件：停机坪改名意图；端侧：COMMON；通道：no_24_heli:helipad_rename，C2S。 */
package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.HelipadState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

/**
 * 职责：发送当前维度的停机坪中心和名称，不允许客户端指定维度或默认编号。
 * 引用 net.minecraft.network.RegistryByteBuf 限制名称长度，net.minecraft.network.packet.CustomPayload 承载意图。
 * 生命周期：地图行内编辑提交时创建；服务端主线程复核乘机身份、名称及目录后持久保存并广播。
 * 端侧：COMMON；排查：名称不同步先查 C2S 注册和 helipad_map 回传，不能只修改本机 JSON。
 */
public record HelipadRenamePayload(BlockPos pos,String name) implements CustomPayload {
    /** 专用改名通道，不能复用导航设点包以免改名时改变目标。 */
    public static final Id<HelipadRenamePayload> ID=new Id<>(No_24_heli.id("helipad_rename"));
    /** net.minecraft.network.RegistryByteBuf 在解码边界限制四十字符，内容合法性由服务端复核。 */
    public static final PacketCodec<RegistryByteBuf,HelipadRenamePayload> CODEC=new PacketCodec<>(){
        /** 读取中心和有限长度名称；超长请求由 net.minecraft.network.RegistryByteBuf 拒绝。 */
        @Override public HelipadRenamePayload decode(RegistryByteBuf buffer){
            return new HelipadRenamePayload(buffer.readBlockPos(),buffer.readString(HelipadState.NAME_LIMIT));
        }
        /** 写入改名意图，不携带客户端本机编号或路径。 */
        @Override public void encode(RegistryByteBuf buffer,HelipadRenamePayload payload){
            buffer.writeBlockPos(payload.pos);buffer.writeString(payload.name,HelipadState.NAME_LIMIT);
        }
    };
    /** 返回 net.minecraft.network.packet.CustomPayload.Id，注册方向为 C2S。 */
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
