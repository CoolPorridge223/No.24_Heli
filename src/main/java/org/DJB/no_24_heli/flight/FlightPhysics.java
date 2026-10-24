/** 文件：服务端飞行物理积分器，处理动力、升力、阻力、风、姿态与油耗；端侧：COMMON；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.data.HeliConfig;


/**
 * 职责：固定 20Hz 的服务端飞行积分器，把输入、动力、升力、阻力、风和姿态合成新速度。
 * 引用：{@link FlightState} 保存机况与速度，{@link HeliConfig} 提供难度/天气等参数；
 *      net.minecraft.entity.Entity#move 由实现在积分后调用，本类不做世界碰撞与位置写入。
 * 生命周期：每 tick 由 HelicopterEntity 在服务端调用一次；纯计算，不持有世界或客户端状态。
 * 端侧/线程：COMMON，仅逻辑服务端主线程；客户端不调用，否则会与服务端权威速度冲突。
 * 排查：起飞下坠查总距/转速/发动机/雨天；抖动查是否重复积分；自转异常查储能与总距顺序。
 */
public final class FlightPhysics {
    /** 每 tick 的重力加速度，单位为格/tick²。 */
    public static final double GRAVITY = 0.04;
    /** 接地时机体竖轴偏离世界竖轴超过三十度即坠毁，包含俯仰与横滚的合成倾斜。 */
    private static final double MAX_LANDING_TILT = 30;
    /** 起落架放下时每 tick 额外保留 99.5% 的水平速度；只在空中施加，不改变垂直升力或地面摩擦。 */
    private static final double GEAR_DRAG = .995;
    /** 高度误差转目标垂速的增益，单位 tick⁻¹；垂速闭环响应与加速度上限分别为 tick⁻¹、格/tick²。 */
    private static final double ALTITUDE_GAIN = .025, VERTICAL_RESPONSE = .12, MAX_VERTICAL_ACCELERATION = .012;
    /** 高度保持与手动升降的垂速上限，单位格/tick，0.16 对应 3.2 m/s。 */
    private static final double MAX_ASSISTED_VERTICAL_SPEED = .16;
    /** 自动高度控制每 tick 最多改变 0.8% 总距，并以 25% 比例趋近目标，抑制突变与来回摆动。 */
    private static final double COLLECTIVE_STEP = .008, COLLECTIVE_RESPONSE = .25;
    /** 松开升降键后垂速小于 0.1 m/s 时捕获当前高度，避免恢复操纵前的旧高度。 */
    private static final double ALTITUDE_CAPTURE_SPEED = .005;

    /**
     * 功能：判断当前部件和天气下，额定转速、最大总距、水平姿态的动力升力能否超过重力。
     * 引用 MC API：无，复用物理积分器的旋翼、发动机与雨天系数；不把正常预转误判成动力不足。
     * 端侧：COMMON 纯计算，服务端用于起飞预检；参数：s 为机况，rain 为旋翼处是否下雨。
     * 返回：是否具有水平离地所需的升力；副作用：无；排查：此处只预估动力，不替代净空或燃油检查。
     */
    public static boolean hasTakeoffPower(FlightState s, boolean rain) {
        double enginePower = (.45+.55*s.engine/100)*(rain ? .94 : 1);
        double rotorPower = s.rotor/100*(rain ? .97 : 1);
        return s.engine > 0 && .072*rotorPower*enginePower > GRAVITY;
    }

    /** 功能：取得水平速度保留比例；纯服务端计算，无 MC API，导航前馈与物理使用同一阻力。 */
    public static double horizontalDrag(boolean hardcore, boolean gearDown) {
        return (hardcore ? .994 : .981) * (gearDown ? GEAR_DRAG : 1);
    }
    /** 功能：把数值限制到闭区间，供控制目标、姿态与速度限幅共用；无 MC API。 */
    public static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** 功能：以固定步长趋近目标，返回本 tick 的新值；用于转速、总距和姿态，避免突变。 */
    private static double approach(double a, double b, double step) { return a + clamp(b-a, -step, step); }

