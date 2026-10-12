package org.DJB.no_24_heli;

import net.minecraft.entity.*;
import net.minecraft.entity.data.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.*;
import java.util.UUID;

/** 一根救援绳承载一人：空闲绳端、攀升、下降与满舱悬挂由服务端管理。 */
public class RopeSeatEntity extends Entity {
    private static final TrackedData<Integer> ANCHOR=DataTracker.registerData(RopeSeatEntity.class,TrackedDataHandlerRegistry.INTEGER);
    private UUID anchorUuid;
    private double length=2.5;
    private int direction,missingTicks;
    private long inputTick=-100,exitUntil=-1;
    private float input;
    private double lerpX,lerpY,lerpZ;
    private int lerpTicks;
    /** 独立绳端沿服务器位置插值，避免摆动绳段和乘员出现20Hz跳变。 */
    @Override public void updateTrackedPositionAndAngles(double x,double y,double z,float yaw,float pitch,int steps){lerpX=x;lerpY=y;lerpZ=z;lerpTicks=Math.max(1,steps);}

    public RopeSeatEntity(EntityType<?> type,World world){super(type,world);}
    @Override protected void initDataTracker(DataTracker.Builder b){b.add(ANCHOR,-1);}
    /** 客户端载具移动包不能改写绳上乘员位置。 */
    @Override public LivingEntity getControllingPassenger(){return null;}
    public HelicopterEntity helicopter(){
        Entity e=getWorld().isClient?getWorld().getEntityById(dataTracker.get(ANCHOR))
                :anchorUuid==null?null:((ServerWorld)getWorld()).getEntity(anchorUuid);
        return e instanceof HelicopterEntity h&&!h.isRemoved()&&!h.flight.wreck?h:null;
    }
    public void attach(HelicopterEntity h){anchorUuid=h.getUuid();dataTracker.set(ANCHOR,h.getId());length=Math.max(.8,Math.min(h.ropeLength,h.heightAboveGround()-.2));setPosition(h.getPos().add(0,-length,0));}
    public boolean board(Entity rider,boolean climbing){
        if(hasPassengers())return false;
        HelicopterEntity h=helicopter();if(h==null||!h.flight.rope)return false;
        if(!climbing){length=.8;setPosition(h.getPos().add(0,-length,0));}
        if(!rider.startRiding(this,true))return false;
        direction=climbing?1:-1;input=0;inputTick=-100;exitUntil=-1;rider.fallDistance=0;return true;
    }
    public void control(PlayerEntity player,ControlPayload p){
        if(!p.valid()||!hasPassenger(player))return;
        input=p.collective();inputTick=getWorld().getTime();
        if((p.actions()&ControlPayload.DISMOUNT)!=0){
            long now=getWorld().getTime();
            if(groundDistance()<=3||isTouchingWater()||(exitUntil>=now)){
                Vec3d velocity=getVelocity();player.stopRiding();player.setVelocity(velocity);exitUntil=-1;
            }else{exitUntil=now+60;player.sendMessage(Text.translatable("message.no_24_heli.rope_exit_warning"),true);}
        }
    }
    private double groundDistance(){
        // 乘员脚部固定在绳端上方0.15格，确认门槛必须使用人物脚部而非载体原点。
        Vec3d feet=getPos().add(0,.15,0);
        var hit=getWorld().raycast(new RaycastContext(feet.add(0,.05,0),feet.add(0,-4,0),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.ANY,this));
        return hit.getType()==HitResult.Type.MISS?5:Math.max(0,feet.y-hit.getPos().y);
    }
    @Override protected void readCustomDataFromNbt(NbtCompound n){
        anchorUuid=n.containsUuid("Anchor")?n.getUuid("Anchor"):null;
        double saved=n.getDouble("Length");length=Double.isFinite(saved)?Math.clamp(saved,.8,24):2.5;direction=Math.clamp(n.getInt("Direction"),-1,1);
    }
    @Override protected void writeCustomDataToNbt(NbtCompound n){if(anchorUuid!=null)n.putUuid("Anchor",anchorUuid);n.putDouble("Length",length);n.putInt("Direction",direction);}
    @Override public void tick(){
        super.tick();if(getWorld().isClient){
            if(lerpTicks>0){setPosition(getX()+(lerpX-getX())/lerpTicks,getY()+(lerpY-getY())/lerpTicks,getZ()+(lerpZ-getZ())/lerpTicks);lerpTicks--;}
            return;
        }
        fallDistance=0;for(Entity e:getPassengerList())e.fallDistance=0;
        HelicopterEntity h=helicopter();
        if(h==null||!h.flight.rope){
            if(!hasPassengers()){discard();return;}
            // 跨区块加载先等待锚点；飞机被毁或绳子消失后沿用原有安全下降。
            if(h==null&&anchorUuid!=null&&missingTicks++<100)return;
            move(MovementType.SELF,new Vec3d(0,-.23,0));
            if(isOnGround()||isTouchingWater()){removeAllPassengers();discard();}return;
        }
        missingTicks=0;dataTracker.set(ANCHOR,h.getId());
        if(!hasPassengers()){length=Math.max(.8,Math.min(h.ropeLength,h.heightAboveGround()-.2));setVelocity(Vec3d.ZERO);setPosition(h.getPos().add(0,-length,0));return;}
        if(getWorld().getTime()-inputTick<10&&Math.abs(input)>.1)direction=input>0?1:-1;
        length=Math.clamp(length+(direction>0?-.14:.23),2.5,24);
        // 保留速度，以重力、阻尼和绳长约束形成摆动；切向速度不会被每 tick 清零。
        Vec3d velocity=getVelocity().multiply(.985).add(0,-.04,0);
        Vec3d predicted=getPos().add(velocity),offset=predicted.subtract(h.getPos());
        if(offset.length()>length){
            Vec3d normal=offset.normalize();predicted=h.getPos().add(normal.multiply(length));
            double radial=velocity.subtract(h.getVelocity()).dotProduct(normal);
            if(radial>0)velocity=velocity.subtract(normal.multiply(radial));
        }
        Vec3d before=getPos();move(MovementType.SELF,predicted.subtract(before));
        if(horizontalCollision)velocity=new Vec3d(0,velocity.y,0);
        if(verticalCollision)velocity=new Vec3d(velocity.x,0,velocity.z);
        setVelocity(velocity);
        for(Entity e:getPassengerList())e.fallDistance=0;
        if(direction<0&&(isOnGround()||isTouchingWater())){removeAllPassengers();return;}
        // 到顶只在确实有空座且距离足够近时转移；满舱保持绳长并继续惯性悬挂。
        if(direction>0&&length<=2.5&&squaredDistanceTo(h)<10&&h.getPassengerList().size()<2){
            Entity rider=getFirstPassenger();if(rider!=null&&rider.startRiding(h,true))rider.fallDistance=0;
        }
    }
    @Override public boolean handleFallDamage(float distance,float multiplier,net.minecraft.entity.damage.DamageSource source){return false;}
    @Override protected void updatePassengerPosition(Entity passenger,PositionUpdater updater){updater.accept(passenger,getX(),getY()+.15,getZ());passenger.fallDistance=0;}
}
