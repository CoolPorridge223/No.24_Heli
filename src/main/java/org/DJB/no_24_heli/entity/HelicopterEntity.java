/** 文件：直升机实体，负责注册属性、飞行物理编排、碰撞、交互、乘员与存档；端侧：COMMON；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.entity;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.block.HelipadBlock;
import org.DJB.no_24_heli.block.HelipadBlockEntity;
import org.DJB.no_24_heli.data.AirspaceState;
import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.data.HelipadState;
import org.DJB.no_24_heli.data.PlayerFlightSettings;
import org.DJB.no_24_heli.flight.AutoFlightController;
import org.DJB.no_24_heli.flight.FlightPhysics;
import org.DJB.no_24_heli.flight.FlightState;
import org.DJB.no_24_heli.flight.NavigationPilot;
import org.DJB.no_24_heli.network.AirspacePayload;
import org.DJB.no_24_heli.network.ControlPayload;
import org.DJB.no_24_heli.network.HelipadMapPayload;
import org.DJB.no_24_heli.network.NavigationPayload;
import org.DJB.no_24_heli.network.RadarContactsPayload;
import org.DJB.no_24_heli.screen.HeliScreenHandler;



import net.minecraft.entity.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.*;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.*;
import net.minecraft.item.*;
import net.minecraft.nbt.*;
import net.minecraft.screen.*;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.hit.*;
import net.minecraft.util.math.*;
import net.minecraft.world.*;
import java.util.List;

/**
 * 职责：服务端权威载具，负责注册属性、飞行物理编排、分部碰撞、交互、乘员、保存和客户端状态快照。
 * 引用：net.minecraft.entity.Entity 提供生命周期、move 与乘骑；
 *      DataTracker 同步 Flight NBT；HelicopterCabin/FlightPhysics/HelicopterCollision 分别处理座位、物理与几何。
 * 生命周期：由 EntityType 创建，世界主线程每 tick 调 tick()；只读写所属世界状态。
 * 端侧/线程：COMMON，逻辑服务端权威；客户端只做位置/姿态插值和读取快照，不写权威物理。
 * 排查：实体不出现查注册；不同步查 STATUS/DataTracker；抖动或穿墙查 tick 是否重复写速度、是否绕过 move；
 *      乘降异常查 HelicopterCabin；事故漏判查 blockImpact 记录和移动后的复查顺序。
 */
