package org.DJB.no_24_heli.client.hud;

import org.DJB.no_24_heli.block.HelipadBlock;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.client.render.HudShapes;
import org.DJB.no_24_heli.network.AirspacePayload;
import org.DJB.no_24_heli.network.HelipadMapPayload;
import org.DJB.no_24_heli.network.RadarContactsPayload;




import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** 雷达与地图共享已跟踪实体；圆形雷达以机头为上方，地图仍保持北向。 */
@Environment(EnvType.CLIENT)
public final class HeliRadar {
    private static RadarContactsPayload contacts=new RadarContactsPayload(-1,List.of(),List.of());
    public static void receive(RadarContactsPayload packet){contacts=packet;}
    public static List<RadarContactsPayload.Contact> contacts(HelicopterEntity h){return contacts.helicopterId()==h.getId()?contacts.contacts():List.of();}
    /** 独立的固定地图目录，不随雷达扫描范围、区块卸载或换乘飞机清除。 */
    private static List<HelipadMapPayload.Entry> mapHelipads=List.of();
    /** 接收专用地图快照；完整替换可及时移除已拆除中心。 */
    public static void receive(HelipadMapPayload packet){mapHelipads=packet.helipads();}
    /** 地图读取固定目录，不使用当前飞机的雷达目标列表。 */
    public static List<HelipadMapPayload.Entry> mapHelipads(){return mapHelipads;}
    /** 雷达仅读取当前飞机的范围内扫描结果，地图通过独立目录读取固定标记。 */
    public static List<RadarContactsPayload.Helipad> helipads(HelicopterEntity heli){
        return contacts.helicopterId()==heli.getId()?contacts.helipads():List.of();
    }
    /** 服务端扫描状态结合最新禁区快照，完整圆盘接触禁区即禁用，不能仅检查中心点。 */
    public static boolean restricted(RadarContactsPayload.Helipad pad){
        return pad.restricted()||restricted(pad.pos());
    }
    /** 固定地图按真实禁区判定完整圆盘，禁止选择受限的远处旧停机坪。 */
    public static boolean restricted(BlockPos pos){
        return zones.stream().anyMatch(zone->HelipadBlock.intersectsZone(pos,zone.pos(),zone.radius()));
    }
    public static List<AirspacePayload.Entry> zones=List.of();
    private static List<Entity> targets=List.of();
    private static Object world;
    private static long scanTime=-1;
    public static void reset(){zones=List.of();targets=List.of();mapHelipads=List.of();contacts=new RadarContactsPayload(-1,List.of(),List.of());world=null;scanTime=-1;}
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
        ctx.fill(cx-52,3,cx+52,112,0xc010241f);
        HudShapes.circle(ctx,cx,cy,r,1,0xff527768);HudShapes.circle(ctx,cx,cy,r/2.0,1,0xff304b42);
        ctx.fill(cx-r,cy,cx+r,cy+1,0xff304b42);ctx.fill(cx,cy-r,cx+1,cy+r,0xff304b42);
        double angle=(heli.age+mc.getRenderTickCounter().getTickDelta(false))*.045;
        HudShapes.line(ctx,cx,cy,cx+Math.sin(angle)*r,cy-Math.cos(angle)*r,.8,0xff56aa83);
        float delta=mc.getRenderTickCounter().getTickDelta(false);
        var radarPosition=heli.getLerpedPos(delta);double radarYaw=heli.getYaw(delta);
        org.DJB.no_24_heli.client.render.NavigationChartRenderer.drawRadar(ctx,heli,cx,cy,r,delta);
        // 只绘制落在圆形雷达内的禁飞边界，机外圆心也能显示进入视野的红色弧线。
        for(var zone:zones){
            double[] center=relative(zone.pos().getX()+.5-radarPosition.x,zone.pos().getZ()+.5-radarPosition.z,radarYaw);
            double zx=center[0]*r/512.0,zy=center[1]*r/512.0,zr=zone.radius()*r/512.0;
            if(Math.hypot(zx,zy)>r+zr||Math.hypot(zx,zy)+r<zr)continue;
            int steps=Math.max(64,(int)Math.ceil(zr*Math.PI*4));
            for(int i=0;i<steps;i++){
                double a=i*Math.PI*2/steps,b=(i+1)*Math.PI*2/steps;
                double px=zx+Math.cos(a)*zr,py=zy+Math.sin(a)*zr,qx=zx+Math.cos(b)*zr,qy=zy+Math.sin(b)*zr;
                if(px*px+py*py<=r*r&&qx*qx+qy*qy<=r*r)HudShapes.line(ctx,cx+px,cy+py,cx+qx,cy+qy,.8,0xffff6655);
            }
        }
        for(var e:contacts(heli)){
            double dx=e.x()-radarPosition.x,dz=e.z()-radarPosition.z;if(Math.hypot(dx,dz)>512)continue;
            double[] local=relative(dx,dz,radarYaw);
            int x=cx+(int)(local[0]/512*r),y=cy+(int)(local[1]/512*r);
            ctx.fill(x-1,y-1,x+2,y+2,e.helicopter()?0xffffba55:0xff70cfff);
        }
        for(var pad:helipads(heli)){
            double[] local=relative(pad.pos().getX()+.5-radarPosition.x,pad.pos().getZ()+.5-radarPosition.z,radarYaw);
            if(Math.hypot(local[0],local[1])>RadarContactsPayload.RADIUS)continue;
            int x=cx+(int)(local[0]/RadarContactsPayload.RADIUS*r),y=cy+(int)(local[1]/RadarContactsPayload.RADIUS*r);
            ctx.drawCenteredTextWithShadow(mc.textRenderer,"H",x,y-4,restricted(pad)?0xffff6655:0xff7fffd4);
        }
        // 四个文字沿外圈旋转，文字自身保持正立；中心白色机头指针始终指向屏幕上方。
        double[][] directions={{0,-1},{1,0},{0,1},{-1,0}};String[] names={"north","east","south","west"};
        for(int i=0;i<4;i++){
            double[] local=relative(directions[i][0],directions[i][1],radarYaw);
            ctx.drawCenteredTextWithShadow(mc.textRenderer,net.minecraft.text.Text.translatable("hud.no_24_heli."+names[i]),cx+(int)(local[0]*(r+9)),cy+(int)(local[1]*(r+9))-4,0xffb9e7cc);
        }
        for(int y=-3;y<=1;y++){int half=Math.max(0,(y+3)/2);ctx.fill(cx-half,cy+y,cx+half+1,cy+y+1,0xffffffff);}
        ctx.fill(cx,cy+1,cx+1,cy+4,0xffffffff);
        if(heli.flight.waypoint){
            double[] local=relative(heli.flight.waypointX-radarPosition.x,heli.flight.waypointZ-radarPosition.z,radarYaw);
            double distance=Math.hypot(local[0],local[1]);double scale=distance>512?r/Math.max(distance,.001):r/512.0;
            int x=cx+(int)(local[0]*scale),y=cy+(int)(local[1]*scale);
            // 超出扫描范围时钳制到外圈，但保持真实方位；菱形与实体方点明显区分。
            for(int i=-3;i<=3;i++){int half=3-Math.abs(i);ctx.fill(x-half,y+i,x+half+1,y+i+1,0xffffe36b);}
        }
    }
}
