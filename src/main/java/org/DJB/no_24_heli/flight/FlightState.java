package org.DJB.no_24_heli.flight;

/** 不依赖客户端的飞行状态；速度单位为格/tick，姿态单位为度。 */
public final class FlightState {
    public double rpm, collective, pitch, roll, yaw, yawRate, vx, vy, vz;
    public double fuel, rotor = 100, tail = 100, engine = 100, tank = 100, hull = 100;
    public double targetAltitude, targetSpeed;
    public boolean running, armed, altitudeHold, trim, cruise, gear = true;
    public boolean wreck, rope;
    /** 已应用的服务器难度，供 HUD 与跨区块加载时比较。 */
    public boolean hardcoreMode;
    public int paint;
    /** 无驾驶员的连续飞行 tick，保存以避免重载反复刷新悬停保护。 */
    public int abandonedTicks;
    /** 清除控制输入只影响操纵杆，不会抹掉惯性和旋翼转速。 */
    public float cyclicPitch, cyclicRoll, pedal, collectiveInput;
    public void clearInputs() { cyclicPitch = cyclicRoll = pedal = collectiveInput = 0; }
}