    /**
     * 功能：推进一个服务端 tick 的飞行积分，处理模式约束、动力/转速、姿态、升力、阻力、风、限幅和油耗。
     *
     * 引用 MC API：
     *   - 纯计算，不直接调用世界；height/grounded/rain 由 {@link net.minecraft.entity.Entity} 与
     *     net.minecraft.world.World 在实体 tick 中采样后传入，碰撞和位移交给 Entity#move。
     *
     * 端侧/线程：仅逻辑服务端；每个实体每 tick 恰好调用一次。
     * 参数：grounded 表示真实方块支撑，rain 为旋翼处降雨，altitude 为当前世界 Y，tick 为世界时间。
     * 副作用：原地修改 s 的速度、姿态、总距、旋翼转速和燃油；不写世界、不播放声音/粒子。
     * 排查：地面仍飘起查 grounded；无动力不自转查 armed/rpm；满转不升力查 rotor/engine/rain；
     *      速度被清零查模式约束或外部碰撞，不要在本方法内再加位置兜底。
     */
    public static void step(FlightState s, HeliConfig c, boolean grounded, boolean rain, double altitude, long tick) {
        // 约束在物理入口再次执行；即使旧存档或伪造数据包留下辅助标志，也不能绕过此模式。
        if(c.unassisted()){s.trim=false;s.altitudeHold=false;s.cruise=false;s.autopilot=false;s.cancelAutoFlight();}
        if (s.wreck) {
            s.rpm = approach(s.rpm, 0, .012);
            // 残骸保留空中坠落惯性，接地阻停平移；实体端继续按承重点倾倒，不进入完好飞机的飞控。
            if (grounded) { s.vx = 0; s.vz = 0; s.vy = -GRAVITY; }
            else s.vy -= GRAVITY;
            return;
        }
        // 正常飞行磨损按20Hz计时：引擎每分钟约2.4点，机身/旋翼/尾桨较慢。
        // 只在真正离地且旋翼工作时扣除，地面停放、残骸和菜单停机不会悄悄掉耐久。
        if(c.unassisted()&&!grounded&&s.armed&&s.rpm>.2){
            s.engine=Math.max(0,s.engine-.002);s.hull=Math.max(0,s.hull-.0005);
            s.rotor=Math.max(0,s.rotor-.0006);s.tail=Math.max(0,s.tail-.0003);
        }
        // 积分顺序固定：动力/转速 → 姿态/偏航 → 总距/升力 → 阻力/风 → 限幅/油耗；
        // 世界碰撞、接地和位置写入由实体在调用本方法之后处理，不能在这里提前改坐标。
        double speed = Math.hypot(s.vx, s.vz);
        boolean powered = s.running && s.engine > 0 && s.fuel > 0;
        if (!powered) { s.running = false; s.altitudeHold = false; s.cruise = false; }
        boolean autoTakeoff = grounded && (s.autoPhase == 1 || s.autoPhase == 2);
        boolean altitudeControl = s.altitudeHold && powered && (s.armed || autoTakeoff) && (!grounded || autoTakeoff);
        if (!altitudeControl) {
            s.altitudeAdjusting = false;
            if (s.collectiveInput != 0) s.targetAltitude = altitude;
            // 自转储能与偏航扭矩必须读取本 tick 的手动总距，保留原来的积分顺序。
            s.collective = clamp(s.collective+s.collectiveInput*COLLECTIVE_STEP,0,1);
        }
        // 动力与转速：启动必须达到额定转速；此后引擎损伤降低功率而不是绕过起飞门槛。
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
        // 姿态与偏航：地面接触约束先清周期变距，空中再按配平/巡航目标平滑趋近；
        // 偏航率由尾桨损伤扭矩和无动力自旋驱动，脚蹬只提供手动抗扭。
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
        // 手动总距仍在地面、关闭辅助或失去动力时直接增减；高度保持下改为指定升降速度，
        // 使持续下降无需与旧高度目标对抗。前馈补偿倾斜、天气和损伤，不累积积分项。
        if (altitudeControl) {
            double targetVy;
            if (s.collectiveInput != 0) {
                s.altitudeAdjusting = true;
                s.targetAltitude = altitude;
                targetVy = s.collectiveInput * MAX_ASSISTED_VERTICAL_SPEED;
            } else if (s.altitudeAdjusting && !s.autopilot && s.autoPhase == 0 && s.abandonedTicks == 0) {
                // 松键先消除当前垂速，再锁住新高度；制动期间不向刚刚经过的高度回拉。
                s.targetAltitude = altitude;
                targetVy = 0;
                if (Math.abs(s.vy) < ALTITUDE_CAPTURE_SPEED) s.altitudeAdjusting = false;
            } else {
                s.altitudeAdjusting = false;
                targetVy = clamp((s.targetAltitude-altitude)*ALTITUDE_GAIN,
                        -MAX_ASSISTED_VERTICAL_SPEED, MAX_ASSISTED_VERTICAL_SPEED);
            }
            double acceleration = clamp((targetVy-s.vy)*VERTICAL_RESPONSE,
                    -MAX_VERTICAL_ACCELERATION, MAX_VERTICAL_ACCELERATION);
            // 地面自动起飞按额定转速预置总距，避免预转初期因 rpm² 接近零把总距拉满。
            double controlRpm = autoTakeoff ? 1 : s.rpm;
            double authority = .072*controlRpm*controlRpm*rotorPower*enginePower*Math.cos(pitch)*Math.cos(roll);
            double target = clamp((GRAVITY+s.vy*(1/.985-1)+acceleration)/Math.max(.001,authority), 0, 1);
            s.collective += clamp((target-s.collective)*COLLECTIVE_RESPONSE,-COLLECTIVE_STEP,COLLECTIVE_STEP);
        }
        // 总距与升力：未完成预转禁止离地；已起飞后允许残余转速和自转产生升力，
        // 升力按机体俯仰/横滚投影到水平方向，垂直分量再与重力比较。
        double lift = s.armed ? .072*s.collective*s.rpm*s.rpm*rotorPower*enginePower : 0;
        double forward = -Math.sin(pitch)*lift, lateral = -Math.sin(roll)*lift;
        s.vx += -Math.sin(yaw)*forward + Math.cos(yaw)*lateral;
        s.vz += Math.cos(yaw)*forward + Math.sin(yaw)*lateral;
        s.vy += lift*Math.cos(pitch)*Math.cos(roll)-GRAVITY;
        // 无动力的前飞气流仍产生升力，支持有速度的下滑和接地前拉平。
        if (!powered && s.armed && !grounded)
            s.vy += Math.min(.028, Math.max(0,-s.vy)*.042*s.rpm*s.rotor/100);
        // 阻力与风：起落架只在空中增加水平阻力；风扰动仅在空中生效，雨天阵风更强。
        double drag = horizontalDrag(c.hardcore(), !grounded && s.gear);
        s.vx *= drag; s.vz *= drag; s.vy *= .985;
        if (c.wind && !grounded) {
            s.vx += windX(rain,tick); s.vz += windZ(rain,tick);
        }
        if (s.rotor < 65 && s.rpm > .3) {
            double shake = (1-s.rotor/100)*.0025*s.rpm;
            s.vx += Math.sin(tick*2.3)*shake; s.vz += Math.cos(tick*1.7)*shake;
        }
        // 限幅与油耗：先限制三轴速度，再在地面施加额外水平摩擦；
        // 油耗与机动量、速度、引擎健康度和难度相关，油箱低于 80% 时持续泄漏。
        s.vx=clamp(s.vx,-2.2,2.2); s.vz=clamp(s.vz,-2.2,2.2); s.vy=clamp(s.vy,-2.5,1.1);
        if (grounded) { s.vx*=.7; s.vz*=.7; }
        double maneuver = Math.abs(s.cyclicPitch)+Math.abs(s.cyclicRoll)+Math.abs(s.collectiveInput)*.5;
        double consumption = powered ? .016*(1+speed*2+maneuver*3)*(2-s.engine/100)*(c.hardcore()?1.7:1) : 0;
        double leak = s.tank < 80 ? (80-s.tank)*.0015 : 0;
        s.fuel=Math.max(0,s.fuel-consumption-leak);
    }

