package org.DJB.no_24_heli.flight;

/**
 * 文件：三维轨迹的单调投影、连续前视与转弯速度包络，归属 flight 飞控模块，端侧 COMMON。
 * 无 MC API；NavigationPilot 每服务端 tick 调用，航段衔接时保留原控制器的速度和杆量。
 * 排查：切角查缩短前视的全段验证；停顿查最终剩余弧长；回环跳段查单调进度与投影窗口。
 */
final class NavigationTerrainFollower {
    /** 每次最多投影相邻六十四个两格采样段，不按整条路线寻找最近点。 */
    private static final int WINDOW=64;
    /** 高度转折点进入半格邻域后才允许前视越过，避免提前下降或爬升抵消当前升降。 */
    private static final double ALTITUDE_TURN_DISTANCE=.5;
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
            // 纯升降的水平速度由当前前视坡度控制；将两端永久写零会让升降后的水平段无法起步。
            if(vertical>1e-6&&horizontal>1e-6)limit=Math.min(limit,horizontal*(b.y()>a.y()
                    ?NavigationTerrainRoute.CLIMB_SPEED:NavigationTerrainRoute.DESCENT_SPEED)/vertical);
            speeds[i-1]=Math.min(speeds[i-1],limit);speeds[i]=Math.min(speeds[i],limit);
        }
        for(int i=1;i+1<count;i++) {
            curves[i]=curvature(points.get(i-1),points.get(i),points.get(i+1));
            if(Math.abs(curves[i])>1e-6)speeds[i]=Math.min(speeds[i],
                    Math.min(Math.sqrt(NavigationController.LATERAL_ACCELERATION/Math.abs(curves[i])),
                            NavigationController.YAW_RATE/Math.abs(curves[i])));
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
        double maximum=altitudeHorizon(pos,progress+Math.max(8,speed*3),null);
        for(int i=segment;i+1<points.size()&&i<segment+WINDOW&&lengths[i]<=maximum;i++) {
            var a=points.get(i);var b=points.get(i+1);
            double dx=b.x()-a.x(),dy=b.y()-a.y(),dz=b.z()-a.z(),square=dx*dx+dy*dy+dz*dz;
            double t=Math.clamp(((pos.x()-a.x())*dx+(pos.y()-a.y())*dy+(pos.z()-a.z())*dz)/square,0,1);
            double along=Math.clamp(lengths[i]+Math.sqrt(square)*t,progress,maximum);
            t=Math.clamp((along-lengths[i])/Math.sqrt(square),0,1);
            var projection=NavigationTerrainRoute.interpolate(a,b,t);
            double deviation=pos.distance(projection);
            // 只推进到当前位置能安全接回的轨迹，避免隔着山体或禁区跳到回程段后再也无法返回。
            if(deviation<best&&clearAcross(pos,projection,next)) {best=deviation;selected=lengths[i]+Math.sqrt(square)*t;}
        }
        progress=Math.max(progress,selected);
        while(segment+2<points.size()&&lengths[segment+1]<=progress)segment++;
        double look=Math.min(Math.clamp(lookMin+speed*20,lookMin,lookMax),
                altitudeHorizon(pos,progress+lookMax,next)-progress);
        var target=reachableTarget(pos,pointAt(progress+look,next),next);
        for(int i=0;i<8&&target==null;i++) {
            look*=.5;target=reachableTarget(pos,pointAt(progress+look,next),next);
        }
        if(target==null)target=pointAt(progress+look,next);
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
                        Math.min(Math.sqrt(NavigationController.LATERAL_ACCELERATION/Math.abs(joint)),NavigationController.YAW_RATE/Math.abs(joint)));
            }
        }
        if(next!=null)limit=Math.min(limit,Math.sqrt(continuationSpeed*continuationSpeed
                +2*NavigationController.BRAKE_ACCELERATION*remaining));
        limit=Math.min(limit,speedAcross(pos,target,next));
        remaining+=continuationLength;
        // 高度与水平前视必须指向同一个已验证点；缩短前视后不能继续追逐远端反坡高度。
        double curve=curves[segment]*(1-fraction)+curves[segment+1]*fraction;
        return new Sample(target,target.y(),remaining,limit,curve,clearAcross(pos,target,next));
    }

    /**
     * 功能：为坡面和绕障回接寻找可执行的水平/高度组合；无 MC API，仅在已验证的快照内调整。
     * 远端高度在原地不可达时逐次缩小高差，可先平移离开山坡再下降，或移出顶棚后爬升。
     * 检查当前短程高度控制量和完整斜向连线；不再要求先原地爬到远端高度再水平移动。
     * 找不到就缩短前视，实时惯性扫掠继续兜住实际运动，绝不放宽机体净空。
     */
    private NavigationTerrainRoute.Point reachableTarget(NavigationTerrainRoute.Point pos,
            NavigationTerrainRoute.Point target,NavigationTerrainRoute.Result next) {
        for(int attempt=0;attempt<=8;attempt++) {
            double y=attempt==8?pos.y():pos.y()+(target.y()-pos.y())/Math.scalb(1,attempt);
            var adjusted=new NavigationTerrainRoute.Point(target.x(),y,target.z());
            var vertical=new NavigationTerrainRoute.Point(pos.x(),NavigationController.altitudeTarget(pos.y(),y),pos.z());
            if(clearAcross(pos,vertical,next)&&clearAcross(pos,adjusted,next))return adjusted;
        }
        return null;
    }

    /**
     * 功能：将投影及前视限制在尚未经过的第一个高度转折处；无 MC API，服务端跟随调用。
     * 平台延续上一段坡向，续段接缝同样检查；到达转折点后解除限制，不要求高度精确相等。
     * 排查：谷底/山脊卡住先查前视是否跨过反坡，不能靠将最小前视调大越过障碍。
     */
    private double altitudeHorizon(NavigationTerrainRoute.Point pos,double maximum,
                                   NavigationTerrainRoute.Result next) {
        var points=route.path();
        double direction=0,along=lengths[segment];
        var previous=points.get(segment);
        int count=points.size()+(next==null?0:next.path().size()-1);
        for(int i=segment+1;i<count&&along<=maximum;i++) {
            var point=i<points.size()?points.get(i):next.path().get(i-points.size()+1);
            double slope=point.y()-previous.y();
            if(Math.abs(slope)>1e-6) {
                if(direction*slope<0&&along>=progress&&pos.distance(previous)>ALTITUDE_TURN_DISTANCE)
                    return Math.min(maximum,along);
                direction=slope;
            }
            along+=previous.distance(point);previous=point;
        }
        return maximum;
    }

    /**
     * 功能：跨接缝前视逐小段使用能完整覆盖该段的地形快照，不将旧快照外的已验证续段判成障碍。
     * 无 MC API，服务端跟随调用；每段仍调用 Volume.clear 的禁区及超覆盖检查，不能拼接不相邻的安全点。
     * 排查：仅两个端点分别安全不够，整条连线的每个小段必须由至少一份快照完整证明安全。
     */
    private boolean clearAcross(NavigationTerrainRoute.Point from,NavigationTerrainRoute.Point to,
                                NavigationTerrainRoute.Result next) {
        int steps=Math.max(1,(int)Math.ceil(from.distance(to)/(NavigationTerrainRoute.CELL*.5)));
        var previous=from;
        for(int i=1;i<=steps;i++){
            var point=NavigationTerrainRoute.interpolate(from,to,(double)i/steps);
            if(!route.volume().clear(previous,point)&&(next==null||!next.volume().clear(previous,point)))return false;
            previous=point;
        }
        return true;
    }

    /**
     * 功能：只读取已覆盖且安全的小段限速，避免旧快照越界被当成洞内十八公里限速。
     * 无 MC API；重叠区取两份有效快照的较低限速，未知区域仍为零，不放宽顶棚或障碍约束。
     */
    private double speedAcross(NavigationTerrainRoute.Point from,NavigationTerrainRoute.Point to,
                               NavigationTerrainRoute.Result next) {
        int steps=Math.max(1,(int)Math.ceil(from.distance(to)/(NavigationTerrainRoute.CELL*.5)));
        double limit=cruise;var previous=from;
        for(int i=1;i<=steps;i++){
            var point=NavigationTerrainRoute.interpolate(from,to,(double)i/steps);
            boolean currentClear=route.volume().clear(previous,point);
            boolean nextClear=next!=null&&next.volume().clear(previous,point);
            if(!currentClear&&!nextClear)return 0;
            if(currentClear)limit=Math.min(limit,route.volume().speed(previous,point));
            if(nextClear)limit=Math.min(limit,next.volume().speed(previous,point));
            previous=point;
        }
        return limit;
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
    boolean atEnd(NavigationTerrainRoute.Point pos,double speed,NavigationTerrainRoute.Result next) {
        // 已通过当前末端并安全进入续段时立即交接，不能要求飞机倒飞回旧接缝的两格半径内。
        if(next!=null&&lengths[lengths.length-1]-progress<ALTITUDE_TURN_DISTANCE
                &&clearAcross(route.path().getLast(),pos,next))return true;
        // 前视可已进入续段，但尚余明显升降弧长时不能仅因水平重合就跳过山脊或谷底。
        return NavigationRoute.distance(pos.horizontal(),route.path().getLast().horizontal())<Math.max(2,speed*3)
                &&(Math.abs(pos.y()-route.path().getLast().y())<ALTITUDE_TURN_DISTANCE
                ||lengths[lengths.length-1]-progress<ALTITUDE_TURN_DISTANCE)
                &&lengths[lengths.length-1]-progress<Math.max(4,speed*4);
    }

    /** 功能：返回本航段已推进的三维弧长，供无进展重规划检测；无 MC API，不计单纯偏航。 */
    double progress() { return progress; }

    /** 功能：计算水平三点曲率，符号与原导航控制器一致；纯垂直段不产生偏航前馈。 */
    private static double curvature(NavigationTerrainRoute.Point a,NavigationTerrainRoute.Point b,NavigationTerrainRoute.Point c) {
        double denominator=NavigationRoute.distance(a.horizontal(),b.horizontal())*NavigationRoute.distance(b.horizontal(),c.horizontal())
                *NavigationRoute.distance(a.horizontal(),c.horizontal());
        return denominator<1e-9?0:2*((b.x()-a.x())*(c.z()-b.z())-(b.z()-a.z())*(c.x()-b.x()))/denominator;
    }
}
