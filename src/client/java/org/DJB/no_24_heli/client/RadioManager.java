package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import org.DJB.no_24_heli.HelicopterEntity;
import java.util.*;

/** 管理每架已跟踪飞机的电台生命周期；退出世界、停止播放或回收飞机时及时释放音源。 */
public final class RadioManager {
    private static final Map<Integer,RadioSound> active=new HashMap<>();
    private static final boolean[] available=new boolean[8];
    private static Object world;
    public static void tick(MinecraftClient mc){
        if(world!=mc.world){for(var sound:active.values())mc.getSoundManager().stop(sound);active.clear();world=mc.world;}
        if(mc.world==null||mc.player==null)return;
        if(mc.world.getTime()%20==0)for(int i=0;i<8;i++)available[i]=RadioLibrary.available(i);
        active.entrySet().removeIf(e->{
            var sound=e.getValue();if(!sound.isDone()&&!sound.isObsolete())return false;
            mc.getSoundManager().stop(sound);return true;
        });
        for(var entity:mc.world.getEntities())if(entity instanceof HelicopterEntity h&&!h.flight.wreck&&h.flight.radioTrack>=0&&available[h.flight.radioTrack]
                &&h.squaredDistanceTo(mc.player)<64*64&&!active.containsKey(h.getId())&&RadioLibrary.prepare(h.flight.radioTrack)){
            RadioSound sound=new RadioSound(h);active.put(h.getId(),sound);mc.getSoundManager().play(sound);
        }
    }
}
