package org.DJB.no_24_heli;

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
import org.DJB.no_24_heli.flight.*;
import java.util.List;

/** 服务端权威载具：负责物理、碰撞、交互、乘员、保存以及客户端状态快照。 */
public class HelicopterEntity extends Entity {
    private static final TrackedData<NbtCompound> STATUS=DataTracker.registerData(HelicopterEntity.class,TrackedDataHandlerRegistry.NBT_COMPOUND);
    private final NavigationPilot navigation=new NavigationPilot();
    public final FlightState flight=new FlightState();
    public final SimpleInventory cargo=new SimpleInventory(19);
    public float rotorAngle,previousRotorAngle;
    public float visualPitch,visualRoll,gearExtension=1;
    /** 保存上一 tick 的第二级平滑结果，渲染帧再次插值，避免 20 Hz 阶梯跳变。 */
    public float previousVisualPitch,previousVisualRoll,previousGearExtension=1;
    private float smoothPitch,smoothRoll;
    private final java.util.Map<java.util.UUID,Long> exitWarnings=new java.util.HashMap<>();
    public int ropeLength;
    private long lastRadio=-100;
    private long lastInput=-100,lastActions=-100,lastEngine=-100;
    private int collisionCooldown,repairCooldown,entityImpactCooldown;
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
    @Override public boolean isCollidable(){return !isRemoved();}
    @Override public boolean isPushable(){return false;}
    /** 实体撞击单独计算，避免原版把动物阻挡误报成撞墙并直接炸机。 */
    @Override public boolean collidesWith(Entity other){return false;}
    private boolean crashObstacle(Entity other){
        return !(other instanceof PlayerEntity)&&!other.isSpectator()&&!isConnectedThroughVehicle(other)
                &&!(other instanceof RopeSeatEntity)&&!(other.getRootVehicle() instanceof RopeSeatEntity)
                &&(other.isCollidable()||other instanceof LivingEntity);
    }
    /** 只查询方块支撑，避免把脚边的玩家或动物误当作地面。 */
    private boolean hasBlockSupport(){
        var b=getBoundingBox();var feet=new Box(b.minX+.001,b.minY-.08,b.minZ+.001,b.maxX-.001,b.minY+.001,b.maxZ-.001);
        return getWorld().getBlockCollisions(this,feet).iterator().hasNext();
    }
    /** 难度切换只重设辅助默认状态，保留当前速度、燃油、损伤与总距。 */
    public void applyDifficulty(HeliConfig.Difficulty mode){
        stopAutopilot();flight.autoPhase=0;
        boolean hardcore=mode!=HeliConfig.Difficulty.CASUAL;
        flight.difficultyMode=mode.ordinal();flight.hardcoreMode=hardcore;flight.trim=!hardcore;flight.altitudeHold=!hardcore;flight.cruise=false;
        flight.targetAltitude=getY();flight.targetSpeed=Math.hypot(flight.vx,flight.vz);
    }
    @Override protected boolean canAddPassenger(Entity passenger){return !flight.wreck&&getPassengerList().size()<2;}
    public boolean isPilot(Entity e){return getFirstPassenger()==e;}
    /** 任何离座途径都清除旧确认窗口；驾驶员离座时立即停止导航，副驾驶离座不干扰驾驶。 */
    @Override protected void removePassenger(Entity passenger){
        boolean pilot=isPilot(passenger);super.removePassenger(passenger);
        exitWarnings.remove(passenger.getUuid());if(pilot){stopAutopilot();flight.autoPhase=0;}
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
        // 原始输入在写入自动驾驶指令之前判断；任何有效手动轴或辅助切换均交还控制权。
        boolean manual=Math.abs(p.pitch())>.02||Math.abs(p.roll())>.02||Math.abs(p.pedal())>.02||Math.abs(p.collective())>.02;
        if((flight.autopilot||flight.autoPhase!=0)&&(manual||(p.actions()&(ControlPayload.ENGINE|ControlPayload.ALTITUDE|ControlPayload.TRIM|ControlPayload.CRUISE))!=0)){
            stopAutopilot();flight.autoPhase=0;message(player,"pilot_takeover");
        }
        // 所有辅助请求都在服务端挡住；按钮变灰只是提示，不能作为权限边界。
        int assisted=ControlPayload.ALTITUDE|ControlPayload.TRIM|ControlPayload.CRUISE|ControlPayload.AUTOPILOT|ControlPayload.AUTO_FLIGHT;
        if(flight.difficultyMode==2&&(p.actions()&assisted)!=0){message(player,"assists_disabled");return;}
        if((p.actions()&ControlPayload.AUTO_FLIGHT)!=0&&tick-lastActions>=5){lastActions=tick;toggleAutoFlight(player);return;}
        if((p.actions()&ControlPayload.AUTOPILOT)!=0&&tick-lastActions>=5){
            lastActions=tick;
            if(flight.autopilot){stopAutopilot();flight.autoPhase=0;message(player,"pilot_takeover");}
            else if(heightAboveGround()<10){message(player,"autopilot_low");}
            else if(!manual&&flight.waypoint&&flight.running&&flight.armed&&!isOnGround()&&!isTouchingWater()){
                flight.priorTrim=flight.trim;flight.priorAltitude=flight.altitudeHold;flight.priorCruise=flight.cruise;
                navigation.reset();flight.autoPhase=0;flight.autopilot=true;flight.navAligned=false;flight.targetAltitude=getY();message(player,"autopilot_on");
            }else message(player,"autopilot_unavailable");
        }
        // 连续轴量每 tick 接收一次，离散按键另行限流，避免同 tick 的中性包吞掉开关动作。
        if(lastInput!=tick){
        lastInput=tick;
        // 自动驾驶接管期间，中性网络包只刷新在线时间；有效手动输入已在上方解除接管。
        if(!flight.autopilot){
        flight.cyclicPitch=(float)FlightPhysics.clamp(p.pitch(),-1,1);flight.cyclicRoll=(float)FlightPhysics.clamp(p.roll(),-1,1);
        flight.pedal=(float)FlightPhysics.clamp(p.pedal(),-1,1);flight.collectiveInput=(float)FlightPhysics.clamp(p.collective(),-1,1);
        }
        }
        int action=p.actions();
        if((action&ControlPayload.ENGINE)!=0&&tick-lastEngine>=20){
            lastEngine=tick;
            if(flight.running){flight.running=false;flight.altitudeHold=false;flight.cruise=false;}
            else if(flight.fuel>0&&flight.engine>0&&flight.rotor>0){flight.running=true;flight.targetAltitude=getY();if(!flight.armed&&!flight.hardcoreMode){flight.trim=true;flight.altitudeHold=true;flight.cruise=false;}if(isOnGround())flight.collective=0;message(player,"start");}
            else message(player,"start_failed");
        }
        if(tick-lastActions<5)return;
        if((action&ControlPayload.ALTITUDE)!=0){flight.altitudeHold=!flight.altitudeHold&&flight.running&&flight.armed;flight.targetAltitude=getY();lastActions=tick;}
        if((action&ControlPayload.TRIM)!=0){flight.trim=!flight.trim;lastActions=tick;}
        if((action&ControlPayload.CRUISE)!=0){flight.cruise=!flight.cruise&&flight.running&&flight.armed;flight.targetSpeed=Math.hypot(flight.vx,flight.vz);lastActions=tick;}
        if((action&ControlPayload.GEAR)!=0){flight.gear=!flight.gear;lastActions=tick;}
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
    /** 本地实体查询用于渲染与服务端维护，绳端不需要占用直升机座位。 */
    public RopeSeatEntity ropeCarrier(){
        for(var e:getWorld().getOtherEntities(this,getBoundingBox().expand(32),e->e instanceof RopeSeatEntity))
            if(((RopeSeatEntity)e).helicopter()==this)return (RopeSeatEntity)e;
        return null;
    }
    public boolean hasRopeCarrier(){return ropeCarrier()!=null;}
    private RopeSeatEntity ensureRopeCarrier(){
        RopeSeatEntity seat=ropeCarrier();if(seat!=null)return seat;
        seat=new RopeSeatEntity(No_24_heli.ROPE,getWorld());seat.attach(this);
        return getWorld().spawnEntity(seat)?seat:null;
    }
    @Override public void tick(){
        super.tick();previousRotorAngle=rotorAngle;rotorAngle+=(float)(flight.rpm*65);
        if(getWorld().isClient){
            previousGearExtension=gearExtension;previousVisualPitch=visualPitch;previousVisualRoll=visualRoll;
            gearExtension=MathHelper.lerp(.15f,gearExtension,flight.gear?1:0);
            smoothPitch=MathHelper.lerp(.45f,smoothPitch,(float)flight.pitch);smoothRoll=MathHelper.lerp(.45f,smoothRoll,(float)flight.roll);
            visualPitch=MathHelper.lerp(.4f,visualPitch,smoothPitch);visualRoll=MathHelper.lerp(.4f,visualRoll,smoothRoll);
            if(lerpTicks>0){setPosition(getX()+(lerpX-getX())/lerpTicks,getY()+(lerpY-getY())/lerpTicks,getZ()+(lerpZ-getZ())/lerpTicks);setYaw(getYaw()+MathHelper.wrapDegrees(lerpYaw-getYaw())/lerpTicks);lerpTicks--;}
            return;
        }
        // 创造飞行只对当前乘员关闭，不修改 allowFlying，下机仍可照常使用创造飞行。
        for(Entity passenger:getPassengerList())if(passenger instanceof net.minecraft.server.network.ServerPlayerEntity player&&player.getAbilities().flying){
            player.getAbilities().flying=false;player.sendAbilitiesUpdate();
        }
        if(entityImpactCooldown>0)entityImpactCooldown--;
        if(collisionCooldown>0)collisionCooldown--;if(repairCooldown>0)repairCooldown--;
        if(getWorld().getTime()-lastInput>10||!(getFirstPassenger() instanceof PlayerEntity))flight.clearInputs();

        if(!flight.wreck&&isOnGround()&&age%20==0){
            var fuel=cargo.getStack(0);double amount=fuel.isOf(No_24_heli.KEROSENE)?150:fuel.isOf(No_24_heli.CRUDE)?80:0;
            if(amount>0&&flight.fuel<=HeliConfig.INSTANCE.fuelCapacity-amount){flight.fuel+=amount;fuel.decrement(1);cargo.markDirty();}
            // 停机坪自动从货仓中的燃料补给；必须停机，防止地面维护与起飞冲突。
            if(!flight.running&&HelipadBlock.supports(getWorld(),BlockPos.ofFloored(getX(),getY()-.05,getZ()))){
                for(int i=1;i<cargo.size();i++)if(cargo.getStack(0).isEmpty()&&(cargo.getStack(i).isOf(No_24_heli.KEROSENE)||cargo.getStack(i).isOf(No_24_heli.CRUDE))){cargo.setStack(0,cargo.removeStack(i));break;}
            }
        }
        if(getFirstPassenger() instanceof net.minecraft.server.network.ServerPlayerEntity pilot){
            var mode=PlayerFlightSettings.get(pilot.getServer()).mode(pilot.getUuid());
            if(flight.difficultyMode!=mode.ordinal())applyDifficulty(mode);
        }
        HeliConfig physicsConfig=flightConfig();
        boolean grounded=isOnGround()||hasBlockSupport();
        airborneTicks=grounded?0:Math.min(3,airborneTicks+1);
        entityContactsEnabled=airborneTicks>=3&&!flight.wreck;
        // 起降阶段每tick复核动力、驾驶员和模式；不会因为客户端断线而保留无人起飞任务。
        if(flight.autoPhase!=0){
            if(!(getFirstPassenger() instanceof PlayerEntity)||flight.hardcoreMode||!flight.running||flight.engine<=0||flight.rotor<40||flight.fuel<=0||flight.wreck||isTouchingWater())flight.autoPhase=0;
            // 支撑探测会提前约 0.08 格判定近地；必须等碰撞系统确认真正接地后才结束降落并关机。
            else if(flight.autoPhase==3&&isOnGround()){
                flight.autoPhase=0;flight.running=false;flight.collective=0;flight.altitudeHold=false;flight.clearInputs();
                if(getFirstPassenger() instanceof PlayerEntity p)message(p,"auto_landed");
            }else AutoFlightController.step(flight,getX(),getY(),getZ(),grounded);
        }
        if(flight.autopilot){
            if(!(getFirstPassenger() instanceof PlayerEntity)||grounded||!flight.running||flight.engine<=0||flight.fuel<=0||flight.wreck)stopAutopilot();
            else navigation.step(this);
        }
        FlightPhysics.handleAbandonment(flight,physicsConfig,grounded,getFirstPassenger() instanceof PlayerEntity,getY());
        FlightPhysics.step(flight,physicsConfig,grounded,getWorld().hasRain(getBlockPos()),getY(),getWorld().getTime());
        if(!flight.wreck&&flight.hull<=0)destroy(false);
        setYaw((float)flight.yaw);
        Vec3d before=getPos(),velocity=new Vec3d(flight.vx,flight.vy,flight.vz);setVelocity(velocity);
        move(MovementType.SELF,velocity);Vec3d moved=getPos().subtract(before);
        if(!flight.wreck){
            double impact=0;
            if(horizontalCollision){impact=Math.hypot(Math.abs(velocity.x-moved.x)>.001?velocity.x:0,Math.abs(velocity.z-moved.z)>.001?velocity.z:0);}
            if(verticalCollision)impact=Math.max(impact,Math.abs(velocity.y));
            // 地面静止时重力贴地不是碰撞事故；真实撞击才触发耐久与解体。
            if(!grounded&&(horizontalCollision||(verticalCollision&&velocity.y>0))&&impact>.02){destroy(true);}
            else if(impact>.12&&collisionCooldown==0){collisionCooldown=10;collide(impact);}
            // 扫过的包围盒防止高速穿越漏检；实体伤害限流，低速碰撞不反伤飞机。
            if(!flight.wreck&&entityContactsEnabled&&entityImpactCooldown==0){
                var swept=getBoundingBox().union(getBoundingBox().offset(before.subtract(getPos())));
                boolean hitEntity=false;
                for(Entity other:getWorld().getOtherEntities(this,swept,this::crashObstacle)){
                    double relative=velocity.subtract(other.getVelocity()).length();
                    if(relative<=.02)continue;
                    hitEntity=true;other.damage(getDamageSources().flyIntoWall(),(float)(relative*12));
                    if(relative>ENTITY_DAMAGE_SPEED){collide(relative);if(flight.wreck)break;}
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
                var carrier=ropeCarrier();
                if(carrier!=null&&carrier.hasPassengers()){ /* 救援乘员悬挂时允许携带飞行，不自动断绳。 */ }
                else if(!safeHover()){flight.rope=false;ropeLength=0;}
                else ensureRopeCarrier();
            }
        }
        if(horizontalCollision){if(Math.abs(velocity.x-moved.x)>.001)flight.vx=0;if(Math.abs(velocity.z-moved.z)>.001)flight.vz=0;}
        if(verticalCollision)flight.vy=0;
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
            for(Entity occupant:getPassengerList())if(occupant instanceof net.minecraft.server.network.ServerPlayerEntity p){
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,AirspacePayload.of(airspace));
                if(flight.restricted)message(p,"restricted");
            }
        }
        if(age%2==0)dataTracker.set(STATUS,saveFlight());
    }
    /** 无驾驶员时沿用本机最后模式，避免跳机改变事故和弃机物理。 */
    private HeliConfig flightConfig(){
        return HeliConfig.forMode(HeliConfig.Difficulty.values()[Math.clamp(flight.difficultyMode,0,2)]);
    }
    private void collide(double impact){
        if(FlightPhysics.catastrophic(impact,flight,flightConfig())){destroy(true);return;}
        double damage=impact*(flight.hardcoreMode?30:14);
        flight.hull-=damage;flight.rotor=Math.max(0,flight.rotor-damage*.4);flight.engine=Math.max(0,flight.engine-damage*.2);
        if(!flight.gear)flight.tank=Math.max(0,flight.tank-damage);
        if(flight.hull<=0)destroy(false);
    }
    /** 部位按攻击来源在机体坐标系中的高度和前后位置区分。 */
    @Override public boolean damage(DamageSource source,float amount){
        if(getWorld().isClient||isInvulnerableTo(source)||flight.wreck||!Float.isFinite(amount))return false;
        // 近战、投射物以及可归因于机上乘员的攻击均忽略，外部攻击仍正常结算。
        if(source.getAttacker()!=null&&hasPassenger(source.getAttacker())
                ||source.getSource()!=null&&hasPassenger(source.getSource()))return false;
        Vec3d hit=source.getPosition();double damage=Math.max(0,amount)*2;
        if(hit!=null){Vec3d local=hit.subtract(getPos()).rotateY((float)Math.toRadians(getYaw()));
            if(local.y>2){flight.rotor=Math.max(0,flight.rotor-damage);}
            else if(local.z<-.8){flight.tail=Math.max(0,flight.tail-damage);}
            else if(local.x>.4){flight.tank=Math.max(0,flight.tank-damage);}
            else flight.engine=Math.max(0,flight.engine-damage);
        }
        flight.hull-=damage*.35;if(flight.hull<=0)destroy(source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_EXPLOSION));return true;
    }
    /** 残骸复用实体并持久化，不会凭空消失；货物只在解体时掉落一次。 */
    private void destroy(boolean explosive){
        if(flight.wreck)return;
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
            if(isOnGround()&&flight.rpm<.05){flight.paint=i;if(!player.isCreative())stack.decrement(1);}else message(player,"ground_only");return ActionResult.CONSUME;
        }
        if(stack.isOf(Items.LEAD)&&getFirstPassenger() instanceof PlayerEntity&&getPassengerList().size()<2){
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
        int index=getPassengerList().indexOf(passenger);double side=index==0?-.35:.35;
        Vec3d offset=new Vec3d(side,.05,.25).rotateY((float)-Math.toRadians(getYaw()));
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
        n.putInt("DifficultyMode",flight.difficultyMode);n.putInt("AutoPhase",flight.autoPhase);
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
        flight.difficultyMode=n.contains("DifficultyMode")?Math.clamp(n.getInt("DifficultyMode"),0,2):HeliConfig.INSTANCE.difficulty.ordinal();
        flight.autoPhase=Math.clamp(n.getInt("AutoPhase"),0,3);
        flight.radioTrack=n.contains("RadioTrack")?Math.clamp(n.getInt("RadioTrack"),-1,7):-1;
        flight.radioRevision=n.getInt("RadioRevision");flight.radioStartedTick=n.getLong("RadioStarted");
        flight.hardcoreMode=n.contains("HardcoreMode")?n.getBoolean("HardcoreMode"):flight.difficultyMode>0;
        flight.waypoint=n.getBoolean("Waypoint");flight.waypointX=number(n,"WaypointX",0,-29999984,29999984);flight.waypointZ=number(n,"WaypointZ",0,-29999984,29999984);
        flight.autopilot=n.getBoolean("Autopilot");flight.restricted=n.getBoolean("Restricted");
        flight.abandonedTicks=Math.clamp(n.getInt("AbandonedTicks"),0,1000000);
        flight.rpm=number(n,"Rpm",0,0,1);flight.collective=number(n,"Collective",0,0,1);flight.pitch=number(n,"Pitch",0,-60,60);flight.roll=number(n,"Roll",0,-60,60);flight.yaw=number(n,"Yaw",getYaw(),-360,360);flight.yawRate=number(n,"YawRate",0,-20,20);
        flight.fuel=number(n,"Fuel",0,0,100000);flight.rotor=number(n,"Rotor",100,0,100);flight.tail=number(n,"Tail",100,0,100);flight.engine=number(n,"Engine",100,0,100);flight.tank=number(n,"Tank",100,0,100);flight.hull=number(n,"Hull",100,0,100);
        flight.vx=number(n,"Vx",0,-3,3);flight.vy=number(n,"Vy",0,-3,3);flight.vz=number(n,"Vz",0,-3,3);flight.targetAltitude=number(n,"TargetAltitude",getY(),-2048,2048);flight.targetSpeed=number(n,"TargetSpeed",0,0,3);
        flight.running=n.getBoolean("Running");flight.armed=n.getBoolean("Armed");flight.altitudeHold=n.getBoolean("Altitude");flight.trim=n.contains("Trim")?n.getBoolean("Trim"):!flight.hardcoreMode;flight.cruise=n.getBoolean("Cruise");flight.gear=!n.contains("Gear")||n.getBoolean("Gear");flight.wreck=n.getBoolean("Wreck");flight.rope=n.getBoolean("Rope");flight.paint=Math.clamp(n.getInt("Paint"),0,No_24_heli.PAINTS.length-1);ropeLength=Math.clamp(n.getInt("RopeLength"),0,24);
    }
    /**
     * K为可撤销的起降任务：地面开始十格起飞，空中尝试垂直降落，再按一次取消。
     * 降落前对机体中心和四角分别向下探测，拒绝水面、悬崖边以及高低不平的支撑面；
     * 起飞则检查头顶整条包围盒通道，避免在低矮室内自动顶撞天花板。
     */
    private void toggleAutoFlight(PlayerEntity player){
        if(flight.autoPhase!=0){flight.autoPhase=0;flight.clearInputs();flight.targetAltitude=getY();message(player,"pilot_takeover");return;}
        if(flight.hardcoreMode){message(player,"auto_mode_disabled");return;}
        if(flight.fuel<=0||flight.engine<30||flight.rotor<70||isTouchingWater()){message(player,"auto_unsafe");return;}
        boolean ground=isOnGround()||hasBlockSupport();double target=getY()+10;
        if(ground){if(!getWorld().isSpaceEmpty(this,getBoundingBox().stretch(0,10,0))){message(player,"auto_unsafe");return;}}
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
        flight.running=true;flight.gear=true;flight.clearInputs();if(ground)flight.collective=0;
        message(player,ground?"auto_takeoff":"auto_landing");
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
    /** 回收物品仅恢复白名单存档字段，清除运动及临时接管，保留库存/油量/耐久。 */
    public void restorePacked(ItemStack stack){
        var component=stack.get(net.minecraft.component.DataComponentTypes.CUSTOM_DATA);
        if(component!=null)readCustomDataFromNbt(component.copyNbt());
        flight.running=false;flight.armed=false;flight.rpm=0;flight.collective=0;
        flight.vx=flight.vy=flight.vz=flight.pitch=flight.roll=flight.yawRate=0;flight.abandonedTicks=0;
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
        stopAutopilot();navigation.reset();flight.autoPhase=0;flight.waypoint=!p.clear();flight.waypointX=p.x();flight.waypointZ=p.z();
        message(player,p.clear()?"waypoint_clear":"waypoint_set");dataTracker.set(STATUS,saveFlight());
    }
    /** 第二次独立按下必须发生在 60 tick 内；按住 Shift 不会连续确认。服务器最终裁决。 */
    public void requestDismount(PlayerEntity player){
        if(!hasPassenger(player))return;
        long now=getWorld().getTime();Long until=exitWarnings.get(player.getUuid());
        if(isOnGround()||hasBlockSupport()||isTouchingWater()||(until!=null&&now<=until)){
            exitWarnings.remove(player.getUuid());player.stopRiding();
        }else{exitWarnings.put(player.getUuid(),now+60);message(player,"exit_warning");}
    }
    @Override protected void writeCustomDataToNbt(NbtCompound n){n.put("Flight",saveFlight());var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);for(int i=0;i<19;i++)items.set(i,cargo.getStack(i));Inventories.writeNbt(n,items,getRegistryManager());}
    @Override protected void readCustomDataFromNbt(NbtCompound n){loadFlight(n.getCompound("Flight"));var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);Inventories.readNbt(n,items,getRegistryManager());for(int i=0;i<19;i++)cargo.setStack(i,items.get(i));// 飞行任务不跨重载恢复，电台也默认静音；导航点和机体数据仍保留。
        flight.autoPhase=0;flight.radioTrack=-1;flight.autopilot=false;flight.restricted=false;
        if(n.getCompound("Flight").getInt("Version")<4)flight.cruise=false;
        flight.clearInputs();flight.rope=false;ropeLength=0;dataTracker.set(STATUS,saveFlight());}
}
