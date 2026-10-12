package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import org.DJB.no_24_heli.*;

/** 近地分级告警：每 tick 采样一次供音效和 HUD 共用，只在已起飞且未接地时工作。 */
public final class GroundProximityWarning {
    private static int vehicle=-1,level;
    private static long nextSound;
    public static int level(){return level;}
    /** 30/20/10/5 米依次增强；射线未命中地面时返回 65 米，不误触 30 米档。 */
    public static int band(double height){return height<=5?4:height<=10?3:height<=20?2:height<=30?1:0;}
    public static void tick(MinecraftClient client){
        if(client.world==null||client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity h)){
            vehicle=-1;level=0;nextSound=0;return;
        }
        if(vehicle!=h.getId()){vehicle=h.getId();level=0;nextSound=0;}
        double height=h.heightAboveGround();
        int current=!h.flight.wreck&&h.flight.armed&&!h.isOnGround()&&!h.isTouchingWater()&&height>.15?band(height):0;
        long now=client.world.getTime();if(current>level)nextSound=now;level=current;
        if(level==0){nextSound=now;return;}
        if(now>=nextSound){
            int[] interval={0,50,30,15,7};float[] volume={0,.22f,.4f,.65f,.9f};
            //client.getSoundManager().play(PositionedSoundInstance.master(No_24_heli.ALARM,.8f+level*.18f,volume[level]));
            nextSound=now+interval[level];
        }
    }
}
