/** 文件：直升机同步和持久化状态；端侧：COMMON，服务端权威；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.flight;

/**
 * 不依赖客户端的飞行状态；速度单位为格/tick，姿态单位为度。
 * 该对象同时承担服务端权威计算与 DataTracker/存档快照载体，新增字段必须明确是否同步、是否持久化。
 * 端侧：COMMON，服务端写入；客户端只读快照，不反向写回权威状态。
 */
public final class FlightState {
    /** 旋翼转速与总距均为 0..1；姿态/偏航率单位为度、度/tick；速度单位为格/tick。 */
    public double rpm, collective, pitch, roll, yaw, yawRate, vx, vy, vz;
    /** 燃油与世界可消耗量，以及主旋翼/尾桨/引擎/油箱/机身耐久，正常范围 0..100；燃油上限见 HeliConfig。 */
    public double fuel, rotor = 100, tail = 100, engine = 100, tank = 100, hull = 100;
    /** 高度保持目标世界 Y、巡航目标水平速度；两者都只是控制目标，不代表实际位置或速度。 */
    public double targetAltitude, targetSpeed;
    /** 高度保持下正在手动升降或松键制动；仅服务端瞬态，不同步、不存档，制动后捕获新高度。 */
    public boolean altitudeAdjusting;
    /** 引擎运行、旋翼已武装、高度保持、配平、巡航；起落架默认放下，残骸不参与飞行控制。 */
    public boolean running, armed, altitudeHold, trim, cruise, gear = true;
    /** 残骸标记和绳索部署开关；残骸复用原实体保存，不能按普通载具删除。 */
    public boolean wreck, rope;
    /** 起飞升力不足警告；服务端按部件、旋翼处天气和地面支撑实时重算，随快照同步，旧数据默认关闭。 */
    public boolean takeoffPowerLow;
    /** 探照灯开关和相对机头的云台角，单位：度；左右 ±120°，俯仰 -85°～-15°，默认朝前下方。 */
    public boolean searchlight;
    public float searchlightYaw,searchlightPitch=-65;
    /** 导航点随飞机保存；自动驾驶属于临时接管状态，重载后须驾驶员重新开启。 */
    public boolean waypoint, autopilot, restricted;
    public double waypointX, waypointZ;
    /** 自动驾驶接管前的辅助状态，手动退出时恢复，避免改变玩家原有选择。 */
    public boolean priorTrim, priorAltitude, priorCruise;
    /** 已应用的服务器难度，供 HUD 与跨区块加载时比较。 */
    public boolean hardcoreMode;
    /** 默认蓝白警用机的外观编号；0–5 继续对应既有六种涂装，避免旧存档丛林色被替换。 */
    public static final int POLICE_PAINT = 6;
    /** 同步并持久化的外观编号，0–6；新制造的飞机使用参考图蓝白配色。 */
    public int paint = POLICE_PAINT;
    /** 0=休闲，1=曼巴，2=佐巴扬。随快照同步，客户端不得用本地配置推测服务器难度。 */
    public int difficultyMode;
    /** 一键起降阶段：0关闭、1预转、2爬升、3降落；重载不恢复自动动作，防止无人起飞。 */
    public int autoPhase;
    /** 自动降落平地或通道失效时暂停；随快照同步，取消任务或重载后清除，不覆盖原降落目标。 */
    public boolean landingBlocked;
    /** 一键起降锚点，单位格；autoY 是开始降落时确定的最终目标，降落暂停不能覆盖它。 */
    public double autoX,autoZ,autoY;
    /** 电台只同步槽位和切歌序号；-1为关闭，0–7对应用户自己提供的八首MP3。 */
    public int radioTrack=-1,radioRevision;
    /** 切歌发生时的世界 tick，客户端据此从当前进度接入而不是从头播放。 */
    public long radioStartedTick;
    /** 无驾驶员的连续飞行 tick，保存以避免重载反复刷新悬停保护。 */
    public int abandonedTicks;
    /** 舱内联动摇杆的显示输入，范围 -1～1；独立于接地时被物理清零的周期变距，随实体快照同步。 */
    public float stickPitch, stickRoll;
    /** 参与服务端物理积分的连续轴输入，范围 -1～1；接地时周期变距归零。 */
    public float cyclicPitch, cyclicRoll, pedal, collectiveInput;
    /** 功能：服务端在超时、离座和读档时清除输入与摇杆显示，不抹掉惯性或旋翼转速；不依赖 MC API。 */
    public void clearInputs() { stickPitch = stickRoll = cyclicPitch = cyclicRoll = pedal = collectiveInput = 0; }
    /** 功能：结束自动起降并清除占用警告；无 MC API，服务端各接管/终止入口共用，不改变速度或高度目标。 */
    public void cancelAutoFlight() { autoPhase = 0; landingBlocked = false; }
}
