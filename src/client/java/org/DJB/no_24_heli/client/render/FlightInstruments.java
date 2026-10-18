/** 文件：HUD 与机内仪表共用的连续显示读数；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.DJB.no_24_heli.entity.HelicopterEntity;

/**
 * 职责：按每架飞机保存仪表读数，使同步快照之间的表针、数字鼓和姿态连续变化。
 * 引用：net.minecraft.entity.Entity#getLerpedPos 提供世界高度，Entity#age 与帧插值提供游戏时间。
 * 生命周期：HUD 与实体面板每帧采样；弱键在实体卸载后释放，值不持有实体引用。
 * 端侧/线程：仅客户端渲染线程，仅读取权威状态，不参与飞行物理或告警判断。
 * 排查：多机串值查实体键；暂停或阴影重复绘制查采样时间；表针突跳查是否绕过本快照。
 */
@Environment(EnvType.CLIENT)
public final class FlightInstruments {
    /** 按实体对象区分读数，避免重进世界后实体编号复用或交替绘制多架飞机时重置。 */
    private static final Map<HelicopterEntity, FlightInstruments> READINGS = new WeakHashMap<>();
    /** 全部飞行仪表使用 120 毫秒时间常数；单次最多推进 250 毫秒，长帧也不直接跳值。 */
    private static final double RESPONSE_SECONDS = .12, MAX_FRAME_SECONDS = .25;
    /** 显示油量、旋翼转速与总距；单位分别为燃油单位、0～1、0～1，仅客户端使用。 */
    public double fuel, rpm, collective;
    /** 显示空速、世界高度与垂速；单位分别为 km/h、格、m/s，仅客户端使用。 */
    public double airspeed, altitude, verticalSpeed;
    /** 人工地平仪显示姿态，单位度；与同步姿态使用相同正方向。 */
    public double pitch, roll;
    /** 上次采样的实体 tick 时间；NaN 表示尚未初始化，游戏暂停时不推进动画。 */
    private double sampleTick = Double.NaN;

    /** 功能：只由实体读数缓存创建快照；无 MC API、副作用或跨端状态。 */
    private FlightInstruments() {}

    /**
     * 功能：取得当前飞机的平滑显示快照，HUD、机内圆表及数字鼓共用同一读数。
     * 引用 MC API：net.minecraft.entity.Entity#getLerpedPos 提供帧间高度；Entity#age 提供游戏 tick。
     * 端侧/线程：客户端渲染线程；参数：heli 为飞机，delta 为帧插值；返回：该飞机的显示快照。
     * 副作用：推进客户端缓存，不写入 FlightState；同一游戏时间重复绘制不加速响应。
     * 排查：仅首次看到实体时初始化；切换视角、长帧或再次显示均沿用已有读数。
     */
    public static FlightInstruments sample(HelicopterEntity heli, float delta) {
        FlightInstruments display = READINGS.computeIfAbsent(heli, ignored -> new FlightInstruments());
        double now = heli.age + (double) delta;
        boolean first = Double.isNaN(display.sampleTick);
        if (!first && now <= display.sampleTick) return display;
        double seconds = first ? 0 : Math.clamp((now - display.sampleTick) / 20, 0, MAX_FRAME_SECONDS);
        double blend = first ? 1 : 1 - Math.exp(-seconds / RESPONSE_SECONDS);
        var state = heli.flight;
        display.fuel += (state.fuel - display.fuel) * blend;
        display.rpm += (state.rpm - display.rpm) * blend;
        display.collective += (state.collective - display.collective) * blend;
        display.airspeed += (Math.hypot(state.vx, state.vz) * 72 - display.airspeed) * blend;
        display.altitude += (heli.getLerpedPos(delta).y - display.altitude) * blend;
        display.verticalSpeed += (state.vy * 20 - display.verticalSpeed) * blend;
        display.pitch += (state.pitch - display.pitch) * blend;
        display.roll += (state.roll - display.roll) * blend;
        display.sampleTick = now;
        return display;
    }
}