    /** 功能：读取当前东西向阵风加速度，格/tick²；纯计算，物理与自动起降补偿共用，不改变天气强度。 */
    public static double windX(boolean rain,long tick) { return Math.sin(tick*.013)*(rain?.0015:.0005); }

    /** 功能：读取当前南北向阵风加速度，格/tick²；纯计算，是否启用风及是否接地由调用方判断。 */
    public static double windZ(boolean rain,long tick) { return Math.cos(tick*.009)*(rain?.0015:.0005); }
    /** 跳机后先保持三秒高度，再丢失动力并持续倾斜；驾驶员重新登机可中断失控过程。 */
    public static void handleAbandonment(FlightState s, HeliConfig c, boolean grounded, boolean hasPilot, double altitude) {
        if(c.unassisted()){
            // 极限模式不提供弃机后的三秒自动悬停；有人驾驶时仍完全保留手动控制。
            if(!hasPilot&&!grounded&&s.armed){s.clearInputs();s.running=false;s.collective=approach(s.collective,0,.006);}
            return;
        }
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
    /** 接地前检查合成倾斜；纯数值、无 MC API，必须在地面配平或弃机状态清零之前调用。 */
    public static boolean unsafeLanding(FlightState s) {
        return Math.cos(Math.toRadians(s.pitch)) * Math.cos(Math.toRadians(s.roll))
                < Math.cos(Math.toRadians(MAX_LANDING_TILT));
    }
    /** 撞击判据只使用实际被碰撞阻挡的速度，适用于任意方向，避免平行擦边被误判为全速撞击。 */
    public static boolean catastrophic(double impact, FlightState s, HeliConfig c) {
        double limit = c.hardcore() ? .66 : .9;
        if (!s.gear) limit *= .75;
        if (Math.abs(s.pitch)>30 || Math.abs(s.roll)>30) limit *= .7;
        return impact > limit;
    }
}
