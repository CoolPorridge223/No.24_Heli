/** 文件：地图箭头及雷达航路线；端侧：CLIENT；数据来自 no_24_heli:navigation_path（S2C）。 */
package org.DJB.no_24_heli.client.render;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.client.hud.HeliRadar;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.network.RadarContactsPayload;

/**
 * 职责：地图以白边灰心箭头区分确认航路和浅灰待定航路，两处机载雷达共用平滑虚线几何。
 * 引用 net.minecraft.client.gui.DrawContext 提交 GUI 几何，Vec3d 保存只读世界坐标。
 * 生命周期：地图、HUD 和舱内雷达每帧调用；仅客户端渲染线程，无网络注册或持久资源。
 * 排查：远目标穿出圆盘查线段裁剪；换点残线查目标匹配；不以二维全局引导冒充确认的地形航路。
 */
@Environment(EnvType.CLIENT)
public final class NavigationChartRenderer {
    /** 地图箭头每十八 GUI 像素一个，长度六像素、半宽两像素，配色沿用目镜灰心白边。 */
    private static final double ARROW_SPACING=18, ARROW_LENGTH=6, ARROW_HALF_WIDTH=2;
    /** 真实终点周围六 GUI 像素不画路线箭头，仅留一个箭头长度，隐藏极短尾段而不过度截短路线。 */
    private static final double DESTINATION_ARROW_CLEARANCE=ARROW_LENGTH;
    private static final int ARROW_COLOR=0xffe6e6e6, ARROW_BORDER=0xffffffff;
    /** 待定地图箭头改用浅灰填充和略亮灰边，清晰可见且仍弱于白边确认箭头。 */
    private static final int PENDING_ARROW_COLOR=0xffb4bab7, PENDING_ARROW_BORDER=0xffd0d5d2;
    /** 两处雷达共用亮线和提亮后的灰绿待定线，均为不透明颜色。 */
    private static final int RADAR_COLOR=0xffc8eee0, PENDING_RADAR_COLOR=0xff99afa5;
    /** 雷达虚线每四十八格一划、间隔二十四格；圆周内缩 3%，为圆头笔画留出边缘余量。 */
    private static final double DASH_LENGTH=48, DASH_GAP=24, RADAR_CLIP=.97;
    /** 雷达显示折线最多偏离原轨迹一格，约为 HUD 的 0.074 像素，避免亚像素短段反复叠加边缘。 */
    private static final double RADAR_TOLERANCE=1;
    /** 地图半秒更新一次，仅在前十六段内裁去已飞过的点，避免近距回环跳到后续航路。 */
    private static final int PROJECTION_SEGMENTS=16;

    /** 已裁剪虚线的一划；坐标为单位雷达圆盘，Y 向下，HUD 和舱内仪表只需缩放及翻转 Y。 */
    public record RadarStroke(double ax,double ay,double bx,double by,int color) {
        /** 功能：同一线划跨原始航点时只在首尾画圆头，避免密集重叠造成颜色变深或亮点。 */
        public boolean connectsTo(RadarStroke next) {
            return color==next.color&&Math.hypot(bx-next.ax,by-next.ay)<1e-7;
        }
    }

    /** 静态绘制工具，不创建实例。 */
    private NavigationChartRenderer() {}

    /** 功能：只显示运行中的自动导航；读取服务端同步的 FlightState，不要求佩戴目镜。 */
    private static boolean active(HelicopterEntity heli) {
        return heli.flight.autopilot&&heli.flight.waypoint&&!heli.flight.wreck;
    }

    /**
     * 功能：用当前位置接回最新确认航路，并裁去已飞过的头部；客户端只读，坐标单位格。
     * 仅在有限头段查最近投影；使用 net.minecraft.entity.Entity#getLerpedPos 与实际机体逐帧一致。
     */
    private static List<Vec3d> remaining(HelicopterEntity heli,float delta) {
        Vec3d position=heli.getLerpedPos(delta);
        List<Vec3d> points=NavigationPathRenderer.chartPoints(heli);
        if(points.size()<2)return List.of(position);
        int first=1;double nearest=Double.POSITIVE_INFINITY;
        for(int i=0;i+1<points.size()&&i<PROJECTION_SEGMENTS;i++) {
            Vec3d a=points.get(i),b=points.get(i+1);
            double dx=b.x-a.x,dz=b.z-a.z,square=dx*dx+dz*dz;
            double t=square<1e-9?0:Math.clamp(((position.x-a.x)*dx+(position.z-a.z)*dz)/square,0,1);
            double distance=Math.hypot(position.x-a.x-dx*t,position.z-a.z-dz*t);
            if(distance<nearest){nearest=distance;first=i+1;}
        }
        List<Vec3d> result=new ArrayList<>(points.size()-first+1);result.add(position);
        result.addAll(points.subList(first,points.size()));
        return result;
    }

