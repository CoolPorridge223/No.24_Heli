package org.DJB.no_24_heli.flight;

import org.DJB.no_24_heli.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;
import java.util.*;

/** 服务端导航协调器：缓存绕行路线，每 tick 检查前视净空并在障碍前刹车爬升。 */
public final class NavigationPilot {
    private List<NavigationRoute.Point> route=List.of();
    private List<NavigationRoute.Circle> circles=List.of();
    private long revision=-1,retryAt;
    private double goalX=Double.NaN,goalZ;
    private int node;
    public void reset(){revision=-1;goalX=Double.NaN;route=List.of();node=0;retryAt=0;}
    public void step(HelicopterEntity h){
        var s=h.flight;var world=(ServerWorld)h.getWorld();var airspace=AirspaceState.get(world);
        var pos=new NavigationRoute.Point(h.getX(),h.getZ());var goal=new NavigationRoute.Point(s.waypointX,s.waypointZ);
        boolean changed=revision!=airspace.revision()||goalX!=goal.x()||goalZ!=goal.z();
        if(changed){revision=airspace.revision();goalX=goal.x();goalZ=goal.z();circles=airspace.zones().stream().map(z->new NavigationRoute.Circle(z.pos().getX()+.5,z.pos().getZ()+.5,z.radius())).toList();}
        // 管理员新建禁飞区后终点也要重新验证，不能继续飞向已失效的导航点。
        if(airspace.zones().stream().anyMatch(z->z.contains(goal.x(),goal.z()))){h.stopAutopilot();notify(h,"waypoint_restricted");return;}
        if(changed||(world.getTime()>=retryAt&&(route.isEmpty()||!NavigationRoute.clear(pos,route.get(node),circles)))){
            route=NavigationRoute.plan(pos,goal,circles);node=0;retryAt=world.getTime()+100;s.navAligned=false;
            if(route.isEmpty())notify(h,"route_blocked");
        }
        if(route.isEmpty()){NavigationController.step(s,h.getX(),h.getZ(),h.getX(),h.getZ(),0);return;}
        var target=route.get(node);
        if(node<route.size()-1&&NavigationRoute.distance(pos,target)<3){target=route.get(++node);s.navAligned=false;}
        double dx=target.x()-h.getX(),dz=target.z()-h.getZ(),distance=Math.hypot(dx,dz);
        double speed=Math.hypot(s.vx,s.vz),look=Math.min(distance,Math.max(24,speed*50+12));
        Vec3d ahead=distance<.1?Vec3d.ZERO:new Vec3d(dx/distance*look,0,dz/distance*look);
        // 宽度覆盖旋翼；查询仅已加载区块，未知地形按障碍处理，不同步加载世界。
        Box base=h.getBoundingBox().expand(2,.7,2);
        boolean clear=clear(world,h,base.stretch(ahead));
        // 检查实际惯性方向，防止转弯时机头路线安全但横向漂移撞墙。
        boolean driftClear=clear(world,h,base.stretch(s.vx*40,Math.min(0,s.vy*30),s.vz*40));
        double limit=clear&&driftClear?.80:0;
        if(!clear){
            // 爬升只在整个竖直通道及抬高后的前视通道都安全时执行，隧道里保持制动。
            for(int rise=4;rise<=24;rise+=4)if(h.getY()+rise+4<world.getTopY()
                    &&clear(world,h,base.stretch(0,rise,0))&&clear(world,h,base.offset(0,rise,0).stretch(ahead))){
                s.targetAltitude=Math.max(s.targetAltitude,h.getY()+rise);break;
            }
        }
        if(!NavigationRoute.clear(pos,new NavigationRoute.Point(h.getX()+s.vx*40,h.getZ()+s.vz*40),circles))limit=0;
        NavigationController.step(s,h.getX(),h.getZ(),target.x(),target.z(),limit);
    }
    private static boolean clear(ServerWorld world,HelicopterEntity h,Box box){
        for(int x=MathHelper.floor(box.minX)>>4;x<=MathHelper.floor(box.maxX)>>4;x++)
            for(int z=MathHelper.floor(box.minZ)>>4;z<=MathHelper.floor(box.maxZ)>>4;z++)if(!world.getChunkManager().isChunkLoaded(x,z))return false;
        if(!world.getWorldBorder().contains(box)||world.getBlockCollisions(h,box).iterator().hasNext())return false;
        return world.getOtherEntities(h,box,e->!h.isConnectedThroughVehicle(e)&&!(e instanceof PlayerEntity)&&!(e instanceof RopeSeatEntity)
                &&!(e.getRootVehicle() instanceof RopeSeatEntity)&&!e.isSpectator()&&(e.isCollidable()||e instanceof net.minecraft.entity.LivingEntity)).isEmpty();
    }
    private static void notify(HelicopterEntity h,String key){if(h.getFirstPassenger() instanceof PlayerEntity p)p.sendMessage(net.minecraft.text.Text.translatable("message.no_24_heli."+key),true);}
}
