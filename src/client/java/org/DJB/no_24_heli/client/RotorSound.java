package org.DJB.no_24_heli.client;

import net.minecraft.client.sound.*;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.random.Random;
import org.DJB.no_24_heli.*;

/** 单一无缝循环旋翼音源，随预转和停转连续变调，避免每 tick 重新播放。 */
public class RotorSound extends MovingSoundInstance {
    private final HelicopterEntity heli;
    public RotorSound(HelicopterEntity heli){super(No_24_heli.ROTOR_SOUND,SoundCategory.NEUTRAL,Random.create());this.heli=heli;repeat=true;repeatDelay=0;volume=0;}
    @Override public boolean canPlay(){return !heli.isRemoved();}
    @Override public boolean shouldAlwaysPlay(){return true;}
    @Override public void tick(){
        if(heli.isRemoved()||heli.flight.rpm<.01){setDone();return;}
        x=heli.getX();y=heli.getY()+1;z=heli.getZ();
        double speed=Math.hypot(heli.flight.vx,heli.flight.vz);
        volume=(float)(heli.flight.rpm*(.3+heli.flight.collective*.55+Math.min(.25,speed*.2)));
        pitch=(float)(.35+heli.flight.rpm*.7+Math.min(.2,speed*.15));
        if(heli.flight.rotor<60)pitch+=(float)(Math.sin(heli.age*2.7)*.06);
    }
}
