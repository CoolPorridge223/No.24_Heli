package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;

/** 地图只提交 X/Z 导航意图；驾驶员身份、边界和有限数由服务端验证。 */
public record NavigationPayload(double x,double z,boolean clear) implements CustomPayload {
    public static final Id<NavigationPayload> ID=new Id<>(No_24_heli.id("navigation"));
    public static final PacketCodec<RegistryByteBuf,NavigationPayload> CODEC=new PacketCodec<>() {
        public NavigationPayload decode(RegistryByteBuf b){return new NavigationPayload(b.readDouble(),b.readDouble(),b.readBoolean());}
        public void encode(RegistryByteBuf b,NavigationPayload p){b.writeDouble(p.x);b.writeDouble(p.z);b.writeBoolean(p.clear);}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
