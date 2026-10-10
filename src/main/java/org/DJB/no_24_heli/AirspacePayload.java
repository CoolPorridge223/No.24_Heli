package org.DJB.no_24_heli;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** 地图禁飞区快照不包含所有者身份；有数量上限，避免超大网络分配。 */
public record AirspacePayload(List<Entry> zones) implements CustomPayload {
    public record Entry(BlockPos pos,int radius) {}
    public static final Id<AirspacePayload> ID=new Id<>(No_24_heli.id("airspace"));
    public static final PacketCodec<RegistryByteBuf,AirspacePayload> CODEC=new PacketCodec<>() {
        public AirspacePayload decode(RegistryByteBuf b){
            int count=b.readVarInt();if(count<0||count>AirspaceState.MAX_ZONES)throw new IllegalArgumentException("Invalid zone count");
            List<Entry> entries=new ArrayList<>();for(int i=0;i<count;i++)entries.add(new Entry(b.readBlockPos(),b.readVarInt()));return new AirspacePayload(List.copyOf(entries));
        }
        public void encode(RegistryByteBuf b,AirspacePayload p){b.writeVarInt(p.zones.size());for(Entry z:p.zones){b.writeBlockPos(z.pos);b.writeVarInt(z.radius);}}
    };
    public static AirspacePayload of(AirspaceState state){return new AirspacePayload(state.zones().stream().map(z->new Entry(z.pos(),z.radius())).toList());}
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
