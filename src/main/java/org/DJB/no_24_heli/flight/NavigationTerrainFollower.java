package org.DJB.no_24_heli.flight;

import java.util.List;

/**
 * 文件：三维轨迹的单调投影、连续前视与转弯速度包络，归属 flight 飞控模块，端侧 COMMON。
 * 无 MC API；NavigationPilot 每服务端 tick 调用，航段衔接时保留原控制器的速度和杆量。
 * 排查：切角查缩短前视的全段验证；停顿查最终剩余弧长；回环跳段查单调进度与投影窗口。
 */
final class NavigationTerrainFollower {
    /** 每次最多投影相邻六十四个两格采样段，不按整条路线寻找最近点。 */
    private static final int WINDOW=64;
    /** 本机当前路线、弧长、速度包络和水平曲率；仅所属服务端线程修改进度。 */
    private NavigationTerrainRoute.Result route;
    private double[] lengths,speeds,curves;
    private int segment;
    private double progress;
    /** 已缓存续段的长度，续段不变时每 tick 不扫描其完整点集。 */
    private NavigationTerrainRoute.Result continuation;
    private double continuationLength;
    /** 巡航速度和续段入口限速，用于在接缝之前完成升降及转弯所需的减速。 */
    private double cruise,continuationSpeed;
    /** 当前前视目标、高度前馈、剩余弧长、限速、曲率与通道安全标志，单位格和 tick。 */
    record Sample(NavigationTerrainRoute.Point target,double altitude,double remaining,double speed,double curvature,boolean safe) {}

    /** 功能：绑定完整地形轨迹，预计算坡度、顶棚和转弯限速；无 MC API，不改飞行物理。 */
    void bind(NavigationTerrainRoute.Result next,double cruise) {
        route=next;segment=0;progress=0;continuation=null;continuationLength=0;
        this.cruise=cruise;
        var points=route.path();int count=points.size();
        lengths=new double[count];speeds=new double[count];curves=new double[count];
        java.util.Arrays.fill(speeds,cruise);
        for(int i=1;i<count;i++) {
            var a=points.get(i-1);var b=points.get(i);
            lengths[i]=lengths[i-1]+a.distance(b);
            double horizontal=NavigationRoute.distance(a.horizontal(),b.horizontal()),vertical=Math.abs(b.y()-a.y());
            double limit=route.volume().speed(a,b);
            if(vertical>1e-6)limit=Math.min(limit,horizontal*(b.y()>a.y()
                    ?NavigationTerrainRoute.CLIMB_SPEED:NavigationTerrainRoute.DESCENT_SPEED)/vertical);
            speeds[i-1]=Math.min(speeds[i-1],limit);speeds[i]=Math.min(speeds[i],limit);
        }
        for(int i=1;i+1<count;i++) {
            curves[i]=curvature(points.get(i-1),points.get(i),points.get(i+1));
            if(Math.abs(curves[i])>1e-6)speeds[i]=Math.min(speeds[i],
                    Math.min(Math.sqrt(NavigationTrajectory.LATERAL_ACCELERATION/Math.abs(curves[i])),
                            NavigationTrajectory.YAW_RATE/Math.abs(curves[i])));
        }
        for(int i=count-2;i>=0;i--)speeds[i]=Math.min(speeds[i],Math.sqrt(speeds[i+1]*speeds[i+1]
                +2*NavigationController.BRAKE_ACCELERATION*NavigationRoute.distance(points.get(i).horizontal(),points.get(i+1).horizontal())));
    }

