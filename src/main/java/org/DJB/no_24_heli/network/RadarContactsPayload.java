package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** 机载雷达同步实体坐标/类型及停机坪中心/禁用状态，不扩大实体追踪范围，也不加载远处区块。 */
public record RadarContactsPayload(int helicopterId,List<Contact> contacts,List<Helipad> helipads) implements CustomPayload {
    public static final int RADIUS=512,MAX_CONTACTS=512;
    public record Contact(double x,double z,boolean helicopter){}
    /** 每个完整停机坪只发送中心位置和服务端实时禁用状态；不发送承重碎片。 */
    public record Helipad(BlockPos pos,boolean restricted){}
    public static final Id<RadarContactsPayload> ID=new Id<>(No_24_heli.id("radar_contacts"));
    public static final PacketCodec<RegistryByteBuf,RadarContactsPayload> CODEC=new PacketCodec<>(){
        public RadarContactsPayload decode(RegistryByteBuf b){
            int id=b.readVarInt(),count=b.readVarInt();if(count<0||count>MAX_CONTACTS)throw new IllegalArgumentException("Invalid radar count");
            List<Contact> list=new ArrayList<>();for(int i=0;i<count;i++)list.add(new Contact(b.readDouble(),b.readDouble(),b.readBoolean()));
            int pads=b.readVarInt();
            // 每项至少为八字节位置和一字节布尔值，先拒绝越界数量再分配列表。
            if(pads<0||pads>MAX_CONTACTS||pads>b.readableBytes()/9)throw new IllegalArgumentException("停机坪雷达数量无效");
            List<Helipad> helipads=new ArrayList<>();
            for(int i=0;i<pads;i++)helipads.add(new Helipad(b.readBlockPos(),b.readBoolean()));
            return new RadarContactsPayload(id,List.copyOf(list),List.copyOf(helipads));
        }
        public void encode(RegistryByteBuf b,RadarContactsPayload p){
            b.writeVarInt(p.helicopterId);b.writeVarInt(p.contacts.size());
            for(var c:p.contacts){b.writeDouble(c.x);b.writeDouble(c.z);b.writeBoolean(c.helicopter);}
            b.writeVarInt(p.helipads.size());
            for(Helipad pad:p.helipads){b.writeBlockPos(pad.pos);b.writeBoolean(pad.restricted);}
        }
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
