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
    public final FlightState flight=new FlightState();
    public final SimpleInventory cargo=new SimpleInventory(19);
    public float rotorAngle,previousRotorAngle;
    public float visualPitch,visualRoll,gearExtension=1;
    public int ropeLength;
    private long lastInput=-100,lastActions=-100,lastEngine=-100;
    private int collisionCooldown,repairCooldown;
    /** 连续离开方块支撑三 tick 后才启用实体事故，防止放置接地前误判。 */
    private int airborneTicks;
    private boolean entityContactsEnabled;
    private double lerpX,lerpY,lerpZ;
    private float lerpYaw;
    private int lerpTicks;
    public HelicopterEntity(EntityType<?> type,World world){super(type,world);intersectionChecked=true;applyDifficulty(HeliConfig.INSTANCE.hardcore());}
    @Override protected void initDataTracker(DataTracker.Builder builder){builder.add(STATUS,new NbtCompound());}
    @Override public boolean canHit(){return true;}
    @Override public boolean isCollidable(){return !isRemoved();}
    @Override public boolean isPushable(){return false;}
    /** 同时过滤原版移动碰撞与自定义事故检测，不能只过滤爆炸后的伤害。 */
    @Override public boolean collidesWith(Entity other){return entityContactsEnabled&&crashObstacle(other)&&super.collidesWith(other);}
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
    public void applyDifficulty(boolean hardcore){
        flight.hardcoreMode=hardcore;flight.trim=!hardcore;flight.altitudeHold=!hardcore;flight.cruise=!hardcore;
        flight.targetAltitude=getY();flight.targetSpeed=Math.hypot(flight.vx,flight.vz);
    }
    @Override protected boolean canAddPassenger(Entity passenger){return !flight.wreck&&getPassengerList().size()<2;}
    public boolean isPilot(Entity e){return getFirstPassenger()==e;}
    @Override public LivingEntity getControllingPassenger(){return null;}

    /** 操作权限只属于第一座乘员；副驾驶可以开货仓和使用已部署绳索。 */
    public void control(PlayerEntity player,ControlPayload p){
        if(!p.valid()||!hasPassenger(player)||flight.wreck)return;
        long tick=getWorld().getTime();
        if((p.actions()&ControlPayload.MENU)!=0&&tick-lastActions>=5){openMenu(player);lastActions=tick;}
        if((p.actions()&ControlPayload.EXIT_ROPE)!=0&&flight.rope&&tick-lastActions>=5){rappel(player);lastActions=tick;return;}
        if(!isPilot(player))return;
        // 连续轴量每 tick 接收一次，离散按键另行限流，避免同 tick 的中性包吞掉开关动作。
        if(lastInput!=tick){
        lastInput=tick;
        flight.cyclicPitch=(float)FlightPhysics.clamp(p.pitch(),-1,1);flight.cyclicRoll=(float)FlightPhysics.clamp(p.roll(),-1,1);
        flight.pedal=(float)FlightPhysics.clamp(p.pedal(),-1,1);flight.collectiveInput=(float)FlightPhysics.clamp(p.collective(),-1,1);
        }
        int action=p.actions();
        if((action&ControlPayload.ENGINE)!=0&&tick-lastEngine>=20){
            lastEngine=tick;
            if(flight.running){flight.running=false;flight.altitudeHold=false;flight.cruise=false;}
            else if(flight.fuel>0&&flight.engine>0&&flight.rotor>0){flight.running=true;flight.targetAltitude=getY();if(!flight.armed&&!HeliConfig.INSTANCE.hardcore()){flight.trim=true;flight.altitudeHold=true;flight.cruise=true;}if(isOnGround())flight.collective=0;message(player,"start");}
            else message(player,"start_failed");
        }
        if(tick-lastActions<5)return;
        if((action&ControlPayload.ALTITUDE)!=0){flight.altitudeHold=!flight.altitudeHold&&flight.running&&flight.armed;flight.targetAltitude=getY();lastActions=tick;}
        if((action&ControlPayload.TRIM)!=0){flight.trim=!flight.trim;lastActions=tick;}
        if((action&ControlPayload.CRUISE)!=0){flight.cruise=!flight.cruise&&flight.running&&flight.armed;flight.targetSpeed=Math.hypot(flight.vx,flight.vz);lastActions=tick;}
        if((action&ControlPayload.GEAR)!=0){flight.gear=!flight.gear;lastActions=tick;}
        if((action&ControlPayload.ROPE)!=0){
            if(flight.rope){flight.rope=false;ropeLength=0;}
            else if(safeHover()){flight.rope=true;ropeLength=(int)Math.ceil(heightAboveGround());
                if(getPassengerList().size()>1&&getPassengerList().get(1) instanceof VillagerEntity villager)rappel(villager);
            }else message(player,"rope_unsafe");lastActions=tick;
        }
    }
    private void message(PlayerEntity p,String key){p.sendMessage(Text.translatable("message.no_24_heli."+key),true);}
    public double heightAboveGround(){
        Vec3d start=getPos().add(0,.05,0);
        var hit=getWorld().raycast(new RaycastContext(start,start.add(0,-25,0),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.ANY,this));
        return hit.getType()==HitResult.Type.MISS?26:start.y-hit.getPos().y;
    }
    private boolean safeHover(){double h=heightAboveGround();return !flight.wreck&&h>1&&h<=24&&Math.hypot(flight.vx,flight.vz)<.10&&Math.abs(flight.vy)<.075;}
    private void rappel(Entity passenger){
        if(!safeHover())return;
        RopeSeatEntity seat=new RopeSeatEntity(No_24_heli.ROPE,getWorld());seat.setPosition(getX(),getY()-.5,getZ());
        if(!getWorld().isSpaceEmpty(seat,seat.getBoundingBox()))return;
        if(getWorld().spawnEntity(seat)){passenger.stopRiding();passenger.startRiding(seat,true);passenger.fallDistance=0;}
    }
    @Override public void tick(){
        super.tick();previousRotorAngle=rotorAngle;rotorAngle+=(float)(flight.rpm*65);
        if(getWorld().isClient){
            gearExtension=MathHelper.lerp(.15f,gearExtension,flight.gear?1:0);
            visualPitch=MathHelper.lerp(.2f,visualPitch,(float)flight.pitch);visualRoll=MathHelper.lerp(.2f,visualRoll,(float)flight.roll);
            if(lerpTicks>0){setPosition(getX()+(lerpX-getX())/lerpTicks,getY()+(lerpY-getY())/lerpTicks,getZ()+(lerpZ-getZ())/lerpTicks);setYaw(getYaw()+MathHelper.wrapDegrees(lerpYaw-getYaw())/lerpTicks);lerpTicks--;}
            return;
        }
        if(collisionCooldown>0)collisionCooldown--;if(repairCooldown>0)repairCooldown--;
        if(getWorld().getTime()-lastInput>10||!(getFirstPassenger() instanceof PlayerEntity))flight.clearInputs();

        if(!flight.wreck&&isOnGround()&&age%20==0){
            var fuel=cargo.getStack(0);double amount=fuel.isOf(No_24_heli.KEROSENE)?150:fuel.isOf(No_24_heli.CRUDE)?80:0;
            if(amount>0&&flight.fuel<=HeliConfig.INSTANCE.fuelCapacity-amount){flight.fuel+=amount;fuel.decrement(1);cargo.markDirty();}
            // 停机坪自动从货仓中的燃料补给；必须停机，防止地面维护与起飞冲突。
            if(!flight.running&&getWorld().getBlockState(getBlockPos().down()).isOf(No_24_heli.HELIPAD)){
                for(int i=1;i<cargo.size();i++)if(cargo.getStack(0).isEmpty()&&(cargo.getStack(i).isOf(No_24_heli.KEROSENE)||cargo.getStack(i).isOf(No_24_heli.CRUDE))){cargo.setStack(0,cargo.removeStack(i));break;}
            }
        }
        if(flight.hardcoreMode!=HeliConfig.INSTANCE.hardcore())applyDifficulty(HeliConfig.INSTANCE.hardcore());
        boolean grounded=isOnGround()||hasBlockSupport();
        airborneTicks=grounded?0:Math.min(3,airborneTicks+1);
        entityContactsEnabled=airborneTicks>=3&&!flight.wreck;
        FlightPhysics.handleAbandonment(flight,HeliConfig.INSTANCE,grounded,getFirstPassenger() instanceof PlayerEntity,getY());
        FlightPhysics.step(flight,HeliConfig.INSTANCE,grounded,getWorld().hasRain(getBlockPos()),getY(),getWorld().getTime());
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
            // 玩家永不触发实体事故；地面、刚放置、乘员及绳降实体全部排除。
            if(!flight.wreck&&entityContactsEnabled&&!isOnGround()){
                for(Entity other:getWorld().getOtherEntities(this,getBoundingBox(),this::crashObstacle)){
                    double relative=velocity.subtract(other.getVelocity()).length();
                    if(relative>.02){destroy(true);other.damage(getDamageSources().flyIntoWall(),(float)(relative*12));break;}
                }
            }
            if(isTouchingWater()){flight.running=false;flight.engine=Math.max(0,flight.engine-.25);}
            if(flight.rope&&!safeHover()){flight.rope=false;ropeLength=0;}
        }
        if(horizontalCollision){if(Math.abs(velocity.x-moved.x)>.001)flight.vx=0;if(Math.abs(velocity.z-moved.z)>.001)flight.vz=0;}
        if(verticalCollision)flight.vy=0;
        setVelocity(flight.vx,flight.vy,flight.vz);fallDistance=0;
        if(age%2==0)dataTracker.set(STATUS,saveFlight());
    }
    private void collide(double impact){
        if(FlightPhysics.catastrophic(impact,flight,HeliConfig.INSTANCE)){destroy(true);return;}
        double damage=impact*(HeliConfig.INSTANCE.hardcore()?30:14);
        flight.hull-=damage;flight.rotor=Math.max(0,flight.rotor-damage*.4);flight.engine=Math.max(0,flight.engine-damage*.2);
        if(!flight.gear)flight.tank=Math.max(0,flight.tank-damage);
        if(flight.hull<=0)destroy(false);
    }
    /** 部位按攻击来源在机体坐标系中的高度和前后位置区分。 */
    @Override public boolean damage(DamageSource source,float amount){
        if(getWorld().isClient||isInvulnerableTo(source)||flight.wreck||!Float.isFinite(amount))return false;
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
        if(player.isSneaking()){if(isOnGround()||hasPassenger(player))openMenu(player);return ActionResult.CONSUME;}
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

    /** 存档版本 2：显式字段，旧存档缺少部件字段时默认完整，损毁状态绝不重置。 */
    private NbtCompound saveFlight(){
        NbtCompound n=new NbtCompound();n.putInt("Version",2);n.putBoolean("HardcoreMode",flight.hardcoreMode);n.putInt("AbandonedTicks",flight.abandonedTicks);
        n.putDouble("Rpm",flight.rpm);n.putDouble("Collective",flight.collective);n.putDouble("Pitch",flight.pitch);n.putDouble("Roll",flight.roll);n.putDouble("Yaw",flight.yaw);n.putDouble("YawRate",flight.yawRate);
        n.putDouble("Fuel",flight.fuel);n.putDouble("Rotor",flight.rotor);n.putDouble("Tail",flight.tail);n.putDouble("Engine",flight.engine);n.putDouble("Tank",flight.tank);n.putDouble("Hull",flight.hull);
        n.putDouble("Vx",flight.vx);n.putDouble("Vy",flight.vy);n.putDouble("Vz",flight.vz);n.putDouble("TargetAltitude",flight.targetAltitude);n.putDouble("TargetSpeed",flight.targetSpeed);
        n.putBoolean("Running",flight.running);n.putBoolean("Armed",flight.armed);n.putBoolean("Altitude",flight.altitudeHold);n.putBoolean("Trim",flight.trim);n.putBoolean("Cruise",flight.cruise);n.putBoolean("Gear",flight.gear);n.putBoolean("Wreck",flight.wreck);n.putBoolean("Rope",flight.rope);n.putInt("Paint",flight.paint);n.putInt("RopeLength",ropeLength);return n;
    }
    private static double number(NbtCompound n,String key,double fallback,double min,double max){double v=n.contains(key)?n.getDouble(key):fallback;return Double.isFinite(v)?FlightPhysics.clamp(v,min,max):fallback;}
    private void loadFlight(NbtCompound n){
        flight.hardcoreMode=n.contains("HardcoreMode")?n.getBoolean("HardcoreMode"):!HeliConfig.INSTANCE.hardcore();
        flight.abandonedTicks=Math.clamp(n.getInt("AbandonedTicks"),0,1000000);
        flight.rpm=number(n,"Rpm",0,0,1);flight.collective=number(n,"Collective",0,0,1);flight.pitch=number(n,"Pitch",0,-60,60);flight.roll=number(n,"Roll",0,-60,60);flight.yaw=number(n,"Yaw",getYaw(),-360,360);flight.yawRate=number(n,"YawRate",0,-20,20);
        flight.fuel=number(n,"Fuel",0,0,100000);flight.rotor=number(n,"Rotor",100,0,100);flight.tail=number(n,"Tail",100,0,100);flight.engine=number(n,"Engine",100,0,100);flight.tank=number(n,"Tank",100,0,100);flight.hull=number(n,"Hull",100,0,100);
        flight.vx=number(n,"Vx",0,-3,3);flight.vy=number(n,"Vy",0,-3,3);flight.vz=number(n,"Vz",0,-3,3);flight.targetAltitude=number(n,"TargetAltitude",getY(),-2048,2048);flight.targetSpeed=number(n,"TargetSpeed",0,0,3);
        flight.running=n.getBoolean("Running");flight.armed=n.getBoolean("Armed");flight.altitudeHold=n.getBoolean("Altitude");flight.trim=n.contains("Trim")?n.getBoolean("Trim"):!HeliConfig.INSTANCE.hardcore();flight.cruise=n.getBoolean("Cruise");flight.gear=!n.contains("Gear")||n.getBoolean("Gear");flight.wreck=n.getBoolean("Wreck");flight.rope=n.getBoolean("Rope");flight.paint=Math.clamp(n.getInt("Paint"),0,No_24_heli.PAINTS.length-1);ropeLength=Math.clamp(n.getInt("RopeLength"),0,24);
    }
    @Override protected void writeCustomDataToNbt(NbtCompound n){n.put("Flight",saveFlight());var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);for(int i=0;i<19;i++)items.set(i,cargo.getStack(i));Inventories.writeNbt(n,items,getRegistryManager());}
    @Override protected void readCustomDataFromNbt(NbtCompound n){loadFlight(n.getCompound("Flight"));var items=net.minecraft.util.collection.DefaultedList.ofSize(19,ItemStack.EMPTY);Inventories.readNbt(n,items,getRegistryManager());for(int i=0;i<19;i++)cargo.setStack(i,items.get(i));flight.clearInputs();flight.rope=false;ropeLength=0;dataTracker.set(STATUS,saveFlight());}
}
