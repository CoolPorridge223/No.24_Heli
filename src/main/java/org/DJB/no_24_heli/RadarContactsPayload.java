package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.*;

/** 机载雷达只同步坐标与类型，不扩大完整实体追踪范围，也不加载远处区块。 */
public record RadarContactsPayload(int helicopterId,List<Contact> contacts) implements CustomPayload {
    public static final int RADIUS=512,MAX_CONTACTS=512;
    public record Contact(double x,double z,boolean helicopter){}
    public static final Id<RadarContactsPayload> ID=new Id<>(No_24_heli.id("radar_contacts"));
    public static final PacketCodec<RegistryByteBuf,RadarContactsPayload> CODEC=new PacketCodec<>(){
        public RadarContactsPayload decode(RegistryByteBuf b){
            int id=b.readVarInt(),count=b.readVarInt();if(count<0||count>MAX_CONTACTS)throw new IllegalArgumentException("Invalid radar count");
            List<Contact> list=new ArrayList<>();for(int i=0;i<count;i++)list.add(new Contact(b.readDouble(),b.readDouble(),b.readBoolean()));
            return new RadarContactsPayload(id,List.copyOf(list));
        }
        public void encode(RegistryByteBuf b,RadarContactsPayload p){b.writeVarInt(p.helicopterId);b.writeVarInt(p.contacts.size());for(var c:p.contacts){b.writeDouble(c.x);b.writeDouble(c.z);b.writeBoolean(c.helicopter);}}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
