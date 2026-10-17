package org.DJB.no_24_heli.data;

import net.minecraft.nbt.*;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import java.util.*;

/** 玩家模式按 UUID 存在主世界数据中，跨维度、重登和服务器重启保留。 */
public final class PlayerFlightSettings extends PersistentState {
    private final Map<UUID,HeliConfig.Difficulty> modes=new HashMap<>();
    private static final Type<PlayerFlightSettings> TYPE=new Type<>(PlayerFlightSettings::new,PlayerFlightSettings::read,null);
    public static PlayerFlightSettings get(MinecraftServer server){
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE,"no_24_heli_player_modes");
    }
    public HeliConfig.Difficulty mode(UUID player){return modes.getOrDefault(player,HeliConfig.INSTANCE.difficulty);}
    public void set(UUID player,HeliConfig.Difficulty mode){modes.put(player,mode);markDirty();}
    private static PlayerFlightSettings read(NbtCompound n,RegistryWrapper.WrapperLookup lookup){
        var state=new PlayerFlightSettings();var list=n.getList("Players",NbtElement.COMPOUND_TYPE);
        for(int i=0;i<list.size();i++){
            var entry=list.getCompound(i);int mode=entry.getInt("Mode");
            if(entry.containsUuid("Player")&&mode>=0&&mode<HeliConfig.Difficulty.values().length)
                state.modes.put(entry.getUuid("Player"),HeliConfig.Difficulty.values()[mode]);
        }
        return state;
    }
    @Override public NbtCompound writeNbt(NbtCompound n,RegistryWrapper.WrapperLookup lookup){
        n.putInt("Version",1);var list=new NbtList();
        modes.forEach((player,mode)->{var entry=new NbtCompound();entry.putUuid("Player",player);entry.putInt("Mode",mode.ordinal());list.add(entry);});
        n.put("Players",list);return n;
    }
}