public class HelicopterEntity extends Entity {
    /** 双座整体朝机头前移 0.12 格；座椅、乘员、握杆手臂和第一人称眼位共用此偏移。 */
    public static final double SEAT_FORWARD_OFFSET=.12;
    /** 主旋翼毂相对机体原点的高度，单位格；天气采样不能落在低于滑橇的实体原点而被地面误遮挡。 */
    private static final double ROTOR_WEATHER_HEIGHT=2.71;
    /** F 与 Shift 共用空中离机确认，60 tick 即三秒；同 tick 的重复输入不算第二次按下。 */
    private static final int EXIT_CONFIRM_TICKS=60;
    /** DataTracker 中的 Flight NBT 快照；服务端每 2 tick 或离散乘降时刷新，客户端只读。 */
    private static final TrackedData<NbtCompound> STATUS=DataTracker.registerData(HelicopterEntity.class,TrackedDataHandlerRegistry.NBT_COMPOUND);
    /** 放置时与机体重叠的玩家 UUID；独立同步给客户端用于移动预测，不写入存档或回收物品。 */
    private static final TrackedData<NbtCompound> PLACEMENT_PLAYERS=DataTracker.registerData(HelicopterEntity.class,TrackedDataHandlerRegistry.NBT_COMPOUND);
    /** 离开机体额外保留五厘米净距后恢复碰撞，避免刚擦过盒面就再次卡住；单位格。 */
    private static final double PLACEMENT_EXIT_MARGIN=.05;
    /** 每架飞机独立的导航任务状态与后台 Future 生命周期，卸载/销毁时必须 reset。 */
    private final NavigationPilot navigation=new NavigationPilot();
    /** 权威飞行状态；所有物理、辅助、损伤和任务字段都在服务端写入。 */
    public final FlightState flight=new FlightState();
    /** 低血量燃烧和两个独立爆炸计时器；只在服务端结算，随 Flight 存档保存。 */
    private final HelicopterDamageEffects damageEffects=new HelicopterDamageEffects();
    /** 19 格货仓：0 号是燃料槽，其余为普通货物；随实体原版库存 NBT 保存。 */
    public final SimpleInventory cargo=new SimpleInventory(19);
    /** 桨叶角度单位为度；实体快照同步相位，使停转桨叶的选取与显示仍一致。 */
    public float rotorAngle,previousRotorAngle;
    /** 实体自身的只读几何快照，位置、姿态、收放或桨盘状态变化时失效；不持久化。 */
    volatile HelicopterCollision.Cache collisionCache;
    /** 客户端双层平滑后的可见姿态与起落架展开量；服务端物理只读不写这些值。 */
    public float visualPitch,visualRoll,gearExtension=1;
    /** 保存上一 tick 的第二级平滑结果，渲染帧再次插值，避免 20 Hz 阶梯跳变。 */
    public float previousVisualPitch,previousVisualRoll,previousGearExtension=1;
    /** 云台视觉角仅客户端平滑；单位：度，与机体同样做 tick/帧双级插值。 */
    public float visualLightYaw,previousLightYaw,visualLightPitch=-65,previousLightPitch=-65;
    /** 第一级姿态平滑缓存；视觉层再插值一次，避免服务端 20 Hz 步进直接进入模型。 */
    private float smoothPitch,smoothRoll;
    /** 空中离机二次确认窗口，值为允许再次确认的世界 tick；任何离座都会清除。 */
    private final java.util.Map<java.util.UUID,Long> exitWarnings=new java.util.HashMap<>();
    /** 刚离机的乘员保留两秒本机碰撞豁免，世界 tick 到期即移除；避免乘骑清零速度与出舱过渡造成自撞。 */
    private final java.util.Map<java.util.UUID,Long> departingPassengers=new java.util.HashMap<>();
    /** 离机碰撞豁免持续四十个服务端 tick，仅作用于离开的那一架飞机。 */
    private static final int DEPARTURE_GRACE_TICKS=40;
    /** 部署时确定的绳长，单位格；飞行中保持初始长度，不随每 tick 地面探测伸缩。 */
    public int ropeLength;
    /** 当前部署的唯一绳实体；运行时缓存跨越查询半径，卸载后按同一 UUID 等待恢复。 */
    private RopeSeatEntity activeRope;
    /** 客户端从 Flight 同步的权威绳实体 ID，仅用于查找与绘制，不跨存档恢复部署。 */
    private int ropeCarrierId=-1;
    /** 固定四座及四扇铰接门；权威状态随 Flight 快照同步，两端只共享乘降时序。 */
    public final HelicopterCabin cabin = new HelicopterCabin(this);
    /** 电台、连续轴、离散动作和引擎切换的最后处理 tick；分开限流，避免同 tick 的包互相吞掉。 */
    private long lastRadio=-100;
    private long lastInput=-100,lastActions=-100,lastEngine=-100;
    /** 灯具开关独立限流五 tick，瞄准每 tick 最多一次；不阻塞引擎或飞行辅助。 */
    private long lastLightToggle=-100,lastLightAim=-100;
    /** 普通碰撞伤害、维修、实体撞击的冷却 tick；高速事故检查和旋翼规则不受这些冷却豁免。 */
    private int collisionCooldown,repairCooldown,entityImpactCooldown;
    /** 本 tick 真实受阻部位与被阻挡速度，单位格/tick；由有向盒扫掠记录，逻辑服务端结算。 */
    private HelicopterCollision.Part blockImpactPart=HelicopterCollision.Part.CABIN;
    private double blockImpactSpeed;
    /** 主桨方块接触独立记录，避免被机身接触覆盖；高速旋转损坏不受普通耐久伤害冷却影响。 */
    private boolean mainRotorBlockImpact;
    /** 主桨本 tick 被方块阻挡的最大机体速度，格/tick；低转速接触按此与桨尖速度的较大值扣耐久。 */
    private double mainRotorBlockSpeed;
    /** 达到额定转速的百分之五十即视为高速旋转，主桨撞方块会一次扣光耐久。 */
    private static final double ROTOR_BREAK_RPM=.5;
    /** 低于 10 m/s 的实体机身碰撞不反伤本机；旋翼仍按接触强度磨损，高速机身撞击按事故阈值处理。 */
    public static final double ENTITY_DAMAGE_SPEED=.5;
    /** 机身完全损坏时，引擎/油箱受到的事件伤害最多放大到 1.25 倍；完整机身为 1 倍。 */
    private static final double MAX_COMPONENT_DAMAGE_MULTIPLIER=1.25;
    /** 连续离开方块支撑三 tick 后才启用实体事故，防止放置接地前误判。 */
    private int airborneTicks;
    /** 本 tick 是否启用实体撞击结算；离地三 tick 或旋翼已转起才为 true。 */
    private boolean entityContactsEnabled;
    /** 客户端位置插值目标与剩余 tick；由原版实体追踪包写入，不参与服务端物理。 */
    private double lerpX,lerpY,lerpZ;
    /** 客户端目标偏航，单位度；收到高精度 Flight.Yaw 后不再接受字节角覆盖。 */
    private float lerpYaw;
    private int lerpTicks;
    /** 高精度 Flight 姿态每两 tick 同步，偏航插值与原版位置包的低精度字节角分开。 */
    private static final int YAW_INTERPOLATION_TICKS=2;
    /** 当前偏航过渡剩余 tick，与位置插值独立；仅客户端维护，不同步或持久化。 */
    private int yawLerpTicks;
    /** 已收到本实体首次高精度偏航快照，首次到达前保留原版生成包朝向。 */
    private boolean preciseYaw;
    /** 由 EntityType 构造；intersectionChecked 允许自定义命中盒，难度只初始化辅助默认值。 */
    public HelicopterEntity(EntityType<?> type,World world){super(type,world);intersectionChecked=true;applyDifficulty(HeliConfig.INSTANCE.difficulty);}
    /** 注册同步 NBT；初始为空，首次 saveFlight 后才包含完整飞行快照。 */
    @Override protected void initDataTracker(DataTracker.Builder builder){
        builder.add(STATUS,new NbtCompound());builder.add(PLACEMENT_PLAYERS,new NbtCompound());
    }
    /**
     * 功能：生成前登记已经位于真实分部盒内的玩家，只对本机临时忽略碰撞。
     * 引用 net.minecraft.world.World#getOtherEntities 筛选玩家，net.minecraft.entity.data.DataTracker 同步 UUID。
     * 端侧/线程：服务端主线程，整机放置和停机坪组装共用；不设超时，完全走出前不会重新卡住。
     * 排查：客户端仍受阻时检查本方法是否在 spawnEntity 前执行，以便首次追踪就带上豁免。
     */
    public void preparePlacement(){
        NbtCompound players=new NbtCompound();
        var shapes=HelicopterCollision.shapes(this);
        for(Entity entity:getWorld().getOtherEntities(this,HelicopterCollision.bounds(this),
                other->other instanceof PlayerEntity&&!other.isSpectator()))
            if(shapes.stream().anyMatch(shape->shape.intersects(entity.getBoundingBox())))
                players.putBoolean(entity.getUuidAsString(),true);
        dataTracker.set(PLACEMENT_PLAYERS,players);
    }
    /** 两端通过 net.minecraft.entity.data.DataTracker 读取同一豁免名单，方块和其他飞机仍正常阻挡。 */
    public boolean ignoresPlacementCollision(Entity entity){
        return dataTracker.get(PLACEMENT_PLAYERS).contains(entity.getUuidAsString());
    }
    /**
     * 服务端每 tick 用真实分部盒确认玩家完全离开，再通过 net.minecraft.entity.data.DataTracker 恢复两端碰撞。
     * net.minecraft.world.World#getPlayerByUuid 找不到的离线/跨维度玩家也移除；只修改副本，保证追踪器检测到变化。
     */
    private void updatePlacementPlayers(){
        NbtCompound players=dataTracker.get(PLACEMENT_PLAYERS);
        if(players.isEmpty())return;
        NbtCompound remaining=players.copy();var shapes=HelicopterCollision.shapes(this);
        for(String uuid:players.getKeys()){
            PlayerEntity player=getWorld().getPlayerByUuid(java.util.UUID.fromString(uuid));
            if(player==null||player.isRemoved()||shapes.stream().noneMatch(shape->
                    shape.intersects(player.getBoundingBox().expand(PLACEMENT_EXIT_MARGIN))))remaining.remove(uuid);
        }
        if(!players.equals(remaining))dataTracker.set(PLACEMENT_PLAYERS,remaining);
    }
    @Override public boolean canHit(){return true;}
    /** 实体碰撞由分部有向盒处理；原版不得把整机候选大盒变成隐形墙。 */
    @Override public boolean isCollidable(){return false;}
    /** net.minecraft.entity.Entity#setPosition 重新计算候选盒；真实碰撞仍由 HelicopterCollision 裁剪。 */
    @Override protected Box calculateBoundingBox(){return HelicopterCollision.bounds(this);}
    /** 原版偏航赋值不更新盒，因此补充刷新；父类构造尚未生成位置时不访问未初始化的 FlightState。 */
    @Override public void setYaw(float yaw){super.setYaw(yaw);if(flight!=null)setBoundingBox(calculateBoundingBox());}
    /**
     * 功能：按最短角差提供逐帧偏航，机模、乘员、地图和相机共用；不修改服务端权威角度。
     * 引用 net.minecraft.util.math.MathHelper#lerpAngleDegrees 跨越 0/360 度时保持连续，避免反向整圈插值。
     */
    @Override public float getYaw(float delta){return MathHelper.lerpAngleDegrees(delta,prevYaw,getYaw());}
    /** 环境流体只检查机舱，避免尾桨擦水导致整机被认定浸水；原盒在 finally 恢复。 */
    @Override public boolean updateMovementInFluid(net.minecraft.registry.tag.TagKey<net.minecraft.fluid.Fluid> fluid,double speed){
        Box broad=getBoundingBox();setBoundingBox(HelicopterCollision.cabinBounds(this));
        try{return super.updateMovementInFluid(fluid,speed);}finally{setBoundingBox(broad);}
    }
    /** net.minecraft.entity.Entity#checkBlockCollision 的环境接触不使用长尾候选盒，以免远端方块触发机舱回调。 */
    @Override protected void checkBlockCollision(){
        Box broad=getBoundingBox();setBoundingBox(HelicopterCollision.cabinBounds(this));
        try{super.checkBlockCollision();}finally{setBoundingBox(broad);}
    }
    /** 主桨与其余部位各自记录真实方块接触，旋翼不能遮蔽机身随后发生的高速撞击。 */
    public void recordBlockImpact(HelicopterCollision.Part part,double speed){
        if(part==HelicopterCollision.Part.MAIN_ROTOR){
            mainRotorBlockImpact=true;mainRotorBlockSpeed=Math.max(mainRotorBlockSpeed,speed);
        }else if(speed>blockImpactSpeed){blockImpactPart=part;blockImpactSpeed=speed;}
    }
    /**
     * 功能：高速主桨撞方块立即折断，允许机身继续完成本次移动，不能让已断桨盘充当防撞护盾。
     * COMMON 服务端碰撞入口；速度为格/tick；返回是否折断，低转速只记录接触并保留碰撞阻挡。
     */
    public boolean breakMainRotorOnBlock(double speed){
        recordBlockImpact(HelicopterCollision.Part.MAIN_ROTOR,speed);
        if(flight.rpm<ROTOR_BREAK_RPM)return false;
        flight.rotor=0;return true;
    }
    @Override public boolean isPushable(){return false;}
    /** 实体撞击单独计算，避免原版把动物阻挡误报成撞墙并直接炸机。 */
    @Override public boolean collidesWith(Entity other){return false;}
    private boolean crashObstacle(Entity other){
        return !other.isSpectator()&&!isConnectedThroughVehicle(other)
                &&!ignoresPlacementCollision(other)
                &&getWorld().getTime()>=departingPassengers.getOrDefault(other.getUuid(),Long.MIN_VALUE)
                &&!(other instanceof RopeSeatEntity)&&!(other.getRootVehicle() instanceof RopeSeatEntity)
                &&(other.isCollidable()||other instanceof LivingEntity||other instanceof HelicopterEntity);
    }
    /** 只查询方块支撑，避免把脚边的玩家或动物误当作地面。 */
    private boolean hasBlockSupport(){
        return HelicopterCollision.supported(this);
    }
    /** 服务端在旋翼高度用 net.minecraft.world.World#hasRain 检查实际露天降雨，供飞行物理和起飞预检共用。 */
    private boolean isRotorInRain(){
        return getWorld().hasRain(BlockPos.ofFloored(getX(),getY()+ROTOR_WEATHER_HEIGHT,getZ()));
    }
    /** 难度切换只重设辅助默认状态，保留当前速度、燃油、损伤与总距。 */
    public void applyDifficulty(HeliConfig.Difficulty mode){
        stopAutopilot();flight.cancelAutoFlight();
        boolean hardcore=mode!=HeliConfig.Difficulty.CASUAL;
        flight.difficultyMode=mode.ordinal();flight.hardcoreMode=hardcore;flight.trim=!hardcore;flight.altitudeHold=!hardcore;flight.cruise=false;
        flight.targetAltitude=getY();flight.targetSpeed=Math.hypot(flight.vx,flight.vz);
    }
    /** 普通挂载遵守门和空位规则；自动登机预占者可先挂载，在门外等待开门动画结束。 */
    @Override protected boolean canAddPassenger(Entity passenger) {
        return !flight.wreck && getPassengerList().size() < HelicopterCabin.SEATS
                && (!(passenger instanceof PlayerEntity player) || cabin.canReach(player)
                && (cabin.seat(player) >= 0 || cabin.canPass(cabin.accessDoor(player))
                && cabin.occupant(cabin.accessDoor(player)) == null));
    }
    /** net.minecraft.entity.Entity#addPassenger 在原版存档恢复和真实挂载时维护固定座号。 */
    @Override protected void addPassenger(Entity passenger) { super.addPassenger(passenger); cabin.added(passenger); }
    /** 只有左前座具有驾驶权；乘降动作中不能操作飞行控制。 */
    public boolean isPilot(Entity entity) { return entity != null && pilot() == entity; }
    /** 第一座独立于乘客列表顺序，后排绝不因前排空缺而接管。 */
    private Entity pilot() { return cabin.occupant(0); }
    /** 绳顶只要求任意座位空闲，门状态不影响资格；后排优先，前排为后备空座。 */
    public boolean canBoardFromRope() { return !flight.wreck && cabin.freeRopeSeat() >= 0; }
    /** 绳顶 F/E 自动按后排优先分配空座，再串联对应门的开门、登机与关门。 */
    public boolean boardFromRope(PlayerEntity player) { return cabin.boardFromRope(player); }
    /** 任何离座途径都清除确认窗口；空中仅副驾玩家可接替主驾。 */
    @Override protected void removePassenger(Entity passenger) {
        boolean pilot = isPilot(passenger), airborne = !isCabinGrounded();
        super.removePassenger(passenger);
        if(!getWorld().isClient&&airborne)departingPassengers.put(passenger.getUuid(),getWorld().getTime()+DEPARTURE_GRACE_TICKS);
        exitWarnings.remove(passenger.getUuid());
        if (pilot) { stopAutopilot(); flight.cancelAutoFlight(); flight.clearInputs(); }
        cabin.removed(passenger, airborne);
    }
    /** 舱门与乘降判断共用真实支撑，net.minecraft.entity.Entity#isOnGround 之外兼顾停机坪和水面。 */
    public boolean isCabinGrounded() { return isOnGround() || hasBlockSupport() || isTouchingWater(); }
    /** 非爆炸机身耐久归零只脱落四门，维修恢复耐久后复原；残骸继续显示原有烧毁门板。COMMON 只读。 */
    public boolean hasCabinDoors() { return flight == null || flight.wreck || flight.hull > 0; }
    /** 离散舱门/座位动作立即同步，避免等待原有每两 tick 的飞行快照。 */
    public void syncCabin() { if (!getWorld().isClient) dataTracker.set(STATUS, saveFlight()); }
    /** 返回 null，避免原版载具移动包接管权威位置；驾驶权由固定 0 号座位另行判断。 */
    @Override public LivingEntity getControllingPassenger(){return null;}