    /**
     * 功能：绘制北向地图的确认箭头及浅灰待定箭头；net.minecraft.client.gui.DrawContext 的外层裁剪保留。
     * 端侧：渲染线程；坐标先减双精度地图中心，再缩放；先裁剪再生成箭头，远端不扩大绘制工作量。
     */
    public static void drawMap(DrawContext ctx,HelicopterEntity heli,int left,int top,int width,int height,
                               double centerX,double centerZ,double blocksPerPixel,float delta) {
        if(!active(heli))return;
        List<Vec3d> points=remaining(heli,delta);
        Vec3d last=points.getLast();
        Vec3d goal=new Vec3d(heli.flight.waypointX,0,heli.flight.waypointZ);
        mapArrows(ctx,List.of(last,goal),goal,
                left,top,width,height,centerX,centerZ,blocksPerPixel,PENDING_ARROW_COLOR,PENDING_ARROW_BORDER);
        mapArrows(ctx,points,goal,left,top,width,height,centerX,centerZ,blocksPerPixel,ARROW_COLOR,ARROW_BORDER);
    }

    /** 功能：沿折线统一排布箭头，待定直达段使用同一轮廓与采样；先裁剪再生成，远目标不增加工作量。 */
    private static void mapArrows(DrawContext ctx,List<Vec3d> points,Vec3d goal,int left,int top,int width,int height,
                                   double centerX,double centerZ,double blocksPerPixel,int color,int border) {
        double cx=left+width/2,cy=top+height/2;
        double goalX=cx+(goal.x-centerX)/blocksPerPixel,goalY=cy+(goal.z-centerZ)/blocksPerPixel;
        double total=0;
        for(int i=1;i<points.size();i++) {
            Vec3d a=points.get(i-1),b=points.get(i);
            total+=Math.hypot(b.x-a.x,b.z-a.z)/blocksPerPixel;
        }
        if(total<1e-6)return;
        // 从固定尾锚点反向对齐间距，前进时箭头不随头部裁剪滑动；真实终点附近单独留空。
        double spacing=Math.min(ARROW_SPACING,total),phase=(total-spacing/2)%spacing;
        double along=0;
        for(int i=1;i<points.size();i++) {
            Vec3d a=points.get(i-1),b=points.get(i);
            double ax=cx+(a.x-centerX)/blocksPerPixel,ay=cy+(a.z-centerZ)/blocksPerPixel;
            double bx=cx+(b.x-centerX)/blocksPerPixel,by=cy+(b.z-centerZ)/blocksPerPixel;
            double dx=bx-ax,dy=by-ay,length=Math.hypot(dx,dy);
            if(length<1e-6)continue;
            double[] clip=rectangle(ax,ay,bx,by,left-ARROW_LENGTH,top-ARROW_LENGTH,
                    left+width+ARROW_LENGTH,top+height+ARROW_LENGTH);
            if(clip!=null) {
                double start=Math.ceil((along+clip[0]*length-phase)/spacing)*spacing+phase-along;
                for(double at=start;at<=clip[1]*length;at+=spacing) {
                    double x=ax+dx*at/length,y=ay+dy*at/length;
                    if(Math.hypot(x-goalX,y-goalY)>=DESTINATION_ARROW_CLEARANCE)
                        arrow(ctx,x,y,dx/length,dy/length,color,border);
                }
            }
            along+=length;
        }
    }

