package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** 双向设置消息：客户端申请修改，服务端返回权威值和权限，不信任客户端权限位。 */
public record SettingsPayload(boolean update,boolean destruction,boolean editable,boolean saved) implements CustomPayload {
    public static final Id<SettingsPayload> ID=new Id<>(No_24_heli.id("settings"));
    public static final PacketCodec<RegistryByteBuf,SettingsPayload> CODEC=new PacketCodec<>() {
        public SettingsPayload decode(RegistryByteBuf b){return new SettingsPayload(b.readBoolean(),b.readBoolean(),b.readBoolean(),b.readBoolean());}
        public void encode(RegistryByteBuf b,SettingsPayload p){b.writeBoolean(p.update);b.writeBoolean(p.destruction);b.writeBoolean(p.editable);b.writeBoolean(p.saved);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
