package org.DJB.no_24_heli.flight;

/**
 * 文件：自动驾驶速度、偏航和侧倾反馈及虚拟杆量适配，COMMON，无注册项、无 MC API。
 * 生命周期：每机实例由 NavigationPilot 每服务端 tick 调用；不影响手动与一键起降控制器。
 * 排查：速度不足查阻力前馈和动力；侧倾方向查机体系转换；抖动查中性包及输入变化率。
 */
public final class NavigationController {
    /** 安全射线制动加速度 0.008 格/tick²、响应时间12 tick；到点闭环另按半值提前减速。 */
    public static final double BRAKE_ACCELERATION = .008, BRAKE_RESPONSE = 12;
    /** 曲率限速的横向加速度与偏航能力；仅自动导航使用，单位格/tick²、弧度/tick。 */
    static final double LATERAL_ACCELERATION=.008,YAW_RATE=Math.toRadians(1.4);
    /** 原高度保持闭环增益；将前视高度转换为有界垂速目标，不另加每 tick 爬升积分限速。 */
    private static final double ALTITUDE_GAIN=.025;
    /** 目标加速度上限 0.004、减速度上限 0.008，单位格/tick²。 */
    private static final double ACCELERATION = .004, DECELERATION = .008;
    /** 杆量每 tick 最大变化；姿态指令最大俯仰 30 度、侧倾 25 度。 */
    private static final double INPUT_STEP = .025, PITCH_LIMIT = 30, BANK_LIMIT = 25;
    /** 速度闭环增益；按原 20Hz 积分器设计，不使用渲染帧时钟。 */
    private static final double VELOCITY_GAIN = .10;
    /** 到点按半值制动能力提前减速，留出二十四 tick 的闭环及姿态响应；近点自然形成位置反馈。 */
    private static final double ARRIVAL_BRAKE = .004, ARRIVAL_RESPONSE = 24;
    /** 水平目标进入一格内保持航向，离开两格才恢复转向，避免升降段与近点漂移导致反复掉头。 */
    private static final double HEADING_HOLD_DISTANCE = 1, HEADING_RESUME_DISTANCE = 2;
    /** 近前视点按位置误差限速，单位 tick⁻¹；缩短前视避障时不能仍按远处终点速度冲过目标。 */
    private static final double TARGET_POSITION_GAIN = .10;
    /** 当前连续目标速度和上次虚拟杆量；不会因网络中性包或状态切换而归零。 */
    private double commandedSpeed, pitchInput, rollInput, pedalInput;
    /** 仅本机控制器保存的近点航向滞回标志，不改变实际偏航或网络状态。 */
    private boolean holdingHeading;
    /** 本 tick 的实际风扰动，已由服务端按天气采样；不存档、不修改物理风强度。 */
    private double windX,windZ;

    /** 功能：取消时释放控制器内部记忆；服务端，无 MC API，不修改实际运动。 */
    public void reset() { commandedSpeed = pitchInput = rollInput = pedalInput = windX = windZ = 0; holdingHeading = false; }

    /** 功能：保存本 tick 的风加速度供导航和等待悬停共同抵消；无 MC API，主线程调用。 */
    void wind(double x,double z) { windX=x;windZ=z; }

    /** 功能：给原高度闭环提供有界提前量，连续爬升上限 0.14、下降 0.12 格/tick；不直接改速度。 */
    static double altitudeTarget(double y,double desired) {
        return y+Math.clamp(desired-y,-NavigationTerrainRoute.DESCENT_SPEED/ALTITUDE_GAIN,
                NavigationTerrainRoute.CLIMB_SPEED/ALTITUDE_GAIN);
    }

