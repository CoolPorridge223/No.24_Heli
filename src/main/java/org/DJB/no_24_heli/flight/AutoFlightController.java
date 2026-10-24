/** 文件：一键起降的杆量生成器；端侧：COMMON；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.flight;

/**
 * 职责：休闲模式一键起降的每 tick 控制，只输出周期变距、总距和脚蹬意图。
 * 引用：{@link FlightState} 保存锚点与目标高度，{@link FlightPhysics} 提供限幅；不直接调用世界或移动实体。
 * 生命周期：由 HelicopterEntity 在 autoPhase != 0 时每 tick 调用；起飞/降落完成或受阻由实体裁决。
 * 端侧/线程：COMMON，仅逻辑服务端主线程。
 * 排查：不上升查总距/预转/升力；降落不下降查 landingBlocked 与锚点误差；横漂查锚点反馈和风。
 */
public final class AutoFlightController {
    /** 自动起飞相对开始位置的悬停高度，单位格；启动净空、爬升目标与提示共用二十格。 */
    public static final double TAKEOFF_HEIGHT = 20;
    /** 降落前先稳定：水平速度不超过一米/秒，俯仰/横滚不超过三度，偏航率不超过六度/秒。 */
    private static final double LANDING_SPEED = .05, LANDING_TILT = 3, LANDING_YAW_RATE = .3;
    /** 对准承重点后下降，偏差不超过十二厘米，避免在预检滑橇支撑区域外提前触地。 */
    private static final double LANDING_POSITION_ERROR = .12;
    private AutoFlightController() {}

    /**
     * 功能：以起降开始时的水平坐标为锚点抵抗惯性和风，偏航保持当前方向，输出飞行杆量。
     *
     * 引用 MC API：无；高度、接地与天气由 HelicopterEntity 从 net.minecraft.entity.Entity
     * 和 net.minecraft.world.World 采样后传入，本方法不写世界。
     *
     * 端侧/线程：仅逻辑服务端，每 tick 一次。
     * 参数：x/y/z 为当前实体原点；grounded 为真实方块支撑。
     * 副作用：修改总距、周期变距、脚蹬、目标高度、起落架和 autoPhase；不直接改位置/速度。
     * 排查：起飞总距为 0 查 autoPhase/armed；降落不下降查 landingBlocked 与 settle 条件；
     *      终点悬停不结束查 y 与 autoY 的 0.25 格窗口及垂速。
     */
    public static void step(FlightState s,double x,double y,double z,boolean grounded){
        // 起降期间始终由高度闭环控制总距；起飞离开实际方块支撑后自动收架，降落阶段始终放架。
        s.trim=true;s.altitudeHold=true;s.cruise=false;s.gear=grounded||s.autoPhase==3;
        // 水平锚点 PD：位置误差乘 0.008、当前速度乘 0.15，限幅 ±0.015 格/tick²，避免修正过冲。
        double ax=FlightPhysics.clamp((s.autoX-x)*.008-s.vx*.15,-.015,.015);
        double az=FlightPhysics.clamp((s.autoZ-z)*.008-s.vz*.15,-.015,.015);
        double yaw=Math.toRadians(s.yaw);
        // 把世界系加速度转成机头/侧向杆量；30°/32° 对应俯仰和横滚的最大控制角。
        s.cyclicPitch=(float)(-Math.toDegrees(Math.atan2(-Math.sin(yaw)*ax+Math.cos(yaw)*az,FlightPhysics.GRAVITY))/30);
        s.cyclicRoll=(float)(-Math.toDegrees(Math.atan2(Math.cos(yaw)*ax+Math.sin(yaw)*az,FlightPhysics.GRAVITY))/32);
        // 偏航率阻尼脚蹬；自动起降不叠加额外总距输入，总距完全交给高度闭环。
        s.pedal=(float)FlightPhysics.clamp(-s.yawRate*.7,-1,1);s.collectiveInput=0;
        if(!s.landingBlocked){
            // 降落先稳定：速度、锚点偏差、俯仰/横滚或偏航率任一超限时以当前高度为目标，不下压。
            boolean settling=s.autoPhase==3&&(Math.hypot(s.vx,s.vz)>LANDING_SPEED
                    ||Math.hypot(s.autoX-x,s.autoZ-z)>LANDING_POSITION_ERROR
                    ||Math.abs(s.pitch)>LANDING_TILT||Math.abs(s.roll)>LANDING_TILT
                    ||Math.abs(s.yawRate)>LANDING_YAW_RATE);
            s.targetAltitude=settling?y:s.autoY;
        }
        // 预转完成才进入爬升；到目标高度附近且垂速足够小才结束自动起飞。
        if(s.autoPhase==1&&s.armed)s.autoPhase=2;
        if(s.autoPhase==2&&!grounded&&Math.abs(y-s.autoY)<.25&&Math.abs(s.vy)<.025)s.cancelAutoFlight();
    }
}
