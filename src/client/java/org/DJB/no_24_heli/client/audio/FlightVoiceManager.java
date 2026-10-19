/** 文件：集中管理飞行语音、示例素材及按类别的播放完成门禁；端侧：CLIENT。 */
package org.DJB.no_24_heli.client.audio;

import java.util.EnumMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.client.hud.GroundProximityWarning;
import org.DJB.no_24_heli.entity.HelicopterEntity;

/**
 * 职责：统一选择高度、下降率、降落占用、起落架和旋翼失速语音，防止同类语音尚未结束时重复播放。
 * 引用 net.minecraft.client.sound.SoundManager 播放并检查音源；PositionedSoundInstance 使用不衰减的相对音源。
 * 生命周期：客户端主线程每 tick 调用；换机、离机、落地、入水、损毁或断线时停止旧机语音。
 * 排查：无声查 sounds.json、OGG 和游戏语音音量；重复查是否绕过 announce；禁止用固定时长猜测播放结束。
 */
@Environment(EnvType.CLIENT)
public final class FlightVoiceManager {
    /** 同一高度类别共用门禁，五档高度不会互相叠播；各类警告分别管理。 */
    public enum Category { HEIGHT, SINK_RATE, LANDING_BLOCKED, LANDING_GEAR, STALL }

    /** 所有语音入口及资源集中于此；资源包可替换 sounds/voice 下的同名 OGG。 */
    public enum Voice {
        HEIGHT_50(Category.HEIGHT,"height_50"), HEIGHT_30(Category.HEIGHT,"height_30"),
        HEIGHT_20(Category.HEIGHT,"height_20"), HEIGHT_10(Category.HEIGHT,"height_10"),
        HEIGHT_5(Category.HEIGHT,"height_5"), SINK_RATE(Category.SINK_RATE,"sink_rate"),
        LANDING_BLOCKED(Category.LANDING_BLOCKED,"landing_blocked"),
        LANDING_GEAR(Category.LANDING_GEAR,"landing_gear"), STALL(Category.STALL,"stall");

        /** 类别用于完成门禁；标识符对应 sounds.json 中的客户端声音事件。 */
        private final Category category;
        private final Identifier sound;

        /** 功能：绑定类别及资源事件；无 MC 状态副作用，初始化时构造。 */
        Voice(Category category,String path){this.category=category;this.sound=No_24_heli.id("voice."+path);}

        /** 功能：将近地提示档位映射到素材；参数 band 为 1～5；无 MC API，不改变监测状态。 */
        private static Voice height(int band){
            return switch(band){
                case 1->HEIGHT_50;case 2->HEIGHT_30;case 3->HEIGHT_20;case 4->HEIGHT_10;case 5->HEIGHT_5;
                default->throw new IllegalArgumentException("非法高度语音档位："+band);
            };
        }
    }

    /** 每类最多一个实际音源；仅在 SoundManager 确认结束后移除，丢弃播放期间的同类请求而不积压。 */
    private static final EnumMap<Category,SoundInstance> PLAYING=new EnumMap<>(Category.class);
    /** 当前乘机按实例识别，避免跨世界复用实体 ID 时串音；均为客户端瞬态。 */
    private static HelicopterEntity vehicle;
    /** 降落占用的进入边沿，持续占用只请求一次，恢复后才允许重新警告。 */
    private static boolean landingBlocked;
    /** 起落架警告只在离地十格以下触发；下降死区为 0.1 m/s，排除悬停浮点抖动。 */
    private static final double GEAR_WARNING_HEIGHT=10, DESCENT_EPSILON=.005;
    /** 旋翼转速低于 60% 警告、恢复至 70% 才重置，避免阈值附近反复触发；转速为额定比例。 */
    private static final double STALL_WARNING_RPM=.6, STALL_RESET_RPM=.7;
    /** 起落架危险条件的进入边沿；恢复安全状态后允许下一次提醒。 */
    private static boolean landingGearWarning;
    /** 旋翼低转速警告锁存，60%～70% 之间保持；落地或换机时也清除。 */
    private static boolean stallWarning;