    /**
     * 功能：将轨迹目标和限速映射到原 FlightPhysics 的脚蹬及侧倾/俯仰输入。
     * 引用 MC API：无。端侧/线程：服务端固定物理 tick；坐标单位格，速度单位格/tick。
     * 返回：无；只修改输入及原辅助标志，不写坐标、实际速度或姿态。
     * 故障排查：Q/E 在本项目是偏航，侧倾用 cyclicRoll；低 TPS 不补跑额外物理循环。
     */
    public void step(FlightState s, double x, double z, NavigationRoute.Point target,
                     double limit, double remaining, double curvature) {
        double dx = target.x() - x, dz = target.z() - z, distance = Math.hypot(dx, dz);
        if (distance < HEADING_HOLD_DISTANCE) holdingHeading = true;
        else if (distance > HEADING_RESUME_DISTANCE) holdingHeading = false;
        double heading = Math.toDegrees(Math.atan2(-dx, dz));
        double error = wrap(heading - s.yaw);
        boolean arriving = remaining < 3;
        double alignment = arriving || holdingHeading ? 1 : Math.max(.2, Math.cos(Math.toRadians(error)));
        // 整机通道已经验证，可边低速平移边对准；不能把第一次进入八度航向窗作为永久起步门槛。
        double desired = Math.min(Math.min(limit, stoppingSpeed(remaining)), distance * TARGET_POSITION_GAIN) * alignment;
        commandedSpeed += Math.clamp(desired - commandedSpeed, -DECELERATION, ACCELERATION);
        if (limit == 0) commandedSpeed = 0; // 安全制动只改变目标，不抹掉飞机惯性。
        double vx = distance < .01 ? 0 : dx / distance * commandedSpeed;
        double vz = distance < .01 ? 0 : dz / distance * commandedSpeed;
        // 逆算原积分器的空气阻力，保证速度误差为零时仍能维持高速平飞。
        double drag = FlightPhysics.horizontalDrag(s.hardcoreMode, s.gear);
        double ax = (vx - s.vx) * VELOCITY_GAIN + s.vx * (1 / drag - 1)-windX/drag;
        double az = (vz - s.vz) * VELOCITY_GAIN + s.vz * (1 / drag - 1)-windZ/drag;
        // 曲率先馈入侧向加速度，转弯时不必等航迹偏离后才侧倾纠正；仍受原姿态限幅约束。
        double lateralFeed=Math.clamp(curvature*commandedSpeed*commandedSpeed,-LATERAL_ACCELERATION,LATERAL_ACCELERATION);
        if(distance>.01){ax-=dz/distance*lateralFeed;az+=dx/distance*lateralFeed;}
        double yaw = Math.toRadians(s.yaw);
        double forward = -Math.sin(yaw) * ax + Math.cos(yaw) * az;
        double lateral = Math.cos(yaw) * ax + Math.sin(yaw) * az;
        double bank = Math.clamp(-Math.toDegrees(Math.atan2(lateral, FlightPhysics.GRAVITY)), -BANK_LIMIT, BANK_LIMIT);
        double pitch = Math.clamp(-Math.toDegrees(Math.atan2(forward * Math.cos(Math.toRadians(bank)),
                FlightPhysics.GRAVITY)), -PITCH_LIMIT, PITCH_LIMIT);
        // 原物理以姿态目标驱动俯仰/侧倾，按反馈限制目标与实际姿态的距离，避免持续翻滚。
        pitchInput = approach(pitchInput, Math.clamp(pitch, s.pitch - 8, s.pitch + 8) / 30, INPUT_STEP);
        rollInput = approach(rollInput, Math.clamp(bank, s.roll - 8, s.roll + 8) / 32, INPUT_STEP);
        double turn = arriving || holdingHeading || limit == 0 ? 0 : Math.clamp(error * .055
                + Math.toDegrees(curvature * Math.hypot(s.vx, s.vz)), -1.4, 1.4);
        if (Math.abs(error) < .2 && Math.abs(curvature) < 1e-5) turn = 0;
        // .29 为原 0.94 偏航阻尼和 .22 脚蹬增益的稳态前馈，误差项提供角速度阻尼。
        pedalInput = approach(pedalInput, Math.clamp((turn - s.yawRate) * .7 + turn * .29, -1, 1), .08);
        s.cyclicPitch = (float) pitchInput; s.cyclicRoll = (float) rollInput; s.pedal = (float) pedalInput;
        s.collectiveInput = 0; s.trim = true; s.altitudeHold = true; s.cruise = false;
    }

    /** 功能：取得连续速度目标用于诊断状态；无 MC API，无副作用。 */
    public double commandedSpeed() { return commandedSpeed; }
    /**
     * 功能：按剩余水平路程倒推能停稳的速度，包含惯性制动和闭环反应时间；无 MC API，单位格/tick。
     * 服务端调用；前视目标不能代替最终停车点，已就绪续段的接缝不能作为零速目标。
     * 排查：高速越点查真实水平距离和制动余量，不能直接清零实际速度或扩大到点半径掩盖超调。
     */
    private static double stoppingSpeed(double remaining) {
        double response=ARRIVAL_BRAKE*ARRIVAL_RESPONSE;
        return Math.sqrt(response*response+2*ARRIVAL_BRAKE*Math.max(0,remaining))-response;
    }
    /** 功能：限幅角差到 [-180,180)，避免跨北向时错误长转；无副作用。 */
    private static double wrap(double angle) { return ((angle + 180) % 360 + 360) % 360 - 180; }
    /** 功能：限制控制信号单 tick 变化；无 MC API，无副作用。 */
    private static double approach(double from, double to, double step) { return from + Math.clamp(to - from, -step, step); }
}
