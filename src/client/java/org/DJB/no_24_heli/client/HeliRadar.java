package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.DJB.no_24_heli.*;
import java.util.*;

/** 雷达与地图共享已跟踪实体；圆形雷达以机头为上方，地图仍保持北向。 */
public final class HeliRadar {
    public static List<AirspacePayload.Entry> zones=List.of();
    private static List<Entity> targets=List.of();
    private static Object world;
    private static long scanTime=-1;
    public static void reset(){zones=List.of();targets=List.of();world=null;scanTime=-1;}
    public static List<Entity> targets(){
        var mc=MinecraftClient.getInstance();if(mc.world==null||mc.player==null){reset();return targets;}
        if(world!=mc.world){reset();world=mc.world;}
        if(mc.world.getTime()-scanTime>=5||scanTime<0){
            scanTime=mc.world.getTime();List<Entity> found=new ArrayList<>();
            for(Entity e:mc.world.getEntities())if(e!=mc.player&&e!=mc.player.getVehicle()&&!e.isRemoved()&&!e.isSpectator()
                    &&(e instanceof PlayerEntity||e instanceof HelicopterEntity h&&!h.flight.wreck)
                    &&e.squaredDistanceTo(mc.player)<128*128)found.add(e);
            targets=List.copyOf(found);
        }
        return targets;
    }
    /** 圆环由细分线点组成；无纹理依赖，可复用于不同 GUI 比例与地图缩放。 */
    public static void circle(DrawContext ctx,int cx,int cy,int radius,int color){
        int segments=Math.clamp(radius*7,180,4096);
        for(int i=0;i<segments;i++){double a=i*Math.PI*2/segments;int x=cx+(int)(Math.sin(a)*radius),y=cy+(int)(Math.cos(a)*radius);ctx.fill(x,y,x+1,y+1,color);}
    }
    /**
     * 世界位移旋转到机头坐标：屏幕右为机体右侧，屏幕上为机头前方。
     * Minecraft偏航0指向南，因此不能照搬普通北向地图的X/Z坐标；方位字、
     * 目标和导航点必须共用此变换，否则转向时会出现互相反转或镜像的问题。
     */
    public static double[] relative(double dx,double dz,double yawDegrees){
        double yaw=Math.toRadians(yawDegrees);
        return new double[]{-dx*Math.cos(yaw)-dz*Math.sin(yaw),dx*Math.sin(yaw)-dz*Math.cos(yaw)};
    }
    public static void draw(DrawContext ctx,HelicopterEntity heli,int right){
        var mc=MinecraftClient.getInstance();int cx=right-54,cy=57,r=38;
        // 外圈方位字最大到Y=108，标题另留底行，避免机头朝南时“北”与标题相互遮挡。
        ctx.fill(cx-52,3,cx+52,130,0xc010241f);circle(ctx,cx,cy,r,0xff527768);circle(ctx,cx,cy,r/2,0xff304b42);
        ctx.fill(cx-r,cy,cx+r,cy+1,0xff304b42);ctx.fill(cx,cy-r,cx+1,cy+r,0xff304b42);
        double angle=(heli.age+mc.getRenderTickCounter().getTickDelta(false))*.045;
        for(int i=1;i<r;i++){int px=cx+(int)(Math.sin(angle)*i),py=cy-(int)(Math.cos(angle)*i);ctx.fill(px,py,px+1,py+1,0xff56aa83);}
        for(Entity e:targets()){
            double dx=e.getX()-heli.getX(),dz=e.getZ()-heli.getZ();if(Math.hypot(dx,dz)>128)continue;
            double[] local=relative(dx,dz,heli.getYaw());
            int x=cx+(int)(local[0]/128*r),y=cy+(int)(local[1]/128*r);
            ctx.fill(x-1,y-1,x+2,y+2,e instanceof HelicopterEntity?0xffffba55:0xff70cfff);
        }
        // 四个文字沿外圈旋转，文字自身保持正立；中心白色机头指针始终指向屏幕上方。
        double[][] directions={{0,-1},{1,0},{0,1},{-1,0}};String[] names={"north","east","south","west"};
        for(int i=0;i<4;i++){
            double[] local=relative(directions[i][0],directions[i][1],heli.getYaw());
            ctx.drawCenteredTextWithShadow(mc.textRenderer,net.minecraft.text.Text.translatable("hud.no_24_heli."+names[i]),cx+(int)(local[0]*(r+9)),cy+(int)(local[1]*(r+9))-4,0xffb9e7cc);
        }
        for(int y=-6;y<=4;y++){int half=Math.max(0,(y+6)/3);ctx.fill(cx-half,cy+y,cx+half+1,cy+y+1,0xffffffff);}
        ctx.fill(cx-1,cy+3,cx+2,cy+7,0xffffffff);
        if(heli.flight.waypoint){
            double[] local=relative(heli.flight.waypointX-heli.getX(),heli.flight.waypointZ-heli.getZ(),heli.getYaw());
            double distance=Math.hypot(local[0],local[1]);double scale=distance>128?r/Math.max(distance,.001):r/128.0;
            int x=cx+(int)(local[0]*scale),y=cy+(int)(local[1]*scale);
            // 超出扫描范围时钳制到外圈，但保持真实方位；菱形与实体方点明显区分。
            for(int i=-3;i<=3;i++){int half=3-Math.abs(i);ctx.fill(x-half,y+i,x+half+1,y+i+1,0xffffe36b);}
        }
        ctx.drawCenteredTextWithShadow(mc.textRenderer,net.minecraft.text.Text.translatable("hud.no_24_heli.radar"),cx,118,0xffb9e7cc);
    }
}
