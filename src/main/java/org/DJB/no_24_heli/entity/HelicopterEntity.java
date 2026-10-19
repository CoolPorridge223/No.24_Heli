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

/** 服务端权威载具：负责物理、碰撞、交互、乘员、保存以及客户端状态快照。 */
public class HelicopterEntity extends Entity {
    /** 双座整体朝机头前移 0.12 格；座椅、乘员、握杆手臂和第一人称眼位共用此偏移。 */
    public static final double SEAT_FORWARD_OFFSET=.12;
    /** 主旋翼毂相对机体原点的高度，单位格；天气采样不能落在低于滑橇的实体原点而被地面误遮挡。 */
    private static final double ROTOR_WEATHER_HEIGHT=2.71;
    private static final TrackedData<NbtCompound> STATUS=DataTracker.registerData(HelicopterEntity.class,TrackedDataHandlerRegistry.NBT_COMPOUND);
    private final NavigationPilot navigation=new NavigationPilot();
    public final FlightState flight=new FlightState();
    public final SimpleInventory cargo=new SimpleInventory(19);
    /** 桨叶角度单位为度；实体快照同步相位，使停转桨叶的选取与显示仍一致。 */
    public float rotorAngle,previousRotorAngle;
    /** 实体自身的只读几何快照，位置、姿态、收放或桨盘状态变化时失效；不持久化。 */
    volatile HelicopterCollision.Cache collisionCache;
    public float visualPitch,visualRoll,gearExtension=1;
    /** 保存上一 tick 的第二级平滑结果，渲染帧再次插值，避免 20 Hz 阶梯跳变。 */
    public float previousVisualPitch,previousVisualRoll,previousGearExtension=1;
    /** 云台视觉角仅客户端平滑；单位：度，与机体同样做 tick/帧双级插值。 */
    public float visualLightYaw,previousLightYaw,visualLightPitch=-65,previousLightPitch=-65;
    private float smoothPitch,smoothRoll;
    private final java.util.Map<java.util.UUID,Long> exitWarnings=new java.util.HashMap<>();
    public int ropeLength;
    /** 当前部署的唯一绳实体；运行时缓存跨越查询半径，卸载后按同一 UUID 等待恢复。 */
    private RopeSeatEntity activeRope;
    /** 客户端从 Flight 同步的权威绳实体 ID，仅用于查找与绘制，不跨存档恢复部署。 */
    private int ropeCarrierId=-1;
    /** 从绳索进入的指定副驾，空驾驶座不能把该乘员自动提升为驾驶员。 */
    private java.util.UUID ropeCopilot;
    private long lastRadio=-100;
    private long lastInput=-100,lastActions=-100,lastEngine=-100;
    /** 灯具开关独立限流五 tick，瞄准每 tick 最多一次；不阻塞引擎或飞行辅助。 */
    private long lastLightToggle=-100,lastLightAim=-100;
    private int collisionCooldown,repairCooldown,entityImpactCooldown;
    /** 本 tick 真实受阻部位与被阻挡速度，单位格/tick；由有向盒扫掠记录，逻辑服务端结算。 */
    private HelicopterCollision.Part blockImpactPart=HelicopterCollision.Part.CABIN;
    private double blockImpactSpeed;
    /** 低于 10 m/s 的实体碰撞不损伤机体；高速撞击仍按飞控事故阈值处理。 */
    public static final double ENTITY_DAMAGE_SPEED=.5;
    /** 连续离开方块支撑三 tick 后才启用实体事故，防止放置接地前误判。 */
    private int airborneTicks;
    private boolean entityContactsEnabled;
    private double lerpX,lerpY,lerpZ;
    private float lerpYaw;
    private int lerpTicks;
    public HelicopterEntity(EntityType<?> type,World world){super(type,world);intersectionChecked=true;applyDifficulty(HeliConfig.INSTANCE.difficulty);}
    @Override protected void initDataTracker(DataTracker.Builder builder){builder.add(STATUS,new NbtCompound());}
    @Override public boolean canHit(){return true;}
    /** 实体碰撞由分部有向盒处理；原版不得把整机候选大盒变成隐形墙。 */
    @Override public boolean isCollidable(){return false;}
    /** net.minecraft.entity.Entity#setPosition 重新计算候选盒；真实碰撞仍由 HelicopterCollision 裁剪。 */
    @Override protected Box calculateBoundingBox(){return HelicopterCollision.bounds(this);}
    /** 原版偏航赋值不更新盒，因此补充刷新；父类构造尚未生成位置时不访问未初始化的 FlightState。 */
    @Override public void setYaw(float yaw){super.setYaw(yaw);if(flight!=null)setBoundingBox(calculateBoundingBox());}
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
    /** 只保留本 tick 最强的真实分部接触；静止旋翼擦墙也能归因到正确部位。 */
    public void recordBlockImpact(HelicopterCollision.Part part,double speed){
        if(speed>blockImpactSpeed){blockImpactPart=part;blockImpactSpeed=speed;}
    }
    @Override public boolean isPushable(){return false;}
    /** 实体撞击单独计算，避免原版把动物阻挡误报成撞墙并直接炸机。 */
    @Override public boolean collidesWith(Entity other){return false;}
    private boolean crashObstacle(Entity other){
        return !(other instanceof PlayerEntity)&&!other.isSpectator()&&!isConnectedThroughVehicle(other)
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
    @Override protected boolean canAddPassenger(Entity passenger){return !flight.wreck&&getPassengerList().size()<2;}
    public boolean isPilot(Entity e){return e!=null&&pilot()==e;}
    /** 原有普通乘员顺序保持；绳索指定副驾始终跳过，空驾驶座仍按弃机规则处理。 */
    private Entity pilot(){
        for(Entity passenger:getPassengerList())if(!passenger.getUuid().equals(ropeCopilot))return passenger;
        return null;
    }
    /** 两端共用的副驾空闲判定，客户端 HUD 仅读取同步座位，服务端转移前再次复核。 */
    public boolean canBoardRopeCopilot(){return !flight.wreck&&getPassengerList().size()<2&&ropeCopilot==null;}
    /** 仅绳顶附近 E 调用；副驾空闲才转移，不会抢占驾驶座或自动接管飞控。 */
    public boolean boardRopeCopilot(PlayerEntity player){
        if(!canBoardRopeCopilot())return false;
        if(!player.startRiding(this,true))return false;
        ropeCopilot=player.getUuid();player.fallDistance=0;return true;
    }
    /** 任何离座途径都清除旧确认窗口；驾驶员离座时立即停止导航，副驾驶离座不干扰驾驶。 */
    @Override protected void removePassenger(Entity passenger){
        boolean pilot=isPilot(passenger);super.removePassenger(passenger);
        exitWarnings.remove(passenger.getUuid());if(pilot){stopAutopilot();flight.cancelAutoFlight();}
        if(passenger.getUuid().equals(ropeCopilot))ropeCopilot=null;
    }
    @Override public LivingEntity getControllingPassenger(){return null;}

    /** 操作权限只属于第一座乘员；副驾驶可以开货仓和使用已部署绳索。 */
    public void control(PlayerEntity player,ControlPayload p){
        if(!p.valid()||!hasPassenger(player)||flight.wreck)return;
        long tick=getWorld().getTime();
        if((p.actions()&ControlPayload.DISMOUNT)!=0){requestDismount(player);return;}
        if((p.actions()&ControlPayload.MENU)!=0&&tick-lastActions>=5){openMenu(player);lastActions=tick;}
        if((p.actions()&ControlPayload.EXIT_ROPE)!=0&&flight.rope&&tick-lastActions>=5){rappel(player);lastActions=tick;return;}
        if(!isPilot(player))return;
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
                navigation.reset();flight.cancelAutoFlight();flight.autopilot=true;flight.navAligned=false;flight.targetAltitude=getY();message(player,"autopilot_on");
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
                if(getPassengerList().size()>1&&getPassengerList().get(1) instanceof VillagerEntity villager)rappel(villager);
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
    private void rappel(Entity passenger){
        if(!safeHover())return;
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
    @Override public void tick(){
        super.tick();previousRotorAngle=rotorAngle;rotorAngle+=(float)(flight.rpm*65);
        if(rotorAngle>=360){rotorAngle-=360;previousRotorAngle-=360;}
        if(getWorld().isClient){
            previousLightYaw=visualLightYaw;previousLightPitch=visualLightPitch;
            visualLightYaw=MathHelper.lerp(.55f,visualLightYaw,flight.searchlightYaw);
            visualLightPitch=MathHelper.lerp(.55f,visualLightPitch,flight.searchlightPitch);
            previousGearExtension=gearExtension;previousVisualPitch=visualPitch;previousVisualRoll=visualRoll;
            gearExtension=MathHelper.lerp(.15f,gearExtension,flight.gear?1:0);
            smoothPitch=MathHelper.lerp(.45f,smoothPitch,(float)flight.pitch);smoothRoll=MathHelper.lerp(.45f,smoothRoll,(float)flight.roll);
            visualPitch=MathHelper.lerp(.4f,visualPitch,smoothPitch);visualRoll=MathHelper.lerp(.4f,visualRoll,smoothRoll);
            setBoundingBox(calculateBoundingBox());
            if(lerpTicks>0){setPosition(getX()+(lerpX-getX())/lerpTicks,getY()+(lerpY-getY())/lerpTicks,getZ()+(lerpZ-getZ())/lerpTicks);setYaw(getYaw()+MathHelper.wrapDegrees(lerpYaw-getYaw())/lerpTicks);lerpTicks--;}
            return;
        }
        previousGearExtension=gearExtension;gearExtension=MathHelper.lerp(.15f,gearExtension,flight.gear?1:0);
        // 创造飞行只对当前乘员关闭，不修改 allowFlying，下机仍可照常使用创造飞行。
        for(Entity passenger:getPassengerList())if(passenger instanceof net.minecraft.server.network.ServerPlayerEntity player&&player.getAbilities().flying){
            player.getAbilities().flying=false;player.sendAbilitiesUpdate();
        }
        if(entityImpactCooldown>0)entityImpactCooldown--;
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
        boolean rain=isRotorInRain();
        airborneTicks=grounded?0:Math.min(3,airborneTicks+1);
        entityContactsEnabled=(airborneTicks>=3||flight.rpm>.05)&&!flight.wreck;
        // 起降阶段每tick复核动力、驾驶员和模式；不会因为客户端断线而保留无人起飞任务。
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
                }else AutoFlightController.step(flight,getX(),getY(),getZ(),grounded);
            }
        }
        if(flight.autopilot){
            if(!(pilot() instanceof PlayerEntity)||grounded||!flight.running||flight.engine<=0||flight.fuel<=0||flight.wreck)stopAutopilot();
            else navigation.step(this);
        }
        blockImpactSpeed=0;
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
        if(!flight.wreck&&flight.hull<=0)destroy(false);
        var allowedPose=HelicopterCollision.limitRotation(this,previousPose,
                new HelicopterCollision.Pose(flight.yaw,flight.pitch,flight.roll));
        if(Math.abs(MathHelper.wrapDegrees(allowedPose.yaw()-flight.yaw))>.001)flight.yawRate=0;
        flight.yaw=allowedPose.yaw();flight.pitch=allowedPose.pitch();flight.roll=allowedPose.roll();
        setYaw((float)flight.yaw);
        Vec3d before=getPos(),velocity=new Vec3d(flight.vx,flight.vy,flight.vz);setVelocity(velocity);
        move(MovementType.SELF,velocity);Vec3d moved=getPos().subtract(before);
        // 向下真实触地后强制放架；不把撞到天花板或候选大盒擦地当成落地。
        if(verticalCollision&&velocity.y<0)flight.gear=true;
        if(isOnGround()&&!hasBlockSupport())setOnGround(false);
        if(!flight.wreck){
            HelicopterCollision.spinningContact(this);
            double minimum=blockImpactPart==HelicopterCollision.Part.LANDING_GEAR?.12:.02;
            // 静止接地的重力贴地不算事故；旋翼自身转动的接触速度仍正常结算。
            if(blockImpactSpeed>minimum&&collisionCooldown==0
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
                    if(relative>ENTITY_DAMAGE_SPEED&&collisionCooldown==0){collide(contact.part(),relative);if(flight.wreck)break;}
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
        if(horizontalCollision){if(Math.abs(velocity.x-moved.x)>.001)flight.vx=0;if(Math.abs(velocity.z-moved.z)>.001)flight.vz=0;}
        if(verticalCollision)flight.vy=0;
        // 碰撞后当 tick 立即清除残骸水平动量，避免下一 tick 接地前再滑动一段。
        if(flight.wreck&&isOnGround()){flight.vx=0;flight.vz=0;}
        setVelocity(flight.vx,flight.vy,flight.vz);fallDistance=0;
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
        if(age%2==0)dataTracker.set(STATUS,saveFlight());
    }
    /** 无驾驶员时沿用本机最后模式，避免跳机改变事故和弃机物理。 */
    private HeliConfig flightConfig(){
        return HeliConfig.forMode(HeliConfig.Difficulty.values()[Math.clamp(flight.difficultyMode,0,2)]);
    }
    /** 服务端事故按实际接触部位扣耐久并限流；仅机舱/动力/油箱的高速撞击沿用原灾难阈值。 */
    void collide(HelicopterCollision.Part part,double impact){
        if(collisionCooldown>0||flight.wreck)return;
        collisionCooldown=10;
        boolean body=part==HelicopterCollision.Part.CABIN||part==HelicopterCollision.Part.ENGINE||part==HelicopterCollision.Part.FUEL_TANK;
        if(body&&FlightPhysics.catastrophic(impact,flight,flightConfig())){destroy(true);return;}
        damagePart(part,impact*(flight.hardcoreMode?30:14),false);
    }
    /** 统一碰撞与攻击的分部耐久，尾桨受击不再误扣引擎或主旋翼。 */
    private void damagePart(HelicopterCollision.Part part,double damage,boolean explosive){
        switch(part){
            case CABIN -> flight.hull-=damage;
            case ENGINE -> {flight.engine=Math.max(0,flight.engine-damage);flight.hull-=damage*.25;}
            case FUEL_TANK -> {flight.tank=Math.max(0,flight.tank-damage);flight.hull-=damage*.25;}
            case MAIN_ROTOR -> {flight.rotor=Math.max(0,flight.rotor-damage);flight.hull-=damage*.1;}
            case TAIL_ROTOR -> {flight.tail=Math.max(0,flight.tail-damage);flight.hull-=damage*.1;}
            case TAIL_BOOM -> {flight.tail=Math.max(0,flight.tail-damage*.5);flight.hull-=damage*.5;}
            case LANDING_GEAR -> {flight.hull-=damage*.35;if(!flight.gear)flight.tank=Math.max(0,flight.tank-damage*.5);}
        }
        if(flight.hull<=0)destroy(explosive);
    }
    /** 近战与投射物以真实射线命中部位定位；来源位置只用于非定向环境或爆炸伤害。 */
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
    /** 残骸复用实体并持久化，不会凭空消失；货物只在解体时掉落一次。 */
    private void destroy(boolean explosive){
        if(flight.wreck)return;
        // 必须是事故发生时的真实驾驶员且本 tick 曾离地；乘客、停机地面损坏不算驾驶坠机。
        if(airborneTicks>0&&pilot() instanceof net.minecraft.server.network.ServerPlayerEntity driver)
            org.DJB.no_24_heli.registry.ModAdvancements.grant(driver,
                    org.DJB.no_24_heli.registry.ModAdvancements.LEGEND_NEVER_FALLS);
        flight.wreck=true;flight.running=false;flight.rope=false;flight.clearInputs();
        var occupants=List.copyOf(getPassengerList());removeAllPassengers();
        if(explosive)for(Entity occupant:occupants)occupant.damage(getDamageSources().genericKill(),Float.MAX_VALUE);
        ItemScatterer.spawn(getWorld(),this,cargo);cargo.clear();
        if(explosive){boolean blocks=HeliConfig.INSTANCE.explosionBlockDamage;getWorld().createExplosion(this,getX(),getY()+.5,getZ(),(float)HeliConfig.INSTANCE.explosionPower,blocks,blocks?World.ExplosionSourceType.TNT:World.ExplosionSourceType.NONE);}
        dataTracker.set(STATUS,saveFlight());
    }
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
        if(stack.isOf(Items.LEAD)&&pilot() instanceof PlayerEntity&&getPassengerList().size()<2){
            for(VillagerEntity villager:getWorld().getEntitiesByClass(VillagerEntity.class,getBoundingBox().expand(5),v->!v.hasVehicle()))if(villager.startRiding(this,true)){return ActionResult.CONSUME;}
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
        return player.startRiding(this)?ActionResult.CONSUME:ActionResult.FAIL;
    }
    public void openMenu(PlayerEntity player){
        if(flight.wreck)return;
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory((id,inv,p)->new HeliScreenHandler(id,inv,cargo,new PropertyDelegate(){
            public int size(){return 8;}
            public int get(int i){return switch(i){case 0->(int)flight.fuel;case 1->(int)HeliConfig.INSTANCE.fuelCapacity;case 2->(int)flight.rotor;case 3->(int)flight.tail;case 4->(int)flight.engine;case 5->(int)flight.tank;case 6->(int)flight.hull;default->(int)(flight.rpm*100);};}
            public void set(int i,int value){}
        },this),Text.translatable("container.no_24_heli.service")));
    }
    @Override protected void updatePassengerPosition(Entity passenger,PositionUpdater updater){
        // 面向本地 +Z 机头时，左驾驶席位于 +X；主副驾和客户端眼位统一为 0.38 格偏移。
        double side=isPilot(passenger)?.38:-.38;
        // 人物髋部比脚位高约 0.70 格，脚位 0.10 对齐座垫上表面；前后对齐座垫中心。
        Vec3d offset=new Vec3d(side,.10,-.12+SEAT_FORWARD_OFFSET).rotateY((float)-Math.toRadians(getYaw()));
        updater.accept(passenger,getX()+offset.x,getY()+offset.y,getZ()+offset.z);passenger.fallDistance=0;
    }
    @Override public Vec3d updatePassengerForDismount(LivingEntity passenger){
        for(double side:new double[]{2.4,-2.4}){Vec3d candidate=getPos().add(new Vec3d(side,.15,0).rotateY((float)-Math.toRadians(getYaw())));if(getWorld().isSpaceEmpty(passenger,passenger.getBoundingBox().offset(candidate.subtract(passenger.getPos()))))return candidate;}
        return getPos().add(0,2.8,0);
    }
    @Override public void updateTrackedPositionAndAngles(double x,double y,double z,float yaw,float pitch,int steps){lerpX=x;lerpY=y;lerpZ=z;lerpYaw=yaw;lerpTicks=Math.max(1,steps);}
    @Override public void onTrackedDataSet(TrackedData<?> data){super.onTrackedDataSet(data);if(STATUS.equals(data)&&getWorld().isClient)loadFlight(dataTracker.get(STATUS));}

    /** 存档版本 3：显式字段，旧存档缺少部件字段时默认完整，损毁状态绝不重置。 */
    private NbtCompound saveFlight(){
        NbtCompound n=new NbtCompound();n.putInt("Version",4);
        // 供主副驾联动摇杆和远端握杆姿态读取；读档结束仍由 clearInputs 清除，不恢复控制意图。
        n.putFloat("CyclicPitch",flight.stickPitch);n.putFloat("CyclicRoll",flight.stickRoll);
        n.putFloat("RotorAngle",rotorAngle);
        n.putInt("RopeCarrierId",activeRope!=null&&!activeRope.isRemoved()?activeRope.getId():-1);
        n.putBoolean("Searchlight",flight.searchlight&&!flight.wreck);
        n.putFloat("SearchlightYaw",flight.searchlightYaw);n.putFloat("SearchlightPitch",flight.searchlightPitch);
        n.putInt("DifficultyMode",flight.difficultyMode);n.putInt("AutoPhase",flight.autoPhase);
        n.putBoolean("LandingBlocked",flight.landingBlocked);
        n.putBoolean("TakeoffPowerLow",flight.takeoffPowerLow);
        n.putInt("RadioTrack",flight.radioTrack);n.putInt("RadioRevision",flight.radioRevision);n.putLong("RadioStarted",flight.radioStartedTick);
        n.putBoolean("Waypoint",flight.waypoint);n.putDouble("WaypointX",flight.waypointX);n.putDouble("WaypointZ",flight.waypointZ);
        n.putBoolean("Autopilot",flight.autopilot);n.putBoolean("Restricted",flight.restricted);n.putBoolean("HardcoreMode",flight.hardcoreMode);n.putInt("AbandonedTicks",flight.abandonedTicks);
        n.putDouble("Rpm",flight.rpm);n.putDouble("Collective",flight.collective);n.putDouble("Pitch",flight.pitch);n.putDouble("Roll",flight.roll);n.putDouble("Yaw",flight.yaw);n.putDouble("YawRate",flight.yawRate);
        n.putDouble("Fuel",flight.fuel);n.putDouble("Rotor",flight.rotor);n.putDouble("Tail",flight.tail);n.putDouble("Engine",flight.engine);n.putDouble("Tank",flight.tank);n.putDouble("Hull",flight.hull);
        n.putDouble("Vx",flight.vx);n.putDouble("Vy",flight.vy);n.putDouble("Vz",flight.vz);n.putDouble("TargetAltitude",flight.targetAltitude);n.putDouble("TargetSpeed",flight.targetSpeed);
        if(ropeCopilot!=null)n.putUuid("RopeCopilot",ropeCopilot);n.putBoolean("Running",flight.running);n.putBoolean("Armed",flight.armed);n.putBoolean("Altitude",flight.altitudeHold);n.putBoolean("Trim",flight.trim);n.putBoolean("Cruise",flight.cruise);n.putBoolean("Gear",flight.gear);n.putBoolean("Wreck",flight.wreck);n.putBoolean("Rope",flight.rope);n.putInt("Paint",flight.paint);n.putInt("RopeLength",ropeLength);return n;
    }
    private static double number(NbtCompound n,String key,double fallback,double min,double max){double v=n.contains(key)?n.getDouble(key):fallback;return Double.isFinite(v)?FlightPhysics.clamp(v,min,max):fallback;}
    private void loadFlight(NbtCompound n){
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
        flight.rpm=number(n,"Rpm",0,0,1);flight.collective=number(n,"Collective",0,0,1);flight.pitch=number(n,"Pitch",0,-60,60);flight.roll=number(n,"Roll",0,-60,60);flight.yaw=number(n,"Yaw",getYaw(),-360,360);flight.yawRate=number(n,"YawRate",0,-20,20);
        flight.fuel=number(n,"Fuel",0,0,100000);flight.rotor=number(n,"Rotor",100,0,100);flight.tail=number(n,"Tail",100,0,100);flight.engine=number(n,"Engine",100,0,100);flight.tank=number(n,"Tank",100,0,100);flight.hull=number(n,"Hull",100,0,100);
        flight.vx=number(n,"Vx",0,-3,3);flight.vy=number(n,"Vy",0,-3,3);flight.vz=number(n,"Vz",0,-3,3);flight.targetAltitude=number(n,"TargetAltitude",getY(),-2048,2048);flight.targetSpeed=number(n,"TargetSpeed",0,0,3);
        ropeCopilot=n.containsUuid("RopeCopilot")?n.getUuid("RopeCopilot"):null;flight.running=n.getBoolean("Running");flight.armed=n.getBoolean("Armed");flight.altitudeHold=n.getBoolean("Altitude");flight.trim=n.contains("Trim")?n.getBoolean("Trim"):!flight.hardcoreMode;flight.cruise=n.getBoolean("Cruise");flight.gear=!n.contains("Gear")||n.getBoolean("Gear");flight.wreck=n.getBoolean("Wreck");flight.rope=n.getBoolean("Rope");flight.paint=Math.clamp(n.contains("Paint")?n.getInt("Paint"):FlightState.POLICE_PAINT,0,FlightState.POLICE_PAINT);ropeLength=Math.clamp(n.getInt("RopeLength"),0,24);
    }
    /**
     * K为可撤销的起降任务：地面开始二十格起飞，空中尝试垂直降落，再按一次取消。
     * 降落前对机体中心和四角分别向下探测，拒绝水面、悬崖边以及高低不平的支撑面；
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
            Double surface=null;
            for(double[] offset:new double[][]{{0,0},{-1.5,-1.5},{1.5,-1.5},{-1.5,1.5},{1.5,1.5}}){
                Vec3d start=getPos().add(offset[0],.05,offset[1]);
                var hit=getWorld().raycast(new RaycastContext(start,new Vec3d(start.x,getWorld().getBottomY(),start.z),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.ANY,this));
                if(hit.getType()==HitResult.Type.MISS||!getWorld().getFluidState(hit.getBlockPos()).isEmpty()||(surface!=null&&Math.abs(surface-hit.getPos().y)>.25)){message(player,"auto_unsafe");return;}
                surface=hit.getPos().y;
            }
            target=surface-.2;
        }
        stopAutopilot();flight.autoPhase=ground?1:3;flight.autoX=getX();flight.autoZ=getZ();flight.autoY=target;
        // 起飞接管沿用当前总距，预转期间和离地后均由高度控制渐变调整。
        flight.running=true;flight.gear=true;flight.clearInputs();flight.altitudeAdjusting=false;
        updateLandingObstruction();
        message(player,ground?"auto_takeoff":"auto_landing");
    }

    /**
     * 功能：自动降落每 tick 复核实体占用，首次受阻捕获暂停高度；实体离开后恢复原 autoY 目标。
     * 引用 MC API：net.minecraft.entity.Entity#getY 获取本机高度；实体查询交给 HelicopterCollision。
     * 端侧/线程：服务端主线程；参数/返回：无；副作用：更新同步警告、暂停高度并制止向下速度。
     * 排查：不能覆盖 autoY，否则通道清空后会停在半空；取消任务统一清除 landingBlocked，防止灯和语音残留。
     */
    private void updateLandingObstruction(){
        boolean blocked=flight.autoPhase==3&&HelicopterCollision.landingObstructed(this,flight.autoY);
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
    /** 第二次独立按下必须发生在 60 tick 内；按住 Shift 不会连续确认。服务器最终裁决。 */
    public void requestDismount(PlayerEntity player){
        if(!hasPassenger(player))return;
        long now=getWorld().getTime();Long until=exitWarnings.get(player.getUuid());
        if(isOnGround()||hasBlockSupport()||isTouchingWater()||(until!=null&&now<=until)){
            exitWarnings.remove(player.getUuid());player.stopRiding();
            // 只有穿戴新伞包的跳伞者继承机速；原有下机和确认流程保持不变。
            if(!isOnGround()&&!isTouchingWater()&&player.getEquippedStack(EquipmentSlot.CHEST)
                    .isOf(org.DJB.no_24_heli.registry.ModParachutes.PARACHUTE)){
                player.setVelocity(flight.vx,flight.vy,flight.vz);player.velocityModified=true;
            }
        }else{exitWarnings.put(player.getUuid(),now+60);message(player,"exit_warning");}
    }
    @Override protected void writeCustomDataToNbt(NbtCompound n){n.put("Flight",saveFlight());var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);for(int i=0;i<19;i++)items.set(i,cargo.getStack(i));Inventories.writeNbt(n,items,getRegistryManager());}
    @Override protected void readCustomDataFromNbt(NbtCompound n){loadFlight(n.getCompound("Flight"));var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);Inventories.readNbt(n,items,getRegistryManager());for(int i=0;i<19;i++)cargo.setStack(i,items.get(i));// 飞行任务不跨重载恢复，电台也默认静音；导航点和机体数据仍保留。
        flight.cancelAutoFlight();flight.radioTrack=-1;flight.autopilot=false;flight.restricted=false;
        if(n.getCompound("Flight").getInt("Version")<4)flight.cruise=false;
        flight.clearInputs();flight.rope=false;ropeLength=0;dataTracker.set(STATUS,saveFlight());}
}
