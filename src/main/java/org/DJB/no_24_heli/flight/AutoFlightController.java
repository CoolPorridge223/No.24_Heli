package org.DJB.no_24_heli.flight;

/** 一键起降只生成现有物理系统可接受的杆量，不直接设置坐标或跳过碰撞。 */
public final class AutoFlightController {
    private AutoFlightController() {}

    /**
     * 以起降开始时的水平坐标为锚点抵抗惯性和风；偏航保持当前方向。
     * 起飞时先等预转完成，再渐增总距离地，最后交给高度控制爬升到十格。
     * 降落时沿同一垂线降至经实体验证的支撑面；接地停机由实体负责。
     */
    public static void step(FlightState s,double x,double y,double z,boolean grounded){
        s.trim=true;s.altitudeHold=true;s.cruise=false;s.gear=true;
        double ax=FlightPhysics.clamp((s.autoX-x)*.008-s.vx*.15,-.015,.015);
        double az=FlightPhysics.clamp((s.autoZ-z)*.008-s.vz*.15,-.015,.015);
        double yaw=Math.toRadians(s.yaw);
        s.cyclicPitch=(float)(-Math.toDegrees(Math.atan2(-Math.sin(yaw)*ax+Math.cos(yaw)*az,FlightPhysics.GRAVITY))/30);
        s.cyclicRoll=(float)(-Math.toDegrees(Math.atan2(Math.cos(yaw)*ax+Math.sin(yaw)*az,FlightPhysics.GRAVITY))/32);
        s.pedal=(float)FlightPhysics.clamp(-s.yawRate*.7,-1,1);s.collectiveInput=0;
        s.targetAltitude=s.autoY;
        if(s.autoPhase==1&&s.armed)s.autoPhase=2;
        if(s.autoPhase==2&&grounded&&s.armed)s.collective=Math.min(1,s.collective+.012);
        if(s.autoPhase==2&&!grounded&&Math.abs(y-s.autoY)<.25&&Math.abs(s.vy)<.025)s.autoPhase=0;
    }
}