    /**
     * 功能：绘制 HUD 雷达圆头虚线，复用舱内相同的插值、虚线间距及圆盘裁剪；仅客户端渲染线程。
     * net.minecraft.client.gui.DrawContext 只追加几何，半径为 GUI 像素；两处扫描范围统一为 512 格。
     */
    public static void drawRadar(DrawContext ctx,HelicopterEntity heli,double cx,double cy,double radius,float delta) {
        List<RadarStroke> strokes=radarStrokes(heli,delta);
        for(int i=0;i<strokes.size();i++) {
            RadarStroke stroke=strokes.get(i);
            double ax=cx+stroke.ax()*radius,ay=cy+stroke.ay()*radius;
            double bx=cx+stroke.bx()*radius,by=cy+stroke.by()*radius;
            HudShapes.line(ctx,ax,ay,bx,by,1,stroke.color());
            if(i==0||!strokes.get(i-1).connectsTo(stroke))HudShapes.circle(ctx,ax,ay,.5,0,stroke.color());
            if(i+1==strokes.size()||!stroke.connectsTo(strokes.get(i+1)))HudShapes.circle(ctx,bx,by,.5,0,stroke.color());
        }
    }

    /**
     * 功能：为两处雷达生成同一组虚线，位置和偏航均使用机体逐帧插值，跨 0/360° 连续。
     * 引用 net.minecraft.entity.Entity#getLerpedPos 及 HelicopterEntity#getYaw(float)，只读不推进飞行状态。
     */
    public static List<RadarStroke> radarStrokes(HelicopterEntity heli,float delta) {
        if(!active(heli))return List.of();
        List<Vec3d> points=remaining(heli,delta);
        List<RadarStroke> strokes=new ArrayList<>();
        Vec3d position=heli.getLerpedPos(delta),last=points.getLast();
        double yaw=heli.getYaw(delta);
        radarPath(strokes,List.of(last,new Vec3d(heli.flight.waypointX,0,heli.flight.waypointZ)),position,yaw,PENDING_RADAR_COLOR);
        radarPath(strokes,points,position,yaw,RADAR_COLOR);
        return strokes;
    }

    /** 功能：按距离尾锚点的弧长固定虚线相位，裁剪头部和飞机前进不会带动后续线划滑动。 */
    private static void radarPath(List<RadarStroke> strokes,List<Vec3d> points,Vec3d position,double yaw,int color) {
        points=simplify(points);
        double phase=0;
        for(int i=1;i<points.size();i++) {
            Vec3d a=points.get(i-1),b=points.get(i);
            phase-=Math.hypot(b.x-a.x,b.z-a.z);
        }
        for(int i=1;i<points.size();i++) {
            Vec3d a=points.get(i-1),b=points.get(i);
            double[] from=HeliRadar.relative(a.x-position.x,a.z-position.z,yaw);
            double[] to=HeliRadar.relative(b.x-position.x,b.z-position.z,yaw);
            dashedSegment(strokes,from[0]/RadarContactsPayload.RADIUS,from[1]/RadarContactsPayload.RADIUS,
                    to[0]/RadarContactsPayload.RADIUS,to[1]/RadarContactsPayload.RADIUS,phase,color);
            phase+=Math.hypot(b.x-a.x,b.z-a.z);
        }
    }

    /** 功能：仅简化雷达显示的亚像素弯折，保留首尾与超过一格误差的拐点，不修改服务端航路。 */
    private static List<Vec3d> simplify(List<Vec3d> points) {
        if(points.size()<3)return points;
        boolean[] keep=new boolean[points.size()];keep[0]=true;keep[points.size()-1]=true;
        simplify(points,0,points.size()-1,keep);
        List<Vec3d> result=new ArrayList<>();
        for(int i=0;i<points.size();i++)if(keep[i])result.add(points.get(i));
        return result;
    }

    /** 功能：按完整线段距离保留必要拐点，退化/回环线段也不跳过；纯坐标计算，无世界访问。 */
    private static void simplify(List<Vec3d> points,int first,int last,boolean[] keep) {
        Vec3d a=points.get(first),b=points.get(last);
        double dx=b.x-a.x,dz=b.z-a.z,square=dx*dx+dz*dz,maximum=RADAR_TOLERANCE*RADAR_TOLERANCE;
        int selected=-1;
        for(int i=first+1;i<last;i++) {
            Vec3d p=points.get(i);
            double t=square<1e-9?0:Math.clamp(((p.x-a.x)*dx+(p.z-a.z)*dz)/square,0,1);
            double x=p.x-a.x-dx*t,z=p.z-a.z-dz*t,distance=x*x+z*z;
            if(distance>maximum){maximum=distance;selected=i;}
        }
        if(selected<0)return;
        keep[selected]=true;simplify(points,first,selected,keep);simplify(points,selected,last,keep);
    }