    /**
     * 功能：沿已验证轨迹连续跟随，下一航段已就绪时允许前视跨越接缝，不在中间航点逐点停车。
     * 无 MC API；参数为实际位置、速度和可选续段；仅更新有界单调进度。
     * 前视连线仍由整机配置空间检查，不能依赖曲线采样点安全就直接切过山体。
     */
    Sample sample(NavigationTerrainRoute.Point pos,double speed,double lookMin,double lookMax,
                  NavigationTerrainRoute.Result next) {
        var points=route.path();double best=Double.POSITIVE_INFINITY,selected=progress;
        double maximum=progress+Math.max(8,speed*3);
        for(int i=segment;i+1<points.size()&&i<segment+WINDOW&&lengths[i]<=maximum;i++) {
            var a=points.get(i);var b=points.get(i+1);
            double dx=b.x()-a.x(),dy=b.y()-a.y(),dz=b.z()-a.z(),square=dx*dx+dy*dy+dz*dz;
            double t=Math.clamp(((pos.x()-a.x())*dx+(pos.y()-a.y())*dy+(pos.z()-a.z())*dz)/square,0,1);
            double along=Math.clamp(lengths[i]+Math.sqrt(square)*t,progress,maximum);
            t=Math.clamp((along-lengths[i])/Math.sqrt(square),0,1);
            double deviation=pos.distance(NavigationTerrainRoute.interpolate(a,b,t));
            if(deviation<best) {best=deviation;selected=lengths[i]+Math.sqrt(square)*t;}
        }
        progress=Math.max(progress,selected);
        while(segment+2<points.size()&&lengths[segment+1]<=progress)segment++;
        double look=Math.clamp(lookMin+speed*20,lookMin,lookMax);
        var target=pointAt(progress+look,next);
        for(int i=0;i<8&&!route.volume().clear(pos,target);i++) {
            look*=.5;target=pointAt(progress+look,next);
        }
        double span=lengths[segment+1]-lengths[segment];
        double fraction=Math.clamp((progress-lengths[segment])/span,0,1);
        double limit=speeds[segment]*(1-fraction)+speeds[segment+1]*fraction;
        double remaining=lengths[lengths.length-1]-progress;
        // 续段尚未就绪时才向当前末端制动；已就绪时给控制器连续的剩余距离。
        if(next!=continuation) {
            continuation=next;continuationLength=0;
            if(next!=null) {
                for(int i=1;i<next.path().size();i++)continuationLength+=next.path().get(i-1).distance(next.path().get(i));
                var preview=new NavigationTerrainFollower();preview.bind(next,cruise);continuationSpeed=preview.speeds[0];
                double joint=curvature(points.get(points.size()-2),points.getLast(),next.path().get(1));
                if(Math.abs(joint)>1e-6)continuationSpeed=Math.min(continuationSpeed,
                        Math.min(Math.sqrt(NavigationTrajectory.LATERAL_ACCELERATION/Math.abs(joint)),NavigationTrajectory.YAW_RATE/Math.abs(joint)));
            }
        }
        if(next!=null)limit=Math.min(limit,Math.sqrt(continuationSpeed*continuationSpeed
                +2*NavigationController.BRAKE_ACCELERATION*remaining));
        remaining+=continuationLength;
        double altitude=pointAt(progress+Math.max(look,speed*40),next).y();
        return new Sample(target,altitude,remaining,limit,curves[segment],route.volume().clear(pos,target));
    }

    /** 功能：在当前有序轨迹及下一段的开头插值，不做全局最近点跳跃；无 MC API。 */
    private NavigationTerrainRoute.Point pointAt(double distance,NavigationTerrainRoute.Result next) {
        var points=route.path();double total=lengths[lengths.length-1];
        if(distance>total&&next!=null) {
            double rest=distance-total;var tail=next.path();
            for(int i=1;i<tail.size();i++) {
                double length=tail.get(i-1).distance(tail.get(i));
                if(rest<=length)return NavigationTerrainRoute.interpolate(tail.get(i-1),tail.get(i),rest/length);
                rest-=length;
            }
            return tail.getLast();
        }
        int at=segment;
        while(at+2<points.size()&&lengths[at+1]<distance)at++;
        return NavigationTerrainRoute.interpolate(points.get(at),points.get(at+1),
                Math.clamp((distance-lengths[at])/(lengths[at+1]-lengths[at]),0,1));
    }

    /** 功能：接缝附近即可交接，保留真实速度；最终导航点仍由驾驶器单独停稳确认。 */
    boolean atEnd(NavigationTerrainRoute.Point pos,double speed) {
        // 高度前馈可能已开始下一段的升降，不能要求重新回到接缝的旧高度才交接。
        return NavigationRoute.distance(pos.horizontal(),route.path().getLast().horizontal())<Math.max(2,speed*3)
                &&lengths[lengths.length-1]-progress<Math.max(4,speed*4);
    }

    /** 功能：计算水平三点曲率，符号与原导航控制器一致；纯垂直段不产生偏航前馈。 */
    private static double curvature(NavigationTerrainRoute.Point a,NavigationTerrainRoute.Point b,NavigationTerrainRoute.Point c) {
        double denominator=NavigationRoute.distance(a.horizontal(),b.horizontal())*NavigationRoute.distance(b.horizontal(),c.horizontal())
                *NavigationRoute.distance(a.horizontal(),c.horizontal());
        return denominator<1e-9?0:2*((b.x()-a.x())*(c.z()-b.z())-(b.z()-a.z())*(c.x()-b.x()))/denominator;
    }
}