    /** 仅提供客户端静态管理入口。 */
    private FlightVoiceManager(){}

    /**
     * 功能：统一的语音播报接口，同类上一条仍在播放时拒绝本次请求。
     * 引用 MC API：net.minecraft.client.sound.SoundManager#play 播放；相对音源不受第一/第三人称距离影响。
     * 端侧/线程：客户端主线程；参数：voice 为语音枚举；返回：是否提交播放；副作用：记录实际音源。
     * 排查：素材加载失败或静音由声音引擎处理；类别门禁同时覆盖同 tick 的重复和重入请求，不创建延迟队列。
     */
    public static boolean announce(Voice voice){
        var client=MinecraftClient.getInstance();
        if(client.player==null||client.world==null||client.player.getVehicle()!=vehicle||vehicle==null
                ||vehicle.flight.wreck||client.isPaused()||PLAYING.containsKey(voice.category))return false;
        var sound=new PositionedSoundInstance(voice.sound,SoundCategory.VOICE,1,1,Random.create(),false,0,
                SoundInstance.AttenuationType.NONE,0,0,0,true);
        // 先记入门禁再交给引擎，避免同一 tick 的第二个请求或播放回调重入绕过保护。
        PLAYING.put(voice.category,sound);client.getSoundManager().play(sound);return true;
    }

    /**
     * 功能：停止旧环境的全部飞行语音并清除边沿；引用 net.minecraft.client.sound.SoundManager#stop 停止实际音源。
     * 端侧：客户端主线程；参数：client 为客户端；无返回值；副作用：清除乘机引用和类别门禁。
     */
    private static void stopAll(MinecraftClient client){
        for(var sound:PLAYING.values())client.getSoundManager().stop(sound);
        PLAYING.clear();vehicle=null;landingBlocked=landingGearWarning=stallWarning=false;
    }

    /**
     * 功能：更新近地显示并按状态边沿播报，播放完成后才释放同类门禁。
     * 引用 MC API：net.minecraft.client.MinecraftClient 读取乘机；SoundManager#isPlaying 查询真实播放生命周期。
     * 端侧/线程：客户端主线程；参数：client 为客户端；无返回值；副作用：维护监测、语音与播放句柄。
     * 排查：暂停不消费告警边沿；换机和失效环境会停止旧音源，正常收尾不重放刚结束的警告。
     */
    public static void tick(MinecraftClient client){
        if(client.world==null||client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity heli)){
            GroundProximityWarning.tick(client);stopAll(client);return;
        }
        if(vehicle!=heli){stopAll(client);vehicle=heli;}
        if(client.isPaused())return;
        var notice=GroundProximityWarning.tick(client);
        if(heli.flight.wreck||heli.isTouchingWater()||!heli.flight.armed||heli.isOnGround()&&!heli.flight.landingBlocked){
            stopAll(client);return;
        }
        PLAYING.entrySet().removeIf(entry->!client.getSoundManager().isPlaying(entry.getValue()));
        boolean blocked=heli.flight.autoPhase==3&&heli.flight.landingBlocked;
        if(blocked&&!landingBlocked)announce(Voice.LANDING_BLOCKED);
        landingBlocked=blocked;
        double height=GroundProximityWarning.height();
        boolean gearUnsafe=height>0&&height<GEAR_WARNING_HEIGHT&&heli.flight.vy<-DESCENT_EPSILON&&!heli.flight.gear;
        if(gearUnsafe&&!landingGearWarning)announce(Voice.LANDING_GEAR);
        landingGearWarning=gearUnsafe;
        if(heli.flight.rpm>=STALL_RESET_RPM)stallWarning=false;
        else if(heli.flight.rpm<STALL_WARNING_RPM&&!stallWarning){announce(Voice.STALL);stallWarning=true;}
        if(notice!=null)announce(notice.warning()?Voice.SINK_RATE:Voice.height(notice.band()));
    }
}
