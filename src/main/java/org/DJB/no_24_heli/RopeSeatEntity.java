package org.DJB.no_24_heli;

import net.minecraft.entity.*;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** 绳降座实体每 tick 下移并清零乘员跌落距离；卸载时保留乘骑关系继续下降。 */
public class RopeSeatEntity extends Entity {
    public RopeSeatEntity(EntityType<?> type,World world){super(type,world);}
    @Override protected void initDataTracker(DataTracker.Builder b){}
    @Override protected void readCustomDataFromNbt(NbtCompound n){}
    @Override protected void writeCustomDataToNbt(NbtCompound n){}
    @Override public void tick(){
        super.tick();if(getWorld().isClient)return;
        if(!hasPassengers()){discard();return;}
        // 原版会把载体的跌落伤害转发给乘员，必须同时清除载体自身的跌落累计。
        fallDistance=0;
        for(Entity e:getPassengerList())e.fallDistance=0;
        move(MovementType.SELF,new Vec3d(0,-.23,0));
        for(Entity e:getPassengerList())e.fallDistance=0;
        if(isOnGround()||isTouchingWater()){removeAllPassengers();discard();}
    }
    @Override public boolean handleFallDamage(float distance,float multiplier,net.minecraft.entity.damage.DamageSource source){return false;}
    @Override protected void updatePassengerPosition(Entity passenger,PositionUpdater updater){updater.accept(passenger,getX(),getY()+.15,getZ());passenger.fallDistance=0;}
}
