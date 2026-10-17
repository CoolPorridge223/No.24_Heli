package org.DJB.no_24_heli.network;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.RadioFiles;


import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import java.util.*;

/** S2C固定八槽目录；零长度表示空槽，SHA-256同时用于内容校验和缓存隔离。 */
public record RadioCatalogPayload(List<Track> tracks) implements CustomPayload {
    public record Track(int size,String hash){
        public Track{
            if(size<0||size>RadioFiles.MAX_BYTES||(size==0?!hash.isEmpty():!hash.matches("[0-9a-f]{64}")))
                throw new IllegalArgumentException("Invalid radio catalog entry");
        }
    }
    public RadioCatalogPayload{tracks=List.copyOf(tracks);if(tracks.size()!=RadioFiles.TRACKS)throw new IllegalArgumentException("Expected eight radio slots");}
    public static final Id<RadioCatalogPayload> ID=new Id<>(No_24_heli.id("radio_catalog"));
    public static final PacketCodec<RegistryByteBuf,RadioCatalogPayload> CODEC=new PacketCodec<>(){
        public RadioCatalogPayload decode(RegistryByteBuf b){
            List<Track> tracks=new ArrayList<>();for(int i=0;i<RadioFiles.TRACKS;i++)tracks.add(new Track(b.readVarInt(),b.readString(64)));return new RadioCatalogPayload(tracks);
        }
        public void encode(RegistryByteBuf b,RadioCatalogPayload p){for(Track t:p.tracks){b.writeVarInt(t.size);b.writeString(t.hash,64);}}
    };
    @Override public Id<? extends CustomPayload> getId(){return ID;}
}
