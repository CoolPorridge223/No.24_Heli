package org.DJB.no_24_heli.flight;

/** 平面导航速度闭环。只输出操纵意图，不瞬移、不跳过旋翼和碰撞物理。 */
public final class NavigationController {
    private NavigationController() {}

    /** 按剩余距离平滑减速，靠近目标时反打姿态刹车，最终保持当前位置。 */
    public static void step(FlightState s, double x, double z) {
        step(s,x,z,s.waypointX,s.waypointZ,.80);
    }
    /** 路线段与避障器提供当前目标和限速；保留原手动飞行积分器。 */
    public static void step(FlightState s,double x,double z,double targetX,double targetZ,double limit){
        double dx=targetX-x, dz=targetZ-z, distance=Math.hypot(dx,dz);
        // Minecraft偏航0朝南(+Z)，90朝西(-X)，用atan2(-dx,dz)得到正确机头方向。
        // 先用速度闭环消除漂移，再以受限脚蹬转向；误差小于6度且偏航角速度足够低才前飞。
        double heading=Math.toDegrees(Math.atan2(-dx,dz));
        double error=((heading-s.yaw+540)%360+360)%360-180;
        if(distance>3){
            s.pedal=(float)FlightPhysics.clamp(error*.035-s.yawRate*.7,-1,1);
            if(Math.abs(error)>30)s.navAligned=false;
            if(!s.navAligned&&Math.abs(error)<6&&Math.abs(s.yawRate)<.7)s.navAligned=true;
        }else s.pedal=(float)FlightPhysics.clamp(-s.yawRate*.7,-1,1);
        double alignment=Math.max(0,Math.cos(Math.toRadians(error)));
        double speed=s.navAligned?Math.min(limit,distance*.025)*alignment:0;
        double desiredX=distance<.5?0:dx/Math.max(distance,.001)*speed;
        double desiredZ=distance<.5?0:dz/Math.max(distance,.001)*speed;
        double ax=FlightPhysics.clamp((desiredX-s.vx)*.10,-.018,.018);
        double az=FlightPhysics.clamp((desiredZ-s.vz)*.10,-.018,.018);
        double yaw=Math.toRadians(s.yaw);
        double forward=-Math.sin(yaw)*ax+Math.cos(yaw)*az;
        double lateral=Math.cos(yaw)*ax+Math.sin(yaw)*az;
        s.cyclicPitch=(float)FlightPhysics.clamp(-Math.toDegrees(Math.atan2(forward,FlightPhysics.GRAVITY))/30,-.65,.65);
        s.cyclicRoll=(float)FlightPhysics.clamp(-Math.toDegrees(Math.atan2(lateral,FlightPhysics.GRAVITY))/32,-.65,.65);
        s.collectiveInput=0;s.trim=true;s.altitudeHold=true;s.cruise=false;
    }
}
