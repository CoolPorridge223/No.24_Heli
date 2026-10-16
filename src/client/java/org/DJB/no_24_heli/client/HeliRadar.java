package org.DJB.no_24_heli.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.DJB.no_24_heli.AirspacePayload;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.HelipadBlock;
import org.DJB.no_24_heli.RadarContactsPayload;
import java.util.ArrayList;
import java.util.List;

/** 雷达与地图共享已跟踪实体；圆形雷达以机头为上方，地图仍保持北向。 */
@Environment(EnvType.CLIENT)
public final class HeliRadar {
    private static RadarContactsPayload contacts=new RadarContactsPayload(-1,List.of(),List.of());
    public static void receive(RadarContactsPayload packet){contacts=packet;}
    public static List<RadarContactsPayload.Contact> contacts(HelicopterEntity h){return contacts.helicopterId()==h.getId()?contacts.contacts():List.of();}
    /** 雷达和地图读取同一份服务端停机坪快照，换乘飞机时不显示上一架的扫描结果。 */
    public static List<RadarContactsPayload.Helipad> helipads(HelicopterEntity heli){
        return contacts.helicopterId()==heli.getId()?contacts.helipads():List.of();
    }
    /** 服务端扫描状态结合最新禁区快照，完整圆盘接触禁区即禁用，不能仅检查中心点。 */
    public static boolean restricted(RadarContactsPayload.Helipad pad){
        return pad.restricted()||zones.stream().anyMatch(zone->HelipadBlock.intersectsZone(pad.pos(),zone.pos(),zone.radius()));
    }
    public static List<AirspacePayload.Entry> zones=List.of();
    private static List<Entity> targets=List.of();
    private static Object world;
    private static long scanTime=-1;
    public static void reset(){zones=List.of();targets=List.of();contacts=new RadarContactsPayload(-1,List.of(),List.of());world=null;scanTime=-1;}
    public static List<Entity> targets(){
        var mc=MinecraftClient.getInstance();if(mc.world==null||mc.player==null){reset();return targets;}
        if(world!=mc.world){reset();world=mc.world;}
        if(mc.world.getTime()-scanTime>=5||scanTime<0){
            scanTime=mc.world.getTime();List<Entity> found=new ArrayList<>();
            for(Entity e:mc.world.getEntities())if(e!=mc.player&&e!=mc.player.getVehicle()&&!e.isRemoved()&&!e.isSpectator()
                    &&(e instanceof PlayerEntity||e instanceof HelicopterEntity h&&!h.flight.wreck)
                    &&e.squaredDistanceTo(mc.player)<512*512)found.add(e);
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
        // 外圈只保留方位字，移除标题和扫描距离文字。
        ctx.fill(cx-52,3,cx+52,112,0xc010241f);circle(ctx,cx,cy,r,0xff527768);circle(ctx,cx,cy,r/2,0xff304b42);
        ctx.fill(cx-r,cy,cx+r,cy+1,0xff304b42);ctx.fill(cx,cy-r,cx+1,cy+r,0xff304b42);
        double angle=(heli.age+mc.getRenderTickCounter().getTickDelta(false))*.045;
        for(int i=1;i<r;i++){int px=cx+(int)(Math.sin(angle)*i),py=cy-(int)(Math.cos(angle)*i);ctx.fill(px,py,px+1,py+1,0xff56aa83);}
        // 只绘制落在圆形雷达内的禁飞边界，机外圆心也能显示进入视野的红色弧线。
        for(var zone:zones){
            double[] center=relative(zone.pos().getX()+.5-heli.getX(),zone.pos().getZ()+.5-heli.getZ(),heli.getYaw());
            double zx=center[0]*r/512.0,zy=center[1]*r/512.0,zr=zone.radius()*r/512.0;
            if(Math.hypot(zx,zy)>r+zr||Math.hypot(zx,zy)+r<zr)continue;
            int steps=Math.max(64,(int)Math.ceil(zr*Math.PI*4));
            for(int i=0;i<steps;i++){
                double a=i*Math.PI*2/steps,px=zx+Math.cos(a)*zr,py=zy+Math.sin(a)*zr;
                if(px*px+py*py<=r*r)ctx.fill(cx+(int)Math.round(px),cy+(int)Math.round(py),cx+(int)Math.round(px)+1,cy+(int)Math.round(py)+1,0xffff6655);
            }
        }
        for(var e:contacts(heli)){
            double dx=e.x()-heli.getX(),dz=e.z()-heli.getZ();if(Math.hypot(dx,dz)>512)continue;
            double[] local=relative(dx,dz,heli.getYaw());
            int x=cx+(int)(local[0]/512*r),y=cy+(int)(local[1]/512*r);
            ctx.fill(x-1,y-1,x+2,y+2,e.helicopter()?0xffffba55:0xff70cfff);
        }
        for(var pad:helipads(heli)){
            double[] local=relative(pad.pos().getX()+.5-heli.getX(),pad.pos().getZ()+.5-heli.getZ(),heli.getYaw());
            if(Math.hypot(local[0],local[1])>RadarContactsPayload.RADIUS)continue;
            int x=cx+(int)(local[0]/RadarContactsPayload.RADIUS*r),y=cy+(int)(local[1]/RadarContactsPayload.RADIUS*r);
            ctx.drawCenteredTextWithShadow(mc.textRenderer,"H",x,y-4,restricted(pad)?0xffff6655:0xff7fffd4);
        }
        // 四个文字沿外圈旋转，文字自身保持正立；中心白色机头指针始终指向屏幕上方。
        double[][] directions={{0,-1},{1,0},{0,1},{-1,0}};String[] names={"north","east","south","west"};
        for(int i=0;i<4;i++){
            double[] local=relative(directions[i][0],directions[i][1],heli.getYaw());
            ctx.drawCenteredTextWithShadow(mc.textRenderer,net.minecraft.text.Text.translatable("hud.no_24_heli."+names[i]),cx+(int)(local[0]*(r+9)),cy+(int)(local[1]*(r+9))-4,0xffb9e7cc);
        }
        for(int y=-3;y<=1;y++){int half=Math.max(0,(y+3)/2);ctx.fill(cx-half,cy+y,cx+half+1,cy+y+1,0xffffffff);}
        ctx.fill(cx,cy+1,cx+1,cy+4,0xffffffff);
        if(heli.flight.waypoint){
            double[] local=relative(heli.flight.waypointX-heli.getX(),heli.flight.waypointZ-heli.getZ(),heli.getYaw());
            double distance=Math.hypot(local[0],local[1]);double scale=distance>512?r/Math.max(distance,.001):r/512.0;
            int x=cx+(int)(local[0]*scale),y=cy+(int)(local[1]*scale);
            // 超出扫描范围时钳制到外圈，但保持真实方位；菱形与实体方点明显区分。
            for(int i=-3;i<=3;i++){int half=3-Math.abs(i);ctx.fill(x-half,y+i,x+half+1,y+i+1,0xffffe36b);}
        }
    }
}
