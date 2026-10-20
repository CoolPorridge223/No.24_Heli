package org.DJB.no_24_heli.flight;

/** 一键起降只生成现有物理系统可接受的杆量，不直接设置坐标或跳过碰撞。 */
public final class AutoFlightController {
    /** 自动起飞相对开始位置的悬停高度，单位格；启动净空、爬升目标与提示共用二十格。 */
    public static final double TAKEOFF_HEIGHT = 20;
    /** 降落前先稳定：水平速度不超过一米/秒，俯仰/横滚不超过三度，偏航率不超过六度/秒。 */
    private static final double LANDING_SPEED = .05, LANDING_TILT = 3, LANDING_YAW_RATE = .3;
    /** 对准承重点后下降，偏差不超过十二厘米，避免在预检滑橇支撑区域外提前触地。 */
    private static final double LANDING_POSITION_ERROR = .12;
    private AutoFlightController() {}

    /**
     * 以起降开始时的水平坐标为锚点抵抗惯性和风；偏航保持当前方向。
     * 起飞总距由高度控制从当前值渐变，未预转完成不产生升力，离地后爬升到二十格。
     * 降落时沿同一垂线降至经实体验证的支撑面；通道占用时保持实体捕获的暂停高度，接地停机由实体负责。
     */
    public static void step(FlightState s,double x,double y,double z,boolean grounded){
        // 起飞离开实际方块支撑后自动收架，降落阶段始终放架。
        s.trim=true;s.altitudeHold=true;s.cruise=false;s.gear=grounded||s.autoPhase==3;
        double ax=FlightPhysics.clamp((s.autoX-x)*.008-s.vx*.15,-.015,.015);
        double az=FlightPhysics.clamp((s.autoZ-z)*.008-s.vz*.15,-.015,.015);
        double yaw=Math.toRadians(s.yaw);
        s.cyclicPitch=(float)(-Math.toDegrees(Math.atan2(-Math.sin(yaw)*ax+Math.cos(yaw)*az,FlightPhysics.GRAVITY))/30);
        s.cyclicRoll=(float)(-Math.toDegrees(Math.atan2(Math.cos(yaw)*ax+Math.sin(yaw)*az,FlightPhysics.GRAVITY))/32);
        s.pedal=(float)FlightPhysics.clamp(-s.yawRate*.7,-1,1);s.collectiveInput=0;
        if(!s.landingBlocked){
            boolean settling=s.autoPhase==3&&(Math.hypot(s.vx,s.vz)>LANDING_SPEED
                    ||Math.hypot(s.autoX-x,s.autoZ-z)>LANDING_POSITION_ERROR
                    ||Math.abs(s.pitch)>LANDING_TILT||Math.abs(s.roll)>LANDING_TILT
                    ||Math.abs(s.yawRate)>LANDING_YAW_RATE);
            s.targetAltitude=settling?y:s.autoY;
        }
        if(s.autoPhase==1&&s.armed)s.autoPhase=2;
        if(s.autoPhase==2&&!grounded&&Math.abs(y-s.autoY)<.25&&Math.abs(s.vy)<.025)s.cancelAutoFlight();
    }
}
