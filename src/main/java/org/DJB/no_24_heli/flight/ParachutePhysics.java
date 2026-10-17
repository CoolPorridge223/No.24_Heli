/** 文件：滑翔伞的充气、阻力、升力与惯性积分；端侧：COMMON，服务端调用。 */
package org.DJB.no_24_heli.flight;

/**
 * 职责：使用简化滑翔极曲线模拟载人冲压翼，不依赖世界或客户端 API。
 * 生命周期：ParachuteEntity 在 20 Hz 调用；四次子步限制开伞冲击的积分误差。
 * 端侧/线程：逻辑服务端主线程；速度内部使用 m/s，对外使用格/tick。
 * 排查：下降异常查充气系数和极曲线，瞬间变向查制动/转向阻尼；本模型不是流体求解器。
 */
public final class ParachutePhysics {
    /** 20 tick/s；开伞缩至 1.2 秒，前两 tick 拉出伞绳，提前建立减速面积。 */
    public static final int TICKS_PER_SECOND = 20, OPEN_TICKS = 24, LINE_TICKS = 2;
    /** 重力 m/s²、单步秒数、四个积分子步；不更改原版自由落体。 */
    private static final double GRAVITY = 9.81, DT = .05 / 4;
    /** 操纵滤波时间常数为 0.7 秒，角速度时间常数为 0.9 秒，松键仍保留惯性。 */
    private static final double INPUT_TIME = .7, TURN_TIME = .9;
    /** 开伞合气动力上限 m/s²，提高到 52 以缩短减速距离，仍通过子步积分而非直接清零速度。 */
    private static final double MAX_AIR_ACCELERATION = 52;
    /** 超过配平速度时的制动响应（s^-1）和纵向减速上限（m/s²），不增强低速前进推力。 */
    private static final double BRAKE_RESPONSE = 2.4, MAX_BRAKE = 24;
    /** 前后倾最大 14 度；W 为正，沿本地 +Z 的前缘向下，S 相反。 */
    public static final double MAX_PITCH = 14;

    /** 无实例；数值状态归各自伞实体所有，禁止多个玩家共用。 */
    private ParachutePhysics() {}

    /** 每具伞独立的速度（格/tick）、朝向/角速度（度、度/秒）和经过阻尼的操纵量。 */
    public static final class State {
        /** 世界速度，不直接设成目标速度，保持开伞前惯性。 */
        public double vx, vy, vz;
        /** 航向与带阻尼转向，W/S、A/D 滤波值均在 -1..1。 */
        public double yaw, yawRate, forward, turn, bank, pitch;
    }

    /** 平滑充气比例；伞绳拉直后面积按三次曲线增长，展开动画和受力使用同一时间轴。 */
    public static double inflation(double ticks) {
        double t = Math.clamp((ticks - LINE_TICKS) / (OPEN_TICKS - LINE_TICKS), 0, 1);
        return t * t * (3 - 2 * t);
    }

    /**
     * 积分有效面积上的气动力：垂直二次阻力形成终端下降速度，翼面把下降气流转为前进。
     * 滑翔极曲线在中性约 6.8 m/s 前进、2.6 m/s 下沉；强制动进入高阻低速/后退段。
     * 横向气流强阻尼、转弯额外掉高，输入不能产生向上推力或无能量悬停。
     * 无 MC API；输入仅服务端已校验意图，windX/Z 单位 m/s；副作用仅更新当前 State。
     */
    public static void step(State s, float forwardInput, float turnInput, double opening,
                            double windX, double windZ, double effectiveness) {
        double x = s.vx * TICKS_PER_SECOND, y = s.vy * TICKS_PER_SECOND;
        double z = s.vz * TICKS_PER_SECOND;
        for (int substep = 0; substep < 4; substep++) {
            s.forward += (forwardInput - s.forward) * (1 - Math.exp(-DT / INPUT_TIME));
            s.turn += (turnInput - s.turn) * (1 - Math.exp(-DT / INPUT_TIME));
            double desiredRate = s.turn * 38 * opening;
            s.yawRate += (desiredRate - s.yawRate) * (1 - Math.exp(-DT / TURN_TIME));
            s.yaw += s.yawRate * DT;
            s.bank += (s.turn * 28 * opening - s.bank) * (1 - Math.exp(-DT / INPUT_TIME));
            s.pitch += (s.forward * MAX_PITCH * opening - s.pitch) * (1 - Math.exp(-DT / INPUT_TIME));
            double yaw = Math.toRadians(s.yaw), fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double rx = Math.cos(yaw), rz = Math.sin(yaw);
            double along = (x - windX) * fx + (z - windZ) * fz;
            double sideways = (x - windX) * rx + (z - windZ) * rz;
            // 后退来自深制动的失速段，下降显著增大，不能像动力飞机一样倒飞或爬升。
            double trimSpeed = s.forward >= 0 ? 6.8 + 2.2 * s.forward : 6.8 + 8.4 * s.forward;
            double sink = 2.6 + .055 * Math.pow(along - 6.8, 2)
                    + 1.2 * Math.max(0, -s.forward) + .7 * Math.abs(s.turn);
            double down = Math.max(0, -y);
            double liftDrag = Math.min(MAX_AIR_ACCELERATION, GRAVITY * down * down / (sink * sink));
            double flow = Math.clamp(down / sink, 0, 1.4);
            double error = trimSpeed - along;
            boolean braking = Math.abs(along) > Math.abs(trimSpeed) || along * trimSpeed < 0;
            double response = braking ? BRAKE_RESPONSE : 1.1;
            double limit = braking ? MAX_BRAKE : 10;
            double drive = Math.clamp(error * response * flow, -limit, limit) * opening;
            double lateralDrag = -sideways * (1.8 + .08 * Math.abs(sideways)) * opening;
            double ax = fx * drive + rx * lateralDrag, az = fz * drive + rz * lateralDrag;
            double airY = (y < 0 ? liftDrag : -Math.min(MAX_AIR_ACCELERATION, y * 3)) * opening;
            // 限制合气动力的开伞冲击；重力始终存在，面积为零时仍处在自由落体。
            double length = Math.sqrt(ax * ax + airY * airY + az * az);
            double scale = length > MAX_AIR_ACCELERATION ? MAX_AIR_ACCELERATION / length : 1;
            x += ax * scale * effectiveness * DT; z += az * scale * effectiveness * DT;
            y += (airY * scale * effectiveness - GRAVITY) * DT;
        }
        s.vx = x / TICKS_PER_SECOND; s.vy = y / TICKS_PER_SECOND; s.vz = z / TICKS_PER_SECOND;
    }
}
