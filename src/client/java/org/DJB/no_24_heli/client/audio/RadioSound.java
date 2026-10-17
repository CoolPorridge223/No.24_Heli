package org.DJB.no_24_heli.client.audio;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.HelicopterEntity;


import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.*;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.floatprovider.ConstantFloatProvider;
import net.minecraft.util.math.random.Random;

/** 飞机电台音源：机内使用不衰减的相对声音，机外按玩家到飞机距离计算音量。 */
public final class RadioSound extends MovingSoundInstance {
    private final HelicopterEntity heli;
    private final int track,revision;
    private final boolean cabin;
    public RadioSound(HelicopterEntity heli){
        super(No_24_heli.RADIO_SOUND,SoundCategory.RECORDS,Random.create());this.heli=heli;
        track=heli.flight.radioTrack;revision=heli.flight.radioRevision;
        cabin=MinecraftClient.getInstance().player!=null&&MinecraftClient.getInstance().player.getVehicle()==heli;
        // 音量依据玩家位置，不依据第三人称相机位置；驾驶员/副驾驶拉远镜头仍保持同一机内音量。
        relative=true;attenuationType=AttenuationType.NONE;repeat=true;repeatDelay=0;volume=gain(cabin,MinecraftClient.getInstance().player==null?64:MinecraftClient.getInstance().player.getPos().distanceTo(heli.getPos()));
    }
    /** 直接提供流式Sound，跳过仅认识OGG文件的资源注册检查，再由专用加载器解释虚拟路径。 */
    @Override public WeightedSoundSet getSoundSet(SoundManager manager){
        long elapsed=Math.max(0,heli.getWorld().getTime()-heli.flight.radioStartedTick)*50;
        sound=new Sound(No_24_heli.id("radio/"+track+"/"+elapsed),ConstantFloatProvider.create(1),ConstantFloatProvider.create(1),1,Sound.RegistrationType.FILE,true,false,64);
        WeightedSoundSet set=new WeightedSoundSet(getId(),null);set.add(sound);return set;
    }
    public static float gain(boolean inside,double distance){return inside?.65f:(float)(.65*Math.pow(Math.max(0,1-distance/64),2));}
    @Override public boolean shouldAlwaysPlay(){return true;}
    /** 管理器独立核对槽位版本；即使解码失败导致声音引擎不再tick，重选歌曲也能释放旧实例。 */
    public boolean isObsolete(){
        var mc=MinecraftClient.getInstance();
        return mc.player==null||mc.world!=heli.getWorld()||heli.isRemoved()||heli.flight.wreck||heli.flight.radioTrack!=track||heli.flight.radioRevision!=revision
                ||cabin!=(mc.player.getVehicle()==heli);
    }
    @Override public void tick(){
        var mc=MinecraftClient.getInstance();
        if(isObsolete()){setDone();return;}
        volume=gain(cabin,mc.player.getPos().distanceTo(heli.getPos()));
        // 相对音源固定在听者中心；机外虽不使用相机衰减，仍有随实际距离下降的外放音量。
        x=y=z=0;
    }
}