    /**
     * 功能：处理一个来自乘员的操作包，连续轴与离散动作分开限流，并按座位复核驾驶权。
     *
     * 引用 MC API：
     *   - net.minecraft.entity.player.PlayerEntity#hasPassenger 等由 net.minecraft.entity.Entity 提供，
     *     确认发包者仍在机上；
     *   - net.minecraft.util.math.MathHelper 用于探照灯角度和输入限幅。
     *
     * 端侧/线程：仅逻辑服务端主线程；客户端只发送意图，不能指定坐标或直接改飞行状态。
     * 参数：p 为已通过 valid() 的载荷；非法动作位/非有限轴在入口被丢弃。
     * 副作用：更新输入、辅助开关、引擎、起落架、绳索、探照灯、自动驾驶和菜单；不直接改位置。
     * 排查：操纵无响应查是否主驾/动作状态；开关被吞查离散动作 5 tick 限流；接管异常查 lastInput 分支。
     */
    public void control(PlayerEntity player,ControlPayload p){
        if(!p.valid()||!hasPassenger(player)||flight.wreck)return;
        long tick=getWorld().getTime();
        // DISMOUNT 只保留给绳索；飞机下机由 cabin_door 的 F 意图进入，不接受旧 Shift 控制包。
        if((p.actions()&ControlPayload.DISMOUNT)!=0)return;
        int seatAction=p.actions()&ControlPayload.SEAT_ACTIONS;
        // 换座先于驾驶权判断，后排也能进入空主驾；本包旧座位的四轴不应用到新座位。
        if(seatAction!=0){cabin.switchSeat(player,Integer.numberOfTrailingZeros(seatAction)-Integer.numberOfTrailingZeros(ControlPayload.SEAT_PILOT));return;}
        if((p.actions()&ControlPayload.MENU)!=0&&tick-lastActions>=5){openMenu(player);lastActions=tick;}
        if((p.actions()&ControlPayload.EXIT_ROPE)!=0&&flight.rope&&tick-lastActions>=5){rappel(player);lastActions=tick;return;}
        if(!isPilot(player)||cabin.motion(player)!=HelicopterCabin.SEATED)return;
        // 灯具意图不计入手动飞行接管；自动驾驶时仍可单独搜索地面。
        if((p.actions()&ControlPayload.SEARCHLIGHT)!=0&&tick-lastLightToggle>=5){
            flight.searchlight=!flight.searchlight;lastLightToggle=tick;
        }
        if(flight.searchlight&&lastLightAim!=tick){
            lastLightAim=tick;
            int horizontal=((p.actions()&ControlPayload.LIGHT_RIGHT)!=0?1:0)-((p.actions()&ControlPayload.LIGHT_LEFT)!=0?1:0);
            int vertical=((p.actions()&ControlPayload.LIGHT_UP)!=0?1:0)-((p.actions()&ControlPayload.LIGHT_DOWN)!=0?1:0);
            // 每 tick 两度，即每秒四十度；上键抬高光束，下键朝机腹收回。
            flight.searchlightYaw=MathHelper.clamp(flight.searchlightYaw+horizontal*2,-120,120);
            flight.searchlightPitch=MathHelper.clamp(flight.searchlightPitch+vertical*2,-85,-15);
        }
        // 原始输入在写入自动驾驶指令之前判断；任何有效手动轴或辅助切换均交还控制权。
        boolean manual=Math.abs(p.pitch())>.02||Math.abs(p.roll())>.02||Math.abs(p.pedal())>.02||Math.abs(p.collective())>.02;
        if((flight.autopilot||flight.autoPhase!=0)&&(manual||(p.actions()&(ControlPayload.ENGINE|ControlPayload.ALTITUDE|ControlPayload.TRIM|ControlPayload.CRUISE))!=0)){
            stopAutopilot();flight.cancelAutoFlight();flight.targetAltitude=getY();message(player,"pilot_takeover");
        }
        // 所有辅助请求都在服务端挡住；按钮变灰只是提示，不能作为权限边界。
        int assisted=ControlPayload.ALTITUDE|ControlPayload.TRIM|ControlPayload.CRUISE|ControlPayload.AUTOPILOT|ControlPayload.AUTO_FLIGHT;
        if(flight.difficultyMode==2&&(p.actions()&assisted)!=0){message(player,"assists_disabled");return;}
        if((p.actions()&ControlPayload.AUTO_FLIGHT)!=0&&tick-lastActions>=5){lastActions=tick;toggleAutoFlight(player);return;}
        if((p.actions()&ControlPayload.AUTOPILOT)!=0&&tick-lastActions>=5){
            lastActions=tick;
            if(flight.autopilot){stopAutopilot();flight.cancelAutoFlight();message(player,"pilot_takeover");}
            else if(heightAboveGround()<10){message(player,"autopilot_low");}
            else if(!manual&&flight.waypoint&&flight.running&&flight.armed&&!isOnGround()&&!isTouchingWater()){
                flight.priorTrim=flight.trim;flight.priorAltitude=flight.altitudeHold;flight.priorCruise=flight.cruise;
                navigation.reset();flight.cancelAutoFlight();flight.autopilot=true;flight.targetAltitude=getY();message(player,"autopilot_on");
            }else message(player,"autopilot_unavailable");
        }
        // 连续轴量每 tick 接收一次，离散按键另行限流，避免同 tick 的中性包吞掉开关动作。
        if(lastInput!=tick){
        lastInput=tick;
        // 自动驾驶接管期间，中性网络包只刷新在线时间；有效手动输入已在上方解除接管。
        if(!flight.autopilot){
        flight.cyclicPitch=(float)FlightPhysics.clamp(p.pitch(),-1,1);flight.cyclicRoll=(float)FlightPhysics.clamp(p.roll(),-1,1);
        flight.stickPitch=flight.cyclicPitch;flight.stickRoll=flight.cyclicRoll;
        flight.pedal=(float)FlightPhysics.clamp(p.pedal(),-1,1);flight.collectiveInput=(float)FlightPhysics.clamp(p.collective(),-1,1);
        }
        }
        int action=p.actions();
        if((action&ControlPayload.ENGINE)!=0&&tick-lastEngine>=20){
            lastEngine=tick;
            if(flight.running){flight.running=false;flight.altitudeHold=false;flight.cruise=false;}
            else if(flight.fuel>0&&flight.engine>0&&flight.rotor>0){flight.running=true;flight.targetAltitude=getY();if(!flight.armed&&!flight.hardcoreMode){flight.trim=true;flight.altitudeHold=true;flight.cruise=false;}message(player,hasBlockSupport()&&!FlightPhysics.hasTakeoffPower(flight,isRotorInRain())?"start_power_low":"start");}
            else message(player,"start_failed");
        }
        if(tick-lastActions<5)return;
        if((action&ControlPayload.ALTITUDE)!=0){flight.altitudeHold=!flight.altitudeHold&&flight.running&&flight.armed;flight.altitudeAdjusting=false;flight.targetAltitude=getY();lastActions=tick;}
        if((action&ControlPayload.TRIM)!=0){flight.trim=!flight.trim;lastActions=tick;}
        if((action&ControlPayload.CRUISE)!=0){flight.cruise=!flight.cruise&&flight.running&&flight.armed;flight.targetSpeed=Math.hypot(flight.vx,flight.vz);lastActions=tick;}
        if((action&ControlPayload.GEAR)!=0){
            // 权威端拒绝接地时收起；仍允许放下，也允许真正离地后收起。
            if(flight.gear&&(isOnGround()||hasBlockSupport()))message(player,"gear_grounded");
            else flight.gear=!flight.gear;
            lastActions=tick;
        }
        if((action&ControlPayload.ROPE)!=0){
            if(flight.rope){if(ropeCarrier()!=null&&ropeCarrier().hasPassengers()){message(player,"rope_occupied");return;}flight.rope=false;ropeLength=0;}
            else if(safeHover()){flight.rope=true;ropeLength=(int)Math.ceil(heightAboveGround());ensureRopeCarrier();
                for(Entity occupant:List.copyOf(getPassengerList()))if(!isPilot(occupant)&&occupant instanceof VillagerEntity villager)rappel(villager);
            }else message(player,"rope_unsafe");lastActions=tick;
        }
    }
    private void message(PlayerEntity p,String key){p.sendMessage(Text.translatable("message.no_24_heli."+key),true);}
    public double heightAboveGround(){
        Vec3d start=getPos().add(0,.05,0);
        var hit=getWorld().raycast(new RaycastContext(start,start.add(0,-64,0),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.ANY,this));
        return hit.getType()==HitResult.Type.MISS?65:getY()-hit.getPos().y;
    }
    private boolean safeHover(){double h=heightAboveGround();return !flight.wreck&&h>3&&h<=24&&Math.hypot(flight.vx,flight.vz)<.10&&Math.abs(flight.vy)<.075;}
    /** 安全悬停时按 Y 自动开本座门，门全开后由舱位组件回调并转移到绳索。 */
    void rappel(Entity passenger){
        if(!safeHover())return;
        if(passenger instanceof PlayerEntity player && !cabin.prepareRappel(player))return;
        RopeSeatEntity seat=ensureRopeCarrier();if(seat!=null)seat.board(passenger,false);
    }
    /**
     * 服务端优先复用唯一绳实体，只有旧存档/首次部署才做附近搜索。
     * net.minecraft.server.world.ServerWorld#getEntity 按 UUID 恢复暂时卸载的绳，不能因超出半径另造一根。
     * 客户端只用 Flight 中的实体 ID，避免渲染器自行选择不同的绳。
     */
    public RopeSeatEntity ropeCarrier(){
        if(getWorld().isClient)return getWorld().getEntityById(ropeCarrierId) instanceof RopeSeatEntity rope?rope:null;
        if(activeRope!=null){
            if(!activeRope.isRemoved())return activeRope;
            Entity restored=((net.minecraft.server.world.ServerWorld)getWorld()).getEntity(activeRope.getUuid());
            if(restored instanceof RopeSeatEntity rope){activeRope=rope;return rope;}
            if(!activeRope.getRemovalReason().shouldDestroy())return null;
            activeRope=null;
        }
        for(var e:getWorld().getOtherEntities(this,getBoundingBox().expand(32),e->e instanceof RopeSeatEntity))
            if(((RopeSeatEntity)e).helicopter()==this){activeRope=(RopeSeatEntity)e;return activeRope;}
        return null;
    }
    /** 服务端创建缺失的部署实体；已知绳只是卸载时等待原 UUID，不重复生成。 */
    private RopeSeatEntity ensureRopeCarrier(){
        RopeSeatEntity seat=ropeCarrier();if(seat!=null)return seat;
        if(activeRope!=null)return null;
        seat=new RopeSeatEntity(No_24_heli.ROPE,getWorld());seat.attach(this);
        if(!getWorld().spawnEntity(seat))return null;
        activeRope=seat;return seat;
    }
    /**
     * 功能：服务端每 tick 主编排；客户端分支只做位置/姿态插值与视觉平滑。
     *
     * 引用 MC API：
     *   - net.minecraft.entity.Entity#tick()：实体生命周期入口；
     *   - net.minecraft.entity.Entity#move(MovementType, Vec3d)：处理真实位移和方块碰撞；
     *   - net.minecraft.entity.data.DataTracker#set：同步 Flight 快照。
     *
     * 端侧/线程：世界主线程；服务端段写权威状态，客户端段不得写物理。
     * 副作用：推进座位、物理、碰撞、伤害、绳索、雷达、空域和同步；不直接 setPos 绕过 move。
     * 排查：抖动查客户端是否写速度/服务端是否重复积分；穿墙查 move 前后顺序；
     *      事故漏判查 blockImpact 是否在 move 后结算；乘降卡住查 cabin.tick 的兜底下机。
     */
    @Override public void tick(){
        super.tick();cabin.tick();previousRotorAngle=rotorAngle;rotorAngle+=(float)(flight.rpm*65);
        if(rotorAngle>=360){rotorAngle-=360;previousRotorAngle-=360;}
        // 客户端分支：只消费已同步的 Flight 快照，做两级插值；绝不写速度、姿态权威值或乘降状态。
        if(getWorld().isClient){
            previousLightYaw=visualLightYaw;previousLightPitch=visualLightPitch;
            visualLightYaw=MathHelper.lerp(.55f,visualLightYaw,flight.searchlightYaw);
            visualLightPitch=MathHelper.lerp(.55f,visualLightPitch,flight.searchlightPitch);
            previousGearExtension=gearExtension;previousVisualPitch=visualPitch;previousVisualRoll=visualRoll;
            gearExtension=MathHelper.lerp(.15f,gearExtension,flight.gear?1:0);
            smoothPitch=MathHelper.lerp(.45f,smoothPitch,(float)flight.pitch);smoothRoll=MathHelper.lerp(.45f,smoothRoll,(float)flight.roll);
            visualPitch=MathHelper.lerp(.4f,visualPitch,smoothPitch);visualRoll=MathHelper.lerp(.4f,visualRoll,smoothRoll);
            setBoundingBox(calculateBoundingBox());
            if(lerpTicks>0){setPosition(getX()+(lerpX-getX())/lerpTicks,getY()+(lerpY-getY())/lerpTicks,getZ()+(lerpZ-getZ())/lerpTicks);lerpTicks--;}
            if(yawLerpTicks>0){setYaw(MathHelper.wrapDegrees(getYaw()+MathHelper.wrapDegrees(lerpYaw-getYaw())/yawLerpTicks));yawLerpTicks--;}
            return;
        }
        // 服务端权威段：先清冷却/过期输入，再按“任务复核 → 物理积分 → move → 碰撞/伤害 → 同步”顺序执行。
        previousGearExtension=gearExtension;gearExtension=MathHelper.lerp(.15f,gearExtension,flight.gear?1:0);
        // 创造飞行只对当前乘员关闭，不修改 allowFlying，下机仍可照常使用创造飞行。
        for(Entity passenger:getPassengerList())if(passenger instanceof net.minecraft.server.network.ServerPlayerEntity player&&player.getAbilities().flying){
            player.getAbilities().flying=false;player.sendAbilitiesUpdate();
        }
        if(entityImpactCooldown>0)entityImpactCooldown--;
        departingPassengers.values().removeIf(until->until<=getWorld().getTime());
        updatePlacementPlayers();
        if(collisionCooldown>0)collisionCooldown--;if(repairCooldown>0)repairCooldown--;
        if(getWorld().getTime()-lastInput>10||!(pilot() instanceof PlayerEntity))flight.clearInputs();

        if(!flight.wreck&&isOnGround()&&age%20==0){
            var fuel=cargo.getStack(0);double amount=fuel.isOf(No_24_heli.KEROSENE)?150:fuel.isOf(No_24_heli.CRUDE)?80:0;
            if(amount>0&&flight.fuel<=HeliConfig.INSTANCE.fuelCapacity-amount){flight.fuel+=amount;fuel.decrement(1);cargo.markDirty();}
            // 停机坪自动从货仓中的燃料补给；必须停机，防止地面维护与起飞冲突。
            if(!flight.running&&HelipadBlock.supports(getWorld(),BlockPos.ofFloored(getX(),getY()-.05,getZ()))){
                for(int i=1;i<cargo.size();i++)if(cargo.getStack(0).isEmpty()&&(cargo.getStack(i).isOf(No_24_heli.KEROSENE)||cargo.getStack(i).isOf(No_24_heli.CRUDE))){cargo.setStack(0,cargo.removeStack(i));break;}
            }
        }
        if(pilot() instanceof net.minecraft.server.network.ServerPlayerEntity pilot){
            var mode=PlayerFlightSettings.get(pilot.getServer()).mode(pilot.getUuid());
            if(flight.difficultyMode!=mode.ordinal())applyDifficulty(mode);
        }
        HeliConfig physicsConfig=flightConfig();
        boolean grounded=hasBlockSupport();
        // 支撑探测早于地面配平和弃机状态复位，不能先扶正失控机体再判定安全着陆。
        if(!flight.wreck&&grounded&&FlightPhysics.unsafeLanding(flight))destroy(true);
        boolean rain=isRotorInRain();
        airborneTicks=grounded?0:Math.min(3,airborneTicks+1);
        entityContactsEnabled=(airborneTicks>=3||flight.rpm>.05)&&!flight.wreck;
        // 自动任务与弃机：起降每 tick 复核动力、驾驶员和模式；自动驾驶只在有人、离地且动力可用时运行。
        boolean takeoffObstructed=false;
        if(flight.autoPhase!=0){
            if(!(pilot() instanceof PlayerEntity)||flight.hardcoreMode||!flight.running||flight.engine<=0||flight.rotor<40||flight.fuel<=0||flight.wreck||isTouchingWater())flight.cancelAutoFlight();
            else if((flight.autoPhase==1||flight.autoPhase==2)&&!HelicopterCollision.verticalClearance(this,
                    Math.max(0,flight.autoY-getY()))){
                // 剩余整段爬升净空失效就结束任务，捕获当前位置并制止上冲；不在低顶下继续尝试爬升。
                takeoffObstructed=true;flight.cancelAutoFlight();flight.clearInputs();flight.altitudeAdjusting=false;
                flight.targetAltitude=getY();flight.vy=Math.min(0,flight.vy);
                if(pilot() instanceof PlayerEntity p)message(p,"auto_takeoff_blocked");
            }
            else{
                updateLandingObstruction();
                // 支撑探测会提前约 0.08 格判定近地；只在通道畅通且碰撞系统确认真正接地后结束降落并关机。
                if(flight.autoPhase==3&&!flight.landingBlocked&&isOnGround()){
                    flight.cancelAutoFlight();flight.running=false;flight.collective=0;flight.altitudeHold=false;flight.clearInputs();
                    if(pilot() instanceof PlayerEntity p)message(p,"auto_landed");
                }else AutoFlightController.step(flight,getX(),getY(),getZ(),grounded,
                        physicsConfig.wind&&!grounded?FlightPhysics.windX(rain,getWorld().getTime()):0,
                        physicsConfig.wind&&!grounded?FlightPhysics.windZ(rain,getWorld().getTime()):0);
            }
        }
        if(flight.autopilot){
            if(!(pilot() instanceof PlayerEntity)||grounded||!flight.running||flight.engine<=0||flight.fuel<=0||flight.wreck)stopAutopilot();
            else navigation.step(this,rain);
        }
        // 物理积分：先清本 tick 的撞击记录，再按弃机/输入/动力/姿态顺序推进；姿态限位必须在 move 前完成。
        blockImpactSpeed=0;mainRotorBlockImpact=false;mainRotorBlockSpeed=0;
        HelicopterCollision.Pose previousPose=HelicopterCollision.pose(this);
        FlightPhysics.handleAbandonment(flight,physicsConfig,grounded,pilot() instanceof PlayerEntity,getY());
        // 自动飞行也驱动双杆；手动输入已在收包时保存，接地物理不能清掉其他乘员看到的杆量。
        if(flight.autopilot||flight.autoPhase!=0){flight.stickPitch=flight.cyclicPitch;flight.stickRoll=flight.cyclicRoll;}
        FlightPhysics.step(flight,physicsConfig,grounded,rain,getY(),getWorld().getTime());
        if(takeoffObstructed)flight.vy=Math.min(0,flight.vy);
        // 暂停当 tick 就阻止剩余下降惯性及总距渐变带来的下沉；位置仍经 Entity.move 和正常碰撞处理。
        if(flight.landingBlocked)flight.vy=Math.max(0,flight.vy);
        flight.takeoffPowerLow=!flight.wreck&&grounded&&flight.fuel>0&&flight.engine>0
                &&!FlightPhysics.hasTakeoffPower(flight,rain);
        // 结构磨损归零保留可维修机体；高速事故及爆炸仍由 destroy(true) 生成残骸。
        flight.hull=Math.max(0,flight.hull);
        var allowedPose=HelicopterCollision.limitRotation(this,previousPose,
                new HelicopterCollision.Pose(flight.yaw,flight.pitch,flight.roll));
        if(Math.abs(MathHelper.wrapDegrees(allowedPose.yaw()-flight.yaw))>.001)flight.yawRate=0;
        flight.yaw=allowedPose.yaw();flight.pitch=allowedPose.pitch();flight.roll=allowedPose.roll();
        setYaw((float)flight.yaw);
        // 位移与碰撞：只通过 Entity#move 写世界位置，不能 setPos 绕过方块/实体碰撞；moved 供扫掠复核。
        Vec3d before=getPos(),velocity=new Vec3d(flight.vx,flight.vy,flight.vz);setVelocity(velocity);
        move(MovementType.SELF,velocity);Vec3d moved=getPos().subtract(before);
        if(isOnGround()&&!hasBlockSupport())setOnGround(false);
        if(!flight.wreck){
            // 移动后才结算真实接触：旋翼、机身、实体、水面和绳索依次处理，避免提前清速度掩盖事故。
            HelicopterCollision.spinningContact(this);
            if(mainRotorBlockImpact){
                if(flight.rpm>=ROTOR_BREAK_RPM)flight.rotor=0;
                else collide(HelicopterCollision.Part.MAIN_ROTOR,Math.max(mainRotorBlockSpeed,flight.rpm*1.1));
            }
            // 倾斜接地独立于速度和普通伤害冷却；单纯旋翼接触只损伤旋翼，不直接引爆整机。
            if((isOnGround()||hasBlockSupport())&&FlightPhysics.unsafeLanding(flight))destroy(true);
            double minimum=blockImpactPart==HelicopterCollision.Part.LANDING_GEAR?.12:.02;
            // 静止接地的重力贴地不算事故；旋翼自身转动的接触速度仍正常结算。
            if(blockImpactSpeed>minimum
                    &&(!grounded||horizontalCollision||blockImpactSpeed>FlightPhysics.GRAVITY+.001))collide(blockImpactPart,blockImpactSpeed);
            // 扫过的包围盒防止高速穿越漏检；实体伤害限流，低速碰撞不反伤飞机。
            if(!flight.wreck&&entityContactsEnabled&&entityImpactCooldown==0){
                var swept=getBoundingBox().union(getBoundingBox().offset(before.subtract(getPos())));
                boolean hitEntity=false;
                for(Entity other:getWorld().getOtherEntities(this,swept.expand(HelicopterCollision.QUERY_RADIUS),this::crashObstacle)){
                    var contact=HelicopterCollision.sweptContact(this,other,before,moved.multiply(1.0001));
                    if(contact==null)continue;
                    double relative=velocity.subtract(other.getVelocity()).length();
                    if(contact.part()==HelicopterCollision.Part.MAIN_ROTOR)relative=Math.max(relative,flight.rpm*1.1);
                    if(contact.part()==HelicopterCollision.Part.TAIL_ROTOR)relative=Math.max(relative,flight.rpm*.8);
                    if(relative<=.02)continue;
                    hitEntity=true;
                    if(other instanceof HelicopterEntity aircraft)aircraft.collide(contact.otherPart(),relative);
                    else other.damage(getDamageSources().flyIntoWall(),(float)(relative*12));
                    if(relative>ENTITY_DAMAGE_SPEED||contact.part()==HelicopterCollision.Part.MAIN_ROTOR
                            ||contact.part()==HelicopterCollision.Part.TAIL_ROTOR){
                        collide(contact.part(),relative);if(flight.wreck)break;
                    }
                }
                if(hitEntity)entityImpactCooldown=10;
            }
            if(isTouchingWater()){
                flight.running=false;flight.engine=Math.max(0,flight.engine-.25);stopAutopilot();
                // 入水后阻尼及旋翼水阻使机体停稳；不要求引擎健康才可回收。
                flight.vx*=.65;flight.vz*=.65;flight.vy=Math.max(-.06,flight.vy*.65);
                flight.rpm=Math.max(0,flight.rpm-.025);
            }
            if(flight.rope){
                // 已部署绳保留初始长度，飞行时空绳也继续摆动；只有 B 主动收回或损毁才移除。
                ensureRopeCarrier();
            }
        }
        // 事故结算后才强制放架，让灾难阈值读取接地前的真实收放状态。
        if(verticalCollision&&velocity.y<0)flight.gear=true;
        if(horizontalCollision){if(Math.abs(velocity.x-moved.x)>.001)flight.vx=0;if(Math.abs(velocity.z-moved.z)>.001)flight.vz=0;}
        if(verticalCollision)flight.vy=0;
        // 本 tick 的自然磨损和碰撞均已完成，再结算低血量燃烧/爆炸；只走既有一次性事故入口。
        if(damageEffects.tick(this))destroy(true);
        // 残骸沉降：只按实际支撑点倾倒，接地当 tick 清除水平动量，避免残骸继续滑行。
        if(flight.wreck)HelicopterCollision.settleWreck(this,verticalCollision&&velocity.y<0);
        if(flight.wreck&&isOnGround()){flight.vx=0;flight.vz=0;}
        setVelocity(flight.vx,flight.vy,flight.vz);fallDistance=0;
        // 周期同步：雷达每 10 tick、空域/停机坪目录每 20 tick、飞行快照每 2 tick；频率不能互相替代。
        if(age%10==0&&hasPassengers()&&getWorld() instanceof net.minecraft.server.world.ServerWorld radarWorld){
            var contacts=radarWorld.getOtherEntities(this,new Box(getX()-512,radarWorld.getBottomY(),getZ()-512,getX()+512,radarWorld.getTopY(),getZ()+512),
                e->!hasPassenger(e)&&!e.isSpectator()&&(e instanceof PlayerEntity||e instanceof HelicopterEntity other&&!other.flight.wreck)
                    &&Math.hypot(e.getX()-getX(),e.getZ()-getZ())<=RadarContactsPayload.RADIUS)
                .stream().sorted(java.util.Comparator.comparingDouble(e->e.squaredDistanceTo(this))).limit(RadarContactsPayload.MAX_CONTACTS)
                .map(e->new RadarContactsPayload.Contact(e.getX(),e.getZ(),e instanceof HelicopterEntity)).toList();
            // 停机坪从已加载中心索引查询，不逐格扫描、不加载区块，范围和原机载雷达一致。
            var helipads=HelipadBlockEntity.centers(radarWorld).stream()
                .filter(pos->Math.hypot(pos.getX()+.5-getX(),pos.getZ()+.5-getZ())<=RadarContactsPayload.RADIUS
                    &&radarWorld.isChunkLoaded(pos)&&radarWorld.getBlockState(pos).isOf(No_24_heli.HELIPAD))
                .sorted(java.util.Comparator.comparingDouble(pos->Math.hypot(pos.getX()+.5-getX(),pos.getZ()+.5-getZ())))
                .limit(RadarContactsPayload.MAX_CONTACTS)
                .map(pos->new RadarContactsPayload.Helipad(pos,HelipadBlock.isRestricted(radarWorld,pos))).toList();
            for(Entity occupant:getPassengerList())if(occupant instanceof net.minecraft.server.network.ServerPlayerEntity player)
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,new RadarContactsPayload(getId(),contacts,helipads));
        }
        if(age%20==0&&getWorld() instanceof net.minecraft.server.world.ServerWorld sw){
            AirspaceState airspace=AirspaceState.get(sw);
            flight.restricted=!flight.wreck&&airspace.zones().stream().anyMatch(z->z.contains(getX(),getZ()));
            if(hasPassengers()){
                var helipadMap=HelipadMapPayload.of(HelipadState.get(sw));
                for(Entity occupant:getPassengerList())if(occupant instanceof net.minecraft.server.network.ServerPlayerEntity p){
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,AirspacePayload.of(airspace));
                    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,helipadMap);
                    if(flight.restricted)message(p,"restricted");
                }
            }
        }
        if(age%2==0){dataTracker.set(STATUS,saveFlight());navigation.syncPreview(this);}
    }
    /** 无驾驶员时沿用本机最后模式，避免跳机改变事故和弃机物理。 */
    private HeliConfig flightConfig(){
        return HeliConfig.forMode(HeliConfig.Difficulty.values()[Math.clamp(flight.difficultyMode,0,2)]);
    }
    /** 服务端结构碰撞（含尾梁和起落架）先判高速坠毁；旋翼单独受损，普通扣耐久仍限流十 tick。 */
    void collide(HelicopterCollision.Part part,double impact){
        if(flight.wreck)return;
        boolean body=part!=HelicopterCollision.Part.MAIN_ROTOR&&part!=HelicopterCollision.Part.TAIL_ROTOR;
        // 滑橇或尾梁先接触也会阻停整机，不能成为高速撞击的免爆保护；轻微擦碰的冷却不豁免后续坠毁。
        if(body&&FlightPhysics.catastrophic(impact,flight,flightConfig())){destroy(true);return;}
        if(collisionCooldown>0)return;
        collisionCooldown=10;
        damagePart(part,impact*(flight.hardcoreMode?30:14),false);
    }
    /** 统一碰撞与攻击的分部耐久；普通机身归零保留可维修破损，爆炸导致归零仍生成残骸。 */
    private void damagePart(HelicopterCollision.Part part,double damage,boolean explosive){
        // 使用本次受击前的机身耐久；只放大引擎/油箱实际扣血，不放大附带机身伤害或日常磨损。
        double componentDamage=damage*(1+(MAX_COMPONENT_DAMAGE_MULTIPLIER-1)*(1-Math.clamp(flight.hull/100,0,1)));
        switch(part){
            case CABIN -> flight.hull-=damage;
            case ENGINE -> {flight.engine=Math.max(0,flight.engine-componentDamage);flight.hull-=damage*.25;}
            case FUEL_TANK -> {flight.tank=Math.max(0,flight.tank-componentDamage);flight.hull-=damage*.25;}
            case MAIN_ROTOR -> {flight.rotor=Math.max(0,flight.rotor-damage);flight.hull-=damage*.1;}
            case TAIL_ROTOR -> {flight.tail=Math.max(0,flight.tail-damage);flight.hull-=damage*.1;}
            case TAIL_BOOM -> {flight.tail=Math.max(0,flight.tail-damage*.5);flight.hull-=damage*.5;}
            case LANDING_GEAR -> {flight.hull-=damage*.35;if(!flight.gear)flight.tank=Math.max(0,flight.tank-componentDamage*.5);}
        }
        flight.hull=Math.max(0,flight.hull);
        if(flight.hull<=0&&explosive)destroy(true);
    }
    /**
     * 功能：按真实射线命中的分部结算伤害；非定向环境/爆炸才用来源位置就近选部位。
     *
     * 引用 MC API：
     *   - net.minecraft.entity.damage.DamageSource 提供攻击者、投射物和爆炸标签；
     *   - net.minecraft.entity.projectile.ProjectileEntity 的上一/当前 tick 位置用于扫掠射线；
     *   - net.minecraft.entity.player.PlayerEntity#getRotationVector 读取当前权威朝向。
     *
     * 端侧/线程：仅逻辑服务端。返回：是否实际接受伤害；乘员/旁观者/已残骸/非法数值直接拒绝。
     * 副作用：扣部件/机身耐久，可能触发 destroy；基础伤害放大为原值两倍。
     * 排查：打不中查射线与分部盒；误伤自己查乘员归属；伤害不扣查冷却或 wreck。
     */
    @Override public boolean damage(DamageSource source,float amount){
        if(getWorld().isClient||isInvulnerableTo(source)||flight.wreck||!Float.isFinite(amount))return false;
        // 近战、投射物以及可归因于机上乘员的攻击均忽略，外部攻击仍正常结算。
        if(source.getAttacker()!=null&&hasPassenger(source.getAttacker())
                ||source.getSource()!=null&&hasPassenger(source.getSource()))return false;
        HelicopterCollision.Hit hit=null;
        if(source.getSource() instanceof net.minecraft.entity.projectile.ProjectileEntity projectile){
            Vec3d velocity=projectile.getVelocity();
            hit=HelicopterCollision.raycast(this,projectile.getPos().subtract(velocity),projectile.getPos().add(velocity));
        }else if(source.getAttacker() instanceof PlayerEntity attacker&&!source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_EXPLOSION)){
            Vec3d eye=attacker.getEyePos();
            // net.minecraft.entity.LivingEntity#getYaw(float) 读取头部插值，可能落后刚收到的转头包；近战使用当前权威朝向。
            Vec3d aim=attacker.getRotationVector(attacker.getPitch(),attacker.getYaw());
            hit=HelicopterCollision.raycast(this,eye,eye.add(aim.multiply(attacker.getEntityInteractionRange()+1)));
            if(hit==null)return false;
        }
        var part=hit!=null?hit.part():source.getPosition()!=null?HelicopterCollision.nearestPart(this,source.getPosition()):HelicopterCollision.Part.CABIN;
        damagePart(part,Math.max(0,amount)*2,source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_EXPLOSION));return true;
    }
    /**
     * 功能：只执行一次事故结算：授予坠机进度、保留残骸实体、散落货物、按配置爆炸并伤害乘员。
     *
     * 引用 MC API：net.minecraft.entity.ItemEntity 的 ItemScatterer.spawn 掉落库存；
     *      net.minecraft.world.World#createExplosion 生成配置化爆炸；removeAllPassengers 解除全部乘骑。
     * 端侧/线程：仅逻辑服务端主线程。
     * 副作用：flight.wreck 置位、任务/绳索停止、库存清空、实体继续作为残骸；不删除实体。
     * 排查：残骸消失查是否误调 discard；货物重复掉落查 destroy 是否重入；漏爆炸查 explosive 参数。
     */
    private void destroy(boolean explosive){
        if(flight.wreck)return;
        // 必须是事故发生时的真实驾驶员且本 tick 曾离地；乘客、停机地面损坏不算驾驶坠机。
        if(airborneTicks>0&&pilot() instanceof net.minecraft.server.network.ServerPlayerEntity driver)
            org.DJB.no_24_heli.registry.ModAdvancements.grant(driver,
                    org.DJB.no_24_heli.registry.ModAdvancements.LEGEND_NEVER_FALLS);
        flight.wreck=true;flight.running=false;flight.rope=false;flight.clearInputs();
        damageEffects.clear();
        flight.fireParts=0;
        var occupants=List.copyOf(getPassengerList());removeAllPassengers();
        if(explosive)for(Entity occupant:occupants)occupant.damage(getDamageSources().genericKill(),Float.MAX_VALUE);
        ItemScatterer.spawn(getWorld(),this,cargo);cargo.clear();
        if(explosive){boolean blocks=HeliConfig.INSTANCE.explosionBlockDamage;getWorld().createExplosion(this,getX(),getY()+.5,getZ(),(float)HeliConfig.INSTANCE.explosionPower,blocks,blocks?World.ExplosionSourceType.TNT:World.ExplosionSourceType.NONE);}
        dataTracker.set(STATUS,saveFlight());
    }
    /**
     * 功能：右键机身/残骸的统一服务端交互，按手持物和潜行状态分派乘降、维修、涂装、牵引、货仓和回收。
     *
     * 引用 MC API：net.minecraft.entity.player.PlayerEntity#interact 是原版实体交互入口；
     *      net.minecraft.item.ItemStack/Items 判断维修包、染料、拴绳；ActionResult 决定客户端摆臂/消耗。
     * 端侧/线程：客户端只返回 SUCCESS 让原版继续，所有状态修改在服务端；主线程。
     * 副作用：可能打开容器、消耗物品、改涂装/燃油、生成村民座位、回收整机；右键不让玩家登机。
     * 排查：右键无反应查客户端提前返回/准星；维修无效查冷却和耐久上限；回收失败查 canRecover 条件。
     */
    @Override public ActionResult interact(PlayerEntity player,Hand hand){
        if(isRemoved())return ActionResult.FAIL;
        if(getWorld().isClient)return ActionResult.SUCCESS;
        ItemStack stack=player.getStackInHand(hand);
        if(flight.wreck){
            if(player.isSneaking()){dropStack(new ItemStack(Items.IRON_NUGGET,8+random.nextInt(10)));if(random.nextBoolean())dropStack(new ItemStack(No_24_heli.TAIL));discard();}
            else message(player,"salvage");return ActionResult.CONSUME;
        }
        if(stack.isOf(No_24_heli.REPAIR)){
            if(repairCooldown>0)return ActionResult.CONSUME;
            boolean major=isOnGround()&&flight.rpm<.05;
            if(flight.rotor>=100&&flight.tail>=100&&flight.engine>=100&&flight.tank>=100&&flight.hull>=100)return ActionResult.CONSUME;
            double gain=major?30:3;
            flight.rotor=Math.min(100,flight.rotor+gain);flight.tail=Math.min(100,flight.tail+gain);flight.engine=Math.min(100,flight.engine+gain);flight.hull=Math.min(100,flight.hull+gain);
            if(major)flight.tank=Math.min(100,flight.tank+gain);
            damageEffects.refresh(this);
            repairCooldown=major?20:200;if(!player.isCreative())stack.decrement(1);message(player,major?"repaired":"emergency_repair");return ActionResult.CONSUME;
        }
        for(int i=0;i<No_24_heli.PAINTS.length;i++)if(stack.isOf(No_24_heli.PAINTS[i])){
            if(isOnGround()&&flight.rpm<.05){
                flight.paint=i;if(!player.isCreative())stack.decrement(1);
                // PAINTS 第六项为紫金涂装；只有实际完成涂装，才授予原版持久化进度。
                if(i==5&&player instanceof net.minecraft.server.network.ServerPlayerEntity painter)
                    org.DJB.no_24_heli.registry.ModAdvancements.grant(painter,
                            org.DJB.no_24_heli.registry.ModAdvancements.TRIBUTE_TO_LEGEND);
            }else message(player,"ground_only");return ActionResult.CONSUME;
        }
        if(stack.isOf(Items.LEAD)&&pilot() instanceof PlayerEntity&&getPassengerList().size()<HelicopterCabin.SEATS){
            int side=cabin.accessSide(player), seat=cabin.freeSeat(side);
            if(seat>=0)for(VillagerEntity villager:getWorld().getEntitiesByClass(VillagerEntity.class,getBoundingBox().expand(5),v->!v.hasVehicle()))
                if(cabin.mount(villager,seat,true))return ActionResult.CONSUME;
        }
        if(player.isSneaking()){
            // 空手蹲下回收；手持物品蹲下仍能打开原有货仓，避免入口冲突。
            if(stack.isEmpty()&&!hasPassenger(player)){
                if(canRecover()){
                    ItemStack packed=new ItemStack(No_24_heli.HELICOPTER);
                    NbtCompound saved=new NbtCompound();writeCustomDataToNbt(saved);
                    packed.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA,net.minecraft.component.type.NbtComponent.of(saved));
                    // 先交付唯一物品再移除实体；不散落货物，避免重复掉落。
                    if(!player.getInventory().insertStack(packed))player.dropItem(packed,false);
                    cargo.clear();discard();message(player,"recovered");
                }else message(player,"recover_unsafe");
            }else if(isOnGround()||hasPassenger(player)||isTouchingWater())openMenu(player);
            return ActionResult.CONSUME;
        }
        return ActionResult.PASS;
    }
    public void openMenu(PlayerEntity player){
        if(flight.wreck)return;
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((id,inv,p)->new HeliScreenHandler(id,inv,cargo,new PropertyDelegate(){
            public int size(){return 8;}
            public int get(int i){return switch(i){case 0->(int)flight.fuel;case 1->(int)HeliConfig.INSTANCE.fuelCapacity;case 2->(int)flight.rotor;case 3->(int)flight.tail;case 4->(int)flight.engine;case 5->(int)flight.tank;case 6->(int)flight.hull;default->(int)(flight.rpm*100);};}
            public void set(int i,int value){}
        },this),Text.translatable("container.no_24_heli.service")));
    }
    /** net.minecraft.entity.Entity.PositionUpdater 使用与动画共用的脚位；服务端只旋转偏航，视觉跟随机体。 */
    @Override protected void updatePassengerPosition(Entity passenger,PositionUpdater updater){
        Vec3d position=cabin.toWorld(cabin.position(passenger,0));
        updater.accept(passenger,position.x,position.y,position.z);passenger.fallDistance=0;
    }
    /** 正常乘降的出口由动画预检，同侧下机不再穿过机身寻找另一侧。 */
    @Override public Vec3d updatePassengerForDismount(LivingEntity passenger){return cabin.exitPosition(passenger);}
    /**
     * 功能：接收原版实体位置插值目标；Flight 高精度姿态已到达后，不再用字节偏航覆盖它。
     * 引用 net.minecraft.entity.Entity#updateTrackedPositionAndAngles；客户端主线程调用，不改变权威物理。
     */
    @Override public void updateTrackedPositionAndAngles(double x,double y,double z,float yaw,float pitch,int steps){
        lerpX=x;lerpY=y;lerpZ=z;lerpTicks=Math.max(1,steps);
        if(!preciseYaw){lerpYaw=yaw;yawLerpTicks=Math.max(1,steps);}
    }
    @Override public void onTrackedDataSet(TrackedData<?> data){super.onTrackedDataSet(data);if(STATUS.equals(data)&&getWorld().isClient)loadFlight(dataTracker.get(STATUS));}

    /** 存档版本 4：显式飞行与可选火灾字段，旧存档缺少部件字段时默认完整，损毁状态绝不重置。 */
    private NbtCompound saveFlight(){
        NbtCompound n=new NbtCompound();n.putInt("Version",4);cabin.write(n);damageEffects.write(n);
        // 供主副驾联动摇杆和远端握杆姿态读取；读档结束仍由 clearInputs 清除，不恢复控制意图。
        n.putFloat("CyclicPitch",flight.stickPitch);n.putFloat("CyclicRoll",flight.stickRoll);
        n.putFloat("RotorAngle",rotorAngle);
        n.putInt("RopeCarrierId",activeRope!=null&&!activeRope.isRemoved()?activeRope.getId():-1);
        n.putBoolean("Searchlight",flight.searchlight&&!flight.wreck);
        n.putFloat("SearchlightYaw",flight.searchlightYaw);n.putFloat("SearchlightPitch",flight.searchlightPitch);
        n.putInt("DifficultyMode",flight.difficultyMode);n.putInt("AutoPhase",flight.autoPhase);
        n.putBoolean("LandingBlocked",flight.landingBlocked);
        n.putBoolean("TakeoffPowerLow",flight.takeoffPowerLow);
        n.putInt("FireParts",flight.fireParts);
        n.putInt("RadioTrack",flight.radioTrack);n.putInt("RadioRevision",flight.radioRevision);n.putLong("RadioStarted",flight.radioStartedTick);
        n.putBoolean("Waypoint",flight.waypoint);n.putDouble("WaypointX",flight.waypointX);n.putDouble("WaypointZ",flight.waypointZ);
        n.putBoolean("Autopilot",flight.autopilot);n.putBoolean("Restricted",flight.restricted);n.putBoolean("HardcoreMode",flight.hardcoreMode);n.putInt("AbandonedTicks",flight.abandonedTicks);
        n.putDouble("Rpm",flight.rpm);n.putDouble("Collective",flight.collective);n.putDouble("Pitch",flight.pitch);n.putDouble("Roll",flight.roll);n.putDouble("Yaw",flight.yaw);n.putDouble("YawRate",flight.yawRate);
        n.putDouble("Fuel",flight.fuel);n.putDouble("Rotor",flight.rotor);n.putDouble("Tail",flight.tail);n.putDouble("Engine",flight.engine);n.putDouble("Tank",flight.tank);n.putDouble("Hull",flight.hull);
        n.putDouble("Vx",flight.vx);n.putDouble("Vy",flight.vy);n.putDouble("Vz",flight.vz);n.putDouble("TargetAltitude",flight.targetAltitude);n.putDouble("TargetSpeed",flight.targetSpeed);
        n.putBoolean("Running",flight.running);n.putBoolean("Armed",flight.armed);n.putBoolean("Altitude",flight.altitudeHold);n.putBoolean("Trim",flight.trim);n.putBoolean("Cruise",flight.cruise);n.putBoolean("Gear",flight.gear);n.putBoolean("Wreck",flight.wreck);n.putBoolean("Rope",flight.rope);n.putInt("Paint",flight.paint);n.putInt("RopeLength",ropeLength);return n;
    }
    private static double number(NbtCompound n,String key,double fallback,double min,double max){double v=n.contains(key)?n.getDouble(key):fallback;return Double.isFinite(v)?FlightPhysics.clamp(v,min,max):fallback;}
    private void loadFlight(NbtCompound n){
        cabin.read(n);
        if(n.contains("RotorAngle")){
            float correction=MathHelper.wrapDegrees((float)number(n,"RotorAngle",0,0,360)-rotorAngle);
            rotorAngle+=correction;previousRotorAngle+=correction;
        }
        flight.stickPitch=(float)number(n,"CyclicPitch",0,-1,1);
        flight.stickRoll=(float)number(n,"CyclicRoll",0,-1,1);
        ropeCarrierId=n.contains("RopeCarrierId")?n.getInt("RopeCarrierId"):-1;
        flight.searchlight=n.getBoolean("Searchlight");
        flight.searchlightYaw=(float)number(n,"SearchlightYaw",0,-120,120);
        flight.searchlightPitch=(float)number(n,"SearchlightPitch",-65,-85,-15);
        flight.difficultyMode=n.contains("DifficultyMode")?Math.clamp(n.getInt("DifficultyMode"),0,2):HeliConfig.INSTANCE.difficulty.ordinal();
        flight.autoPhase=Math.clamp(n.getInt("AutoPhase"),0,3);
        flight.landingBlocked=flight.autoPhase==3&&n.getBoolean("LandingBlocked");
        flight.takeoffPowerLow=n.getBoolean("TakeoffPowerLow");
        flight.radioTrack=n.contains("RadioTrack")?Math.clamp(n.getInt("RadioTrack"),-1,7):-1;
        flight.radioRevision=n.getInt("RadioRevision");flight.radioStartedTick=n.getLong("RadioStarted");
        flight.hardcoreMode=n.contains("HardcoreMode")?n.getBoolean("HardcoreMode"):flight.difficultyMode>0;
        flight.waypoint=n.getBoolean("Waypoint");flight.waypointX=number(n,"WaypointX",0,-29999984,29999984);flight.waypointZ=number(n,"WaypointZ",0,-29999984,29999984);
        flight.autopilot=n.getBoolean("Autopilot");flight.restricted=n.getBoolean("Restricted");
        flight.abandonedTicks=Math.clamp(n.getInt("AbandonedTicks"),0,1000000);
        double tiltLimit=n.getBoolean("Wreck")?180:60;
        flight.rpm=number(n,"Rpm",0,0,1);flight.collective=number(n,"Collective",0,0,1);flight.pitch=number(n,"Pitch",0,-tiltLimit,tiltLimit);flight.roll=number(n,"Roll",0,-tiltLimit,tiltLimit);flight.yaw=number(n,"Yaw",getYaw(),-360,360);flight.yawRate=number(n,"YawRate",0,-20,20);
        if(getWorld().isClient&&n.contains("Yaw")){
            lerpYaw=(float)flight.yaw;
            if(!preciseYaw){setYaw(lerpYaw);prevYaw=getYaw();preciseYaw=true;}
            yawLerpTicks=YAW_INTERPOLATION_TICKS;
        }
        flight.fuel=number(n,"Fuel",0,0,100000);flight.rotor=number(n,"Rotor",100,0,100);flight.tail=number(n,"Tail",100,0,100);flight.engine=number(n,"Engine",100,0,100);flight.tank=number(n,"Tank",100,0,100);flight.hull=number(n,"Hull",100,0,100);
        flight.vx=number(n,"Vx",0,-3,3);flight.vy=number(n,"Vy",0,-3,3);flight.vz=number(n,"Vz",0,-3,3);flight.targetAltitude=number(n,"TargetAltitude",getY(),-2048,2048);flight.targetSpeed=number(n,"TargetSpeed",0,0,3);
        flight.running=n.getBoolean("Running");flight.armed=n.getBoolean("Armed");flight.altitudeHold=n.getBoolean("Altitude");flight.trim=n.contains("Trim")?n.getBoolean("Trim"):!flight.hardcoreMode;flight.cruise=n.getBoolean("Cruise");flight.gear=!n.contains("Gear")||n.getBoolean("Gear");flight.wreck=n.getBoolean("Wreck");flight.rope=n.getBoolean("Rope");flight.paint=Math.clamp(n.contains("Paint")?n.getInt("Paint"):FlightState.POLICE_PAINT,0,FlightState.POLICE_PAINT);ropeLength=Math.clamp(n.getInt("RopeLength"),0,24);
        damageEffects.read(n,flight);
        flight.fireParts=flight.wreck?0:n.getInt("FireParts")&15;
    }
    /**
     * K为可撤销的起降任务：地面开始二十格起飞，空中尝试垂直降落，再按一次取消。
     * 降落前检查两条滑橇前后承重面的同高平整支撑，机体、主尾桨与长尾各自检查下降净空；
     * 起飞使用真实分部盒检查整条升空通道；降落的实体通道在启动时和下降期间持续复核，占用只暂停任务。
     */
    private void toggleAutoFlight(PlayerEntity player){
        if(flight.autoPhase!=0){flight.cancelAutoFlight();flight.clearInputs();flight.targetAltitude=getY();message(player,"pilot_takeover");return;}
        if(flight.hardcoreMode){message(player,"auto_mode_disabled");return;}
        if(flight.fuel<=0||flight.engine<30||isTouchingWater()){message(player,"auto_unsafe");return;}
        if(flight.rotor<70){message(player,"auto_rotor_damaged");return;}
        boolean ground=isOnGround()||hasBlockSupport();double target=getY()+AutoFlightController.TAKEOFF_HEIGHT;
        if(ground&&!FlightPhysics.hasTakeoffPower(flight,isRotorInRain())){message(player,"takeoff_power_low");return;}
        if(ground){if(!HelicopterCollision.verticalClearance(this,AutoFlightController.TAKEOFF_HEIGHT)){message(player,"auto_takeoff_blocked");return;}}
        else{
            target=HelicopterCollision.landingTargetY(this);
            if(!Double.isFinite(target)){message(player,"auto_unsafe");return;}
        }
        stopAutopilot();flight.autoPhase=ground?1:3;flight.autoX=getX();flight.autoZ=getZ();flight.autoY=target;
        // 起飞接管沿用当前总距，预转期间和离地后均由高度控制渐变调整。
        flight.running=true;flight.gear=true;flight.clearInputs();flight.altitudeAdjusting=false;
        updateLandingObstruction();
        message(player,ground?"auto_takeoff":"auto_landing");
    }

    /**
     * 功能：自动降落每 tick 复核滑橇平整支撑、分部净空与实体占用，首次受阻捕获暂停高度。
     * 引用 MC API：net.minecraft.entity.Entity#getY 获取本机高度；实体查询交给 HelicopterCollision。
     * 端侧/线程：服务端主线程；参数/返回：无；副作用：更新同步警告、暂停高度并制止向下速度。
     * 排查：不能覆盖 autoY，否则通道清空后会停在半空；取消任务统一清除 landingBlocked，防止灯和语音残留。
     */
    private void updateLandingObstruction(){
        boolean blocked=flight.autoPhase==3&&(!HelicopterCollision.landingTerrainClear(this,flight.autoY,flight.autoX,flight.autoZ)
                ||HelicopterCollision.landingObstructed(this,flight.autoY));
        if(blocked&&!flight.landingBlocked)flight.targetAltitude=getY();
        flight.landingBlocked=blocked;
        if(blocked)flight.vy=Math.max(0,flight.vy);
    }

    /** 驾驶与副驾驶都可选曲；服务器只接受固定八槽，并以5tick冷却避免恶意反复切歌。 */
    public void selectRadio(PlayerEntity player,int track){
        long now=getWorld().getTime();
        if(!hasPassenger(player)||flight.wreck||track< -1||track>=8||now-lastRadio<5)return;
        lastRadio=now;flight.radioTrack=track;flight.radioRevision++;flight.radioStartedTick=now;
        dataTracker.set(STATUS,saveFlight());
    }
    /** 地面停稳或入水减速后可整机回收；损伤和引擎健康度不是回收门槛。 */
    public boolean canRecover(){
        return !flight.wreck&&!hasPassengers()&&!flight.running&&flight.rpm<.05
                &&Math.hypot(flight.vx,flight.vz)<.08&&Math.abs(flight.vy)<.08
                &&(isOnGround()||hasBlockSupport()||isTouchingWater());
    }
    /** 回收物品仅恢复白名单存档字段，停机放架并清除运动及临时接管，保留库存/油量/耐久。 */
    public void restorePacked(ItemStack stack){
        var component=stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if(component!=null)readCustomDataFromNbt(component.copyNbt());
        flight.running=false;flight.armed=false;flight.rpm=0;flight.collective=0;
        flight.vx=flight.vy=flight.vz=flight.pitch=flight.roll=flight.yawRate=0;flight.abandonedTicks=0;
        // 回收物品可能保存着收架状态；地面放置必须在第一次碰撞前完全放架，避免落低后再展开嵌入薄平台。
        flight.gear=true;gearExtension=previousGearExtension=1;
    }
    public void stopAutopilot(){
        navigation.reset();
        if(!flight.autopilot)return;
        flight.autopilot=false;flight.clearInputs();flight.trim=flight.priorTrim;flight.altitudeHold=flight.priorAltitude;flight.cruise=flight.priorCruise;
        flight.targetAltitude=getY();flight.targetSpeed=Math.hypot(flight.vx,flight.vz);
    }
    /**
     * 功能：到点停稳后结束导航，使用正常配平与高度保持继续悬停。
     * 引用 MC API：net.minecraft.entity.Entity#getY 记录当前悬停高度。
     * 端侧/线程：逻辑服务端 tick；仅由 NavigationPilot 在最终点附近低速调用。
     * 返回：无。副作用：取消后台任务并重设辅助，不瞬移、不清零实际速度或旋翼。
     * 故障排查：完成后继续前飞查 cruise 是否关闭；手动退出恢复仍由 stopAutopilot 处理。
     */
    public void finishAutopilot(){
        stopAutopilot();flight.trim=true;flight.altitudeHold=true;flight.cruise=false;
        flight.targetSpeed=0;flight.clearInputs();
    }
    /**
     * 功能：在卸载、销毁或移除实体时取消其后台规划，防止无效请求占用队列。
     * 引用 MC API：net.minecraft.entity.Entity#onRemoved 为当前映射的实体移除回调。
     * 端侧/线程：世界主线程。返回：无。副作用：只清理临时导航，不改存档。
     * 故障排查：卸载后仍计算查是否覆盖并调用父类回调。
     */
    @Override public void onRemoved(){navigation.reset();super.onRemoved();}
    /** 机上乘员均可设置导航点；开启自动驾驶仍必须通过驾驶员控制接口。 */
    public void navigate(PlayerEntity player,NavigationPayload p){
        if(!hasPassenger(player)||flight.wreck||!Double.isFinite(p.x())||!Double.isFinite(p.z())
                ||Math.abs(p.x())>29999984||Math.abs(p.z())>29999984
                ||!getWorld().getWorldBorder().contains(BlockPos.ofFloored(p.x(),getY(),p.z())))return;
        if(!p.clear()&&getWorld() instanceof net.minecraft.server.world.ServerWorld sw
                &&(AirspaceState.get(sw).zones().stream().anyMatch(z->z.contains(p.x(),p.z()))
                    ||HelipadBlock.isRestrictedTarget(sw,p.x(),p.z()))){message(player,"waypoint_restricted");return;}
        stopAutopilot();navigation.reset();flight.cancelAutoFlight();flight.waypoint=!p.clear();flight.waypointX=p.x();flight.waypointZ=p.z();
        message(player,p.clear()?"waypoint_clear":"waypoint_set");dataTracker.set(STATUS,saveFlight());
    }
    /**
     * 功能：F 下机入口；地面直接开门走下，空中先警告并在 60 tick 内二次确认才跃出。
     *
     * 引用 MC API：无直接调用；实际开门/离座交给 {@link HelicopterCabin#beginExit}，
     *      解除乘骑仍由原版 net.minecraft.entity.Entity#stopRiding 完成。
     * 端侧/线程：仅逻辑服务端；客户端通过 CabinDoorPayload 发送 F 意图。
     * 副作用：记录确认窗口或启动离机；地面出口被卡住时由舱位组件直接下载具，不会保留座位。
     * 排查：首次按无反应查 isCabinGrounded；二次按仍不走查动作状态/座位；同 tick 重复请求不算确认。
     */
    public void requestDismount(PlayerEntity player){
        if(flight.wreck||!hasPassenger(player)||cabin.motion(player)!=HelicopterCabin.SEATED)return;
        long now=getWorld().getTime();Long until=exitWarnings.get(player.getUuid());
        boolean grounded=isCabinGrounded();
        if(!grounded&&until!=null&&now==until-EXIT_CONFIRM_TICKS)return;
        if(grounded||(until!=null&&now<=until)){
            exitWarnings.remove(player.getUuid());cabin.beginExit(player,!grounded);
        }else{exitWarnings.put(player.getUuid(),now+EXIT_CONFIRM_TICKS);message(player,"exit_warning");}
    }
    @Override protected void writeCustomDataToNbt(NbtCompound n){n.put("Flight",saveFlight());var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);for(int i=0;i<19;i++)items.set(i,cargo.getStack(i));Inventories.writeNbt(n,items,getRegistryManager());}
    @Override protected void readCustomDataFromNbt(NbtCompound n){loadFlight(n.getCompound("Flight"));cabin.resetAnimations();var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);Inventories.readNbt(n,items,getRegistryManager());for(int i=0;i<19;i++)cargo.setStack(i,items.get(i));// 飞行任务不跨重载恢复，电台也默认静音；导航点和机体数据仍保留。
        flight.cancelAutoFlight();flight.radioTrack=-1;flight.autopilot=false;flight.restricted=false;
        if(n.getCompound("Flight").getInt("Version")<4)flight.cruise=false;
        flight.clearInputs();flight.rope=false;ropeLength=0;dataTracker.set(STATUS,saveFlight());}
}
