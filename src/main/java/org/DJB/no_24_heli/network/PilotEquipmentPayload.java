/** 文件：玩家两个专用头槽的外观快照；端侧：COMMON，仅 S2C；网络：pilot_equipment。 */
package org.DJB.no_24_heli.network;

import java.util.UUID;
import net.minecraft.item.ItemStack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：同步玩家身份及两件装备，保留物品组件；不接受客户端穿戴声明。
 * 引用：net.minecraft.item.ItemStack.OPTIONAL_PACKET_CODEC 支持空槽与注册表组件。
 * 生命周期：装备变化/开始追踪时发送；服务端编码，客户端主线程应用。
 * 排查：错误玩家外观查 UUID；空槽未消失查是否发送空 ItemStack。
 */
public record PilotEquipmentPayload(UUID player, ItemStack headset, ItemStack goggles) implements CustomPayload {
    /** 单向网络 ID，避免与原版容器点击协议混用。 */
    public static final Id<PilotEquipmentPayload> ID = new Id<>(No_24_heli.id("pilot_equipment"));
    /** 固定两件装备，不按包内数量分配集合。 */
    public static final PacketCodec<RegistryByteBuf, PilotEquipmentPayload> CODEC = new PacketCodec<>() {
        /** 读取 UUID 与两个原版物品组件；非法编码由原版包解码器拒绝。 */
        @Override public PilotEquipmentPayload decode(RegistryByteBuf buffer) {
            return new PilotEquipmentPayload(buffer.readUuid(), ItemStack.OPTIONAL_PACKET_CODEC.decode(buffer),
                    ItemStack.OPTIONAL_PACKET_CODEC.decode(buffer));
        }
        /** 按固定顺序写入装备，空物品有明确标记。 */
        @Override public void encode(RegistryByteBuf buffer, PilotEquipmentPayload packet) {
            buffer.writeUuid(packet.player());
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buffer, packet.headset());
            ItemStack.OPTIONAL_PACKET_CODEC.encode(buffer, packet.goggles());
        }
    };
    /** net.minecraft.network.packet.CustomPayload 用此 ID 选择接收器。 */
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