    /** 功能：先裁剪到单位圆再按世界弧长切虚线，两端在圆外的穿越段也保留，远终点不会生成海量线划。 */
    private static void dashedSegment(List<RadarStroke> strokes,double ax,double ay,double bx,double by,double phase,int color) {
        double[] clip=circle(ax,ay,bx,by,RADAR_CLIP);
        if(clip==null)return;
        double dx=bx-ax,dy=by-ay,length=Math.hypot(dx,dy)*RadarContactsPayload.RADIUS;
        double from=clip[0]*length,to=clip[1]*length,period=DASH_LENGTH+DASH_GAP;
        double first=Math.floor((phase+from)/period)*period-phase;
        for(double at=first;at<to;at+=period) {
            double start=Math.max(from,at),end=Math.min(to,at+DASH_LENGTH);
            if(end-start<1e-6)continue;
            strokes.add(new RadarStroke(ax+dx*start/length,ay+dy*start/length,
                    ax+dx*end/length,ay+dy*end/length,color));
        }
    }

    /** 功能：解析裁剪圆盘内的参数区间，零长段不绘制；无 MC API 或副作用。 */
    private static double[] circle(double ax,double ay,double bx,double by,double radius) {
        double dx=bx-ax,dy=by-ay,square=dx*dx+dy*dy;
        if(square<1e-9)return null;
        double projection=-(ax*dx+ay*dy)/square;
        double nearestX=ax+projection*dx,nearestY=ay+projection*dy;
        double height=radius*radius-nearestX*nearestX-nearestY*nearestY;
        if(height<0)return null;
        double half=Math.sqrt(height/square),start=Math.max(0,projection-half),end=Math.min(1,projection+half);
        return start<end?new double[]{start,end}:null;
    }

    /** 功能：裁剪矩形内的参数区间，确认和待定箭头共用；无 MC API 或副作用。 */
    private static double[] rectangle(double ax,double ay,double bx,double by,
                                       double left,double top,double right,double bottom) {
        double[] clip={0,1};
        return axis(ax,bx-ax,left,right,clip)&&axis(ay,by-ay,top,bottom,clip)?clip:null;
    }

    /** 功能：将线段参数收紧到单轴范围，平行且在范围外时拒绝；无 MC API，仅修改 clip。 */
    private static boolean axis(double from,double delta,double min,double max,double[] clip) {
        if(Math.abs(delta)<1e-9)return from>=min&&from<=max;
        double a=(min-from)/delta,b=(max-from)/delta;
        clip[0]=Math.max(clip[0],Math.min(a,b));clip[1]=Math.min(clip[1],Math.max(a,b));
        return clip[0]<clip[1];
    }

    /** 功能：沿航线切线绘制目镜式无柄凹尾箭头，确认/待定配色由调用者指定；客户端 GUI 无持久资源。 */
    private static void arrow(DrawContext ctx,double x,double y,double dx,double dy,int color,int border) {
        double tipX=x+dx*ARROW_LENGTH/2,tipY=y+dy*ARROW_LENGTH/2;
        double leftX=x-dx*ARROW_LENGTH/2-dy*ARROW_HALF_WIDTH,leftY=y-dy*ARROW_LENGTH/2+dx*ARROW_HALF_WIDTH;
        double rightX=x-dx*ARROW_LENGTH/2+dy*ARROW_HALF_WIDTH,rightY=y-dy*ARROW_LENGTH/2-dx*ARROW_HALF_WIDTH;
        // 凹尾收进长度的三成，与三维导引箭头轮廓一致。
        double notchX=x-dx*ARROW_LENGTH*.2,notchY=y-dy*ARROW_LENGTH*.2;
        HudShapes.triangle(ctx,tipX,tipY,leftX,leftY,notchX,notchY,color);
        HudShapes.triangle(ctx,tipX,tipY,notchX,notchY,rightX,rightY,color);
        HudShapes.line(ctx,tipX,tipY,leftX,leftY,.6,border);
        HudShapes.line(ctx,leftX,leftY,notchX,notchY,.6,border);
        HudShapes.line(ctx,notchX,notchY,rightX,rightY,.6,border);
        HudShapes.line(ctx,rightX,rightY,tipX,tipY,.6,border);
    }
}
