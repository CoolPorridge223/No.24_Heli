package org.DJB.no_24_heli.flight;

/** 不依赖客户端的飞行状态；速度单位为格/tick，姿态单位为度。 */
public final class FlightState {
    public double rpm, collective, pitch, roll, yaw, yawRate, vx, vy, vz;
    public double fuel, rotor = 100, tail = 100, engine = 100, tank = 100, hull = 100;
    public double targetAltitude, targetSpeed;
    public boolean running, armed, altitudeHold, trim, cruise, gear = true;
    public boolean wreck, rope;
    /** 导航点随飞机保存；自动驾驶属于临时接管状态，重载后须驾驶员重新开启。 */
    public boolean waypoint, autopilot, restricted;
    public double waypointX, waypointZ;
    /** 自动驾驶接管前的辅助状态，手动退出时恢复，避免改变玩家原有选择。 */
    public boolean priorTrim, priorAltitude, priorCruise;
    /** 已应用的服务器难度，供 HUD 与跨区块加载时比较。 */
    public boolean hardcoreMode;
    public int paint;
    /** 0=休闲，1=曼巴，2=佐巴扬。随快照同步，客户端不得用本地配置推测服务器难度。 */
    public int difficultyMode;
    /** 一键起降阶段：0关闭、1预转、2爬升、3降落；重载不恢复自动动作，防止无人起飞。 */
    public int autoPhase;
    public double autoX,autoZ,autoY;
    /** 导航先在当前位置制动并对正航向；对正后才开始前飞，近目标处不追逐抖动航向。 */
    public boolean navAligned;
    /** 电台只同步槽位和切歌序号；-1为关闭，0–7对应用户自己提供的八首MP3。 */
    public int radioTrack=-1,radioRevision;
    public long radioStartedTick;
    /** 无驾驶员的连续飞行 tick，保存以避免重载反复刷新悬停保护。 */
    public int abandonedTicks;
    /** 清除控制输入只影响操纵杆，不会抹掉惯性和旋翼转速。 */
    public float cyclicPitch, cyclicRoll, pedal, collectiveInput;
    public void clearInputs() { cyclicPitch = cyclicRoll = pedal = collectiveInput = 0; }
}
