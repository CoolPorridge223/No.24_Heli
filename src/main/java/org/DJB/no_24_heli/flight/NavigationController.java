package org.DJB.no_24_heli.flight;

/**
 * 文件：自动驾驶速度、偏航和侧倾反馈及虚拟杆量适配，COMMON，无注册项、无 MC API。
 * 生命周期：每机实例由 NavigationPilot 每服务端 tick 调用；不影响手动与一键起降控制器。
 * 排查：速度不足查阻力前馈和动力；侧倾方向查机体系转换；抖动查中性包及输入变化率。
 */
public final class NavigationController {
    /** 安全射线制动加速度 0.008 格/tick²、响应时间12 tick；轨迹表另按半值提前减速。 */
    public static final double BRAKE_ACCELERATION = .008, BRAKE_RESPONSE = 12;
    /** 目标加速度上限 0.004、减速度上限 0.008，单位格/tick²。 */
    private static final double ACCELERATION = .004, DECELERATION = .008;
    /** 杆量每 tick 最大变化；姿态指令最大俯仰 30 度、侧倾 25 度。 */
    private static final double INPUT_STEP = .025, PITCH_LIMIT = 30, BANK_LIMIT = 25;
    /** 速度闭环增益和位置末端增益；按原 20Hz 积分器设计，不使用渲染帧时钟。 */
    private static final double VELOCITY_GAIN = .10, ARRIVAL_GAIN = .025;
    /** 当前连续目标速度和上次虚拟杆量；不会因网络中性包或状态切换而归零。 */
    private double commandedSpeed, pitchInput, rollInput, pedalInput;

    /** 功能：取消时释放控制器内部记忆；服务端，无 MC API，不修改实际运动。 */
    public void reset() { commandedSpeed = pitchInput = rollInput = pedalInput = 0; }

    /**
     * 功能：将轨迹目标和限速映射到原 FlightPhysics 的脚蹬及侧倾/俯仰输入。
     * 引用 MC API：无。端侧/线程：服务端固定物理 tick；坐标单位格，速度单位格/tick。
     * 返回：无；只修改输入及原辅助标志，不写坐标、实际速度或姿态。
     * 故障排查：Q/E 在本项目是偏航，侧倾用 cyclicRoll；低 TPS 不补跑额外物理循环。
     */
    public void step(FlightState s, double x, double z, NavigationRoute.Point target,
                     double limit, double remaining, double curvature) {
        double dx = target.x() - x, dz = target.z() - z, distance = Math.hypot(dx, dz);
        double heading = Math.toDegrees(Math.atan2(-dx, dz));
        double error = wrap(heading - s.yaw);
        boolean arriving = remaining < 3;
        if (limit > 0 && !s.navAligned && Math.abs(error) < 8) s.navAligned = true;
        double alignment = arriving ? 1 : Math.max(.2, Math.cos(Math.toRadians(error)));
        double desired = s.navAligned || arriving ? Math.min(limit, remaining * ARRIVAL_GAIN) * alignment : 0;
        commandedSpeed += Math.clamp(desired - commandedSpeed, -DECELERATION, ACCELERATION);
        if (limit == 0) commandedSpeed = 0; // 安全制动只改变目标，不抹掉飞机惯性。
        double vx = distance < .01 ? 0 : dx / distance * commandedSpeed;
        double vz = distance < .01 ? 0 : dz / distance * commandedSpeed;
        // 逆算原积分器的空气阻力，保证速度误差为零时仍能维持高速平飞。
        double drag = s.hardcoreMode ? .994 : .981;
        double ax = (vx - s.vx) * VELOCITY_GAIN + s.vx * (1 / drag - 1);
        double az = (vz - s.vz) * VELOCITY_GAIN + s.vz * (1 / drag - 1);
        double yaw = Math.toRadians(s.yaw);
        double forward = -Math.sin(yaw) * ax + Math.cos(yaw) * az;
        double lateral = Math.cos(yaw) * ax + Math.sin(yaw) * az;
        double bank = Math.clamp(-Math.toDegrees(Math.atan2(lateral, FlightPhysics.GRAVITY)), -BANK_LIMIT, BANK_LIMIT);
        double pitch = Math.clamp(-Math.toDegrees(Math.atan2(forward * Math.cos(Math.toRadians(bank)),
                FlightPhysics.GRAVITY)), -PITCH_LIMIT, PITCH_LIMIT);
        // 原物理以姿态目标驱动俯仰/侧倾，按反馈限制目标与实际姿态的距离，避免持续翻滚。
        pitchInput = approach(pitchInput, Math.clamp(pitch, s.pitch - 8, s.pitch + 8) / 30, INPUT_STEP);
        rollInput = approach(rollInput, Math.clamp(bank, s.roll - 8, s.roll + 8) / 32, INPUT_STEP);
        double turn = arriving || limit == 0 ? 0 : Math.clamp(error * .055
                + Math.toDegrees(curvature * Math.hypot(s.vx, s.vz)), -1.4, 1.4);
        if (Math.abs(error) < .2 && Math.abs(curvature) < 1e-5) turn = 0;
        // .29 为原 0.94 偏航阻尼和 .22 脚蹬增益的稳态前馈，误差项提供角速度阻尼。
        pedalInput = approach(pedalInput, Math.clamp((turn - s.yawRate) * .7 + turn * .29, -1, 1), .08);
        s.cyclicPitch = (float) pitchInput; s.cyclicRoll = (float) rollInput; s.pedal = (float) pedalInput;
        s.collectiveInput = 0; s.trim = true; s.altitudeHold = true; s.cruise = false;
    }

    /** 功能：取得连续速度目标用于诊断状态；无 MC API，无副作用。 */
    public double commandedSpeed() { return commandedSpeed; }
    /** 功能：限幅角差到 [-180,180)，避免跨北向时错误长转；无副作用。 */
    private static double wrap(double angle) { return ((angle + 180) % 360 + 360) % 360 - 180; }
    /** 功能：限制控制信号单 tick 变化；无 MC API，无副作用。 */
    private static double approach(double from, double to, double step) { return from + Math.clamp(to - from, -step, step); }
}
