/** 文件：未开伞趴姿飞行的重力、空气阻力和平移积分；端侧：COMMON，仅服务端调用。 */
package org.DJB.no_24_heli.flight;

/**
 * 职责：把鼠标航向与 WASD 平移分开，仅向前收拢时减小空气阻力，下降不超过原版自由落体。
 * 引用 MC API：无；速度单位格/tick，角度单位度，20 Hz 单步积分。
 * 生命周期：ParachuteEntity 的 FREEFALL 阶段每 tick 调用，开伞后改用 ParachutePhysics。
 * 排查：下落过快查姿态阻力与终端速度；无 W 俯冲查收拢条件；斜向过快查输入归一化。
 */
public final class SkydivingPhysics {
    /** 保留原版每 tick 0.08 格/tick² 重力；前进俯冲只减阻，不叠加重力。 */
    public static final double GRAVITY = .08;
    /** 竖直速度保留比例：张开 0.92、向前平移 0.94、向前垂直俯冲至多原版的 0.98。 */
    private static final double SPREAD_RETENTION = .92, FORWARD_RETENTION = .94, FREEFALL_RETENTION = .98;
    /** 原版 (vy-0.08)*0.98 的终端下降速度约 3.92 格/tick，所有姿态及继承速度都受此上限约束。 */
    public static final double MAX_SINK = GRAVITY * FREEFALL_RETENTION / (1 - FREEFALL_RETENTION);
    /** 水平平移约 17 m/s，每 tick 最大操纵加速度 0.06 格/tick²，松键阻尼只作用于水平。 */
    private static final double MOVE_SPEED = .85, MOVE_ACCELERATION = .06, COAST_DRAG = .98;
    /** 航向每 tick 最多跟随 18°；骨骼约 0.25 秒收敛，方向倾斜最大 18°/25°。 */
    private static final double YAW_STEP = 18, POSE_RESPONSE = .22, FORE_TILT = 18, SIDE_TILT = 25;
    /** 从跳机动作过渡至趴姿使用 12 tick，时间戳由实体同步，不随重追踪重新播放。 */
    public static final int ENTRY_TICKS = 12;

    /** 每名跳伞者独立的权威速度、航向和姿态，不与其他玩家共享。 */
    public static final class State {
        /** 世界速度，保留离机惯性，开伞时原样交给滑翔伞积分。 */
        public double vx, vy, vz, yaw;
        /** 平滑后的趴姿俯仰、左右倾斜和收拢比例；tuck 为 0 张开、1 双腿并拢且手贴身体。 */
        public double pitch = 90, bank, tuck;
    }

    /** 纯数值工具，无世界状态。 */
    private SkydivingPhysics() {}

    /**
     * 功能：积分鼠标航向、姿态空气阻力、相对航向的水平平移，并平滑身体倾斜和四肢展开比例。
     * 参数 forward/strafe 为 -1..1，lookPitch 为 -90..90（低头为正），lookYaw 为有限角度。
     * 无 MC API；逻辑服务端调用；副作用仅修改当前 State；无输入和斜向输入均张开四肢。
     */
    public static void step(State s, float forward, float strafe, float lookYaw, float lookPitch) {
        double yawError = (lookYaw - s.yaw) % 360;
        if (yawError >= 180) yawError -= 360;
        if (yawError < -180) yawError += 360;
        s.yaw += Math.clamp(yawError, -YAW_STEP, YAW_STEP);
        double tuck = forward > 0 && Math.abs(strafe) < .01 ? 1 : 0;
        double down = Math.sin(Math.toRadians(Math.max(0, lookPitch)));
        // 只有 W 纯前进才减阻；水平至抬头使用相同阻力，低头逐渐接近原版自由落体。
        double retention = SPREAD_RETENTION + tuck * forward * (FORWARD_RETENTION - SPREAD_RETENTION
                + (FREEFALL_RETENTION - FORWARD_RETENTION) * down);
        s.vy = Math.max(-MAX_SINK, (s.vy - GRAVITY) * retention);
        double length = Math.hypot(forward, strafe), scale = Math.max(1, length);
        double yaw = Math.toRadians(s.yaw), sin = Math.sin(yaw), cos = Math.cos(yaw);
        if (length > 0) {
            double x = (-sin * forward - cos * strafe) * MOVE_SPEED / scale;
            double z = (cos * forward - sin * strafe) * MOVE_SPEED / scale;
            double dx = x - s.vx, dz = z - s.vz;
            double response = Math.min(1, MOVE_ACCELERATION / Math.max(MOVE_ACCELERATION, Math.hypot(dx, dz)));
            s.vx += dx * response; s.vz += dz * response;
        } else { s.vx *= COAST_DRAG; s.vz *= COAST_DRAG; }
        double pitch = Math.clamp(90 + Math.max(0, lookPitch) + forward * FORE_TILT, 65, 175);
        s.pitch += (pitch - s.pitch) * POSE_RESPONSE;
        s.bank += (strafe * SIDE_TILT - s.bank) * POSE_RESPONSE;
        s.tuck += (tuck - s.tuck) * POSE_RESPONSE;
    }
}
