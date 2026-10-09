package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.HeliConfig;

/** 固定 20Hz 的服务端飞行积分器。旋翼储能与动力分离，使无动力自转真实可控。 */
public final class FlightPhysics {
    /** 每 tick 的重力加速度，单位为格/tick²。 */
    public static final double GRAVITY = 0.04;
    public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double approach(double a, double b, double step) { return a + clamp(b-a, -step, step); }

    /** 雨和风由世界提供；落地判定与碰撞由实体在积分之后处理。 */
    public static void step(FlightState s, HeliConfig c, boolean grounded, boolean rain, double altitude, long tick) {
        if (s.wreck) { s.rpm = approach(s.rpm, 0, .012); s.vy -= GRAVITY; return; }
        double speed = Math.hypot(s.vx, s.vz);
        boolean powered = s.running && s.engine > 0 && s.fuel > 0;
        if (!powered) { s.running = false; s.altitudeHold = false; s.cruise = false; }
        if (s.collectiveInput != 0) s.targetAltitude = altitude;
        s.collective = clamp(s.collective + s.collectiveInput * .008, 0, 1);
        if (s.altitudeHold && s.armed && s.collectiveInput == 0 && !grounded) {
            double target = .56 + (s.targetAltitude-altitude)*.035 - s.vy*.95;
            s.collective = approach(s.collective, clamp(target, .1, 1), .015);
        }
        // 启动必须达到额定转速；此后引擎损伤降低功率而不是绕过起飞门槛。
        double spool = 1.0 / c.spoolTicks();
        if (powered) {
            s.rpm = approach(s.rpm, 1, spool);
            if (s.rpm >= .995) s.armed = true;
        } else {
            // 下洗气流驱动旋翼。低总距蓄能，接地前拉总距消耗转动能量完成拉平。
            double airflow = clamp((-s.vy-.08)*1.7 + speed*.16, 0, 1);
            double drive = grounded ? 0 : airflow * (1-s.collective) * .017;
            s.rpm = clamp(s.rpm + drive - .0035 - s.collective*.011, 0, 1);
            if (grounded && s.rpm < .05) s.armed = false;
        }
        // 地面接触约束：操纵杆与鼠标不能让未离地的机身绕起落架晃动。
        if (grounded) { s.cyclicPitch=0; s.cyclicRoll=0; s.pedal=0; s.pitch=approach(s.pitch,0,2); s.roll=approach(s.roll,0,2); }
        double requestedPitch = s.cyclicPitch * 30;
        if (Math.abs(s.cyclicPitch) > .05) s.targetSpeed = speed;
        if (!grounded && s.cruise && Math.abs(s.cyclicPitch) < .05) requestedPitch = clamp(-(s.targetSpeed-speed)*65 - speed*10, -26, 18);
        // 自动配平松杆归中；硬核保留姿态，需要反打操纵杆。
        if (s.trim || Math.abs(s.cyclicPitch) > .03 || s.cruise)
            s.pitch = approach(s.pitch, requestedPitch, c.hardcore() ? .7 : 1.1);
        if (s.trim || Math.abs(s.cyclicRoll) > .03)
            s.roll = approach(s.roll, s.cyclicRoll*32, c.hardcore() ? .8 : 1.2);
        double torque = c.hardcore() ? (1-s.tail/100)*s.rpm*(.4+s.collective)*2.8 : 0;
        // 无动力时仍有小幅机身自旋；脚蹬 Q/E 提供手动抗扭。
        if (!powered && !grounded) torque += s.rpm*.18;
        s.yawRate = (s.yawRate + torque*.08 + s.pedal*.22)*.94;
        if (grounded) s.yawRate=0;
        s.yaw = (s.yaw + s.yawRate) % 360;
        double pitch = Math.toRadians(s.pitch), roll = Math.toRadians(s.roll), yaw = Math.toRadians(s.yaw);
        double enginePower = powered ? (.45+.55*s.engine/100)*(rain ? .94 : 1) : 1;
        double rotorPower = s.rotor/100 * (rain ? .97 : 1);
        // 未完成预转禁止离地；已起飞后允许残余转速和自转产生升力。
        double lift = s.armed ? .072*s.collective*s.rpm*s.rpm*rotorPower*enginePower : 0;
        double forward = -Math.sin(pitch)*lift, lateral = -Math.sin(roll)*lift;
        s.vx += -Math.sin(yaw)*forward + Math.cos(yaw)*lateral;
        s.vz += Math.cos(yaw)*forward + Math.sin(yaw)*lateral;
        s.vy += lift*Math.cos(pitch)*Math.cos(roll)-GRAVITY;
        // 无动力的前飞气流仍产生升力，支持有速度的下滑和接地前拉平。
        if (!powered && s.armed && !grounded)
            s.vy += Math.min(.028, Math.max(0,-s.vy)*.042*s.rpm*s.rotor/100);
        double drag = c.hardcore() ? .994 : .981;
        s.vx *= drag; s.vz *= drag; s.vy *= .985;
        if (c.wind && !grounded) {
            double gust = rain ? .0015 : .0005;
            s.vx += Math.sin(tick*.013)*gust; s.vz += Math.cos(tick*.009)*gust;
        }
        if (s.rotor < 65 && s.rpm > .3) {
            double shake = (1-s.rotor/100)*.0025*s.rpm;
            s.vx += Math.sin(tick*2.3)*shake; s.vz += Math.cos(tick*1.7)*shake;
        }
        s.vx=clamp(s.vx,-2.2,2.2); s.vz=clamp(s.vz,-2.2,2.2); s.vy=clamp(s.vy,-2.5,1.1);
        if (grounded) { s.vx*=.7; s.vz*=.7; }
        double maneuver = Math.abs(s.cyclicPitch)+Math.abs(s.cyclicRoll)+Math.abs(s.collectiveInput)*.5;
        double consumption = powered ? .016*(1+speed*2+maneuver*3)*(2-s.engine/100)*(c.hardcore()?1.7:1) : 0;
        double leak = s.tank < 80 ? (80-s.tank)*.0015 : 0;
        s.fuel=Math.max(0,s.fuel-consumption-leak);
    }
    /** 跳机后先保持三秒高度，再丢失动力并持续倾斜；驾驶员重新登机可中断失控过程。 */
    public static void handleAbandonment(FlightState s, HeliConfig c, boolean grounded, boolean hasPilot, double altitude) {
        if (hasPilot || grounded || !s.armed || s.wreck) {
            if (s.abandonedTicks>0) {
                s.altitudeHold=false; s.cruise=false; s.trim=!c.hardcore();
                if (grounded) s.collective=0;
            }
            s.abandonedTicks=0;
            return;
        }
        s.clearInputs();
        if (s.abandonedTicks++==0) s.targetAltitude=altitude;
        s.cruise=false;
        if (s.abandonedTicks<=60) {
            s.trim=true; s.altitudeHold=s.running;
            // 立即消除遗留爬升趋势，并抑制水平漂移，不使用瞬移。
            s.vy=clamp(s.vy,-.08,0); s.vx*=.88; s.vz*=.88;
            s.pitch=approach(s.pitch,0,2); s.roll=approach(s.roll,0,2);
            s.collective=clamp(.56+(s.targetAltitude-altitude)*.04-s.vy*.95,0,1);
        } else {
            s.altitudeHold=false; s.trim=false; s.running=false;
            s.collective=approach(s.collective,0,.006);
            s.roll=approach(s.roll,55,.4); s.pitch=approach(s.pitch,38,.2);
            s.yawRate+=.08;
        }
    }
    /** 撞击判据只使用实际被碰撞阻挡的速度，避免平行擦边被误判为全速撞击。 */
    public static boolean catastrophic(double impact, FlightState s, HeliConfig c) {
        double limit = c.hardcore() ? .66 : .9;
        if (!s.gear) limit *= .75;
        if (Math.abs(s.pitch)>30 || Math.abs(s.roll)>30) limit *= .7;
        return impact > limit;
    }
}
