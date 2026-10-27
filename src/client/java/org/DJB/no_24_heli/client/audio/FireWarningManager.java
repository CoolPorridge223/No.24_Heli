/** 文件：任意部位火灾的持续消防警铃与 fire warning 语音；端侧：CLIENT；声音：fire_alarm、voice.fire_warning。 */
package org.DJB.no_24_heli.client.audio;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.random.Random;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.HelicopterEntity;

/**
 * 职责：消费服务端 FlightState.hasFire，所有乘员共用同一火灾提示，停机/落地也持续告警。
 * 引用 net.minecraft.client.sound.SoundManager 管理真实循环音源，PositionedSoundInstance 使用无衰减相对音源。
 * 生命周期：客户端每 tick 更新，修复熄火、离机、换机、残骸或断线立即停止并清除句柄。
 * 端侧/线程：仅客户端主线程，不计算血量或爆炸概率。
 * 排查：无声查音效/语音音量及 sounds.json；叠音查是否每 tick 重建句柄；离机残留查 stop 调用。
 */
@Environment(EnvType.CLIENT)
public final class FireWarningManager {
    /** 语音最多每四秒请求一次，实际播放结束前不重复；单位为未暂停的客户端 tick。 */
    private static final int VOICE_INTERVAL = 80;
    /** 当前告警飞机按实例识别，避免跨世界实体 ID 重用；音源与冷却均不持久化。 */
    private static HelicopterEntity vehicle;
    private static SoundInstance alarm, voice;
    private static int voiceCooldown;

    /** 静态客户端管理器，无实例。 */
    private FireWarningManager() {}

    /**
     * 功能：为当前乘坐的起火飞机维持一条循环警报，并间隔播报 fire warning。
     * 引用 net.minecraft.client.MinecraftClient 读取乘机与暂停，SoundManager#isPlaying 检查真实语音结束。
     * 端侧/线程：客户端主线程；任意座位、地面与停机都有效；无火时停止全部火灾音源。
     */
    public static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null
                || !(client.player.getVehicle() instanceof HelicopterEntity heli) || !heli.flight.hasFire()) {
            stop(client);
            return;
        }
        if (vehicle != heli) { stop(client); vehicle = heli; }
        if (client.isPaused()) return;
        if (alarm == null) {
            alarm = new PositionedSoundInstance(No_24_heli.id("fire_alarm"), SoundCategory.PLAYERS, .55f, 1,
                    Random.create(), true, 0, SoundInstance.AttenuationType.NONE, 0, 0, 0, true);
            client.getSoundManager().play(alarm);
        }
        if (voiceCooldown > 0) voiceCooldown--;
        if (voice != null && !client.getSoundManager().isPlaying(voice)) voice = null;
        if (voice == null && voiceCooldown == 0) {
            voice = new PositionedSoundInstance(No_24_heli.id("voice.fire_warning"), SoundCategory.VOICE, 1, 1,
                    Random.create(), false, 0, SoundInstance.AttenuationType.NONE, 0, 0, 0, true);
            client.getSoundManager().play(voice);
            voiceCooldown = VOICE_INTERVAL;
        }
    }

    /** 引用 net.minecraft.client.sound.SoundManager#stop 立即关闭实际音源并清空旧机状态；客户端主线程调用。 */
    private static void stop(MinecraftClient client) {
        if (alarm != null) client.getSoundManager().stop(alarm);
        if (voice != null) client.getSoundManager().stop(voice);
        alarm = voice = null;
        vehicle = null;
        voiceCooldown = 0;
    }
}
