package org.DJB.no_24_heli.flight;

import java.util.*;

/** 禁飞圆柱的二维可见图。所有边都做线段/圆距离校验，绝不以端点在圈外代替整段安全。 */
public final class NavigationRoute {
    public record Point(double x,double z){}
    public record Circle(double x,double z,double radius){}
    public static final double MARGIN=18;
    private NavigationRoute(){}
    public static double distance(Point a,Point b){return Math.hypot(a.x-b.x,a.z-b.z);}
    public static double segmentDistance(Point a,Point b,Circle c){
        double dx=b.x-a.x,dz=b.z-a.z,l=dx*dx+dz*dz;
        double t=l<1e-9?0:Math.clamp(((c.x-a.x)*dx+(c.z-a.z)*dz)/l,0,1);
        return Math.hypot(a.x+t*dx-c.x,a.z+t*dz-c.z);
    }
    public static boolean clear(Point a,Point b,List<Circle> zones){
        for(var c:zones)if(segmentDistance(a,b,c)<=c.radius+MARGIN)return false;return true;
    }
    /** 圆周采用外接十六边形，邻点连线仍在安全半径外；限制图规模以控制服务器最坏耗时。 */
    public static List<Point> plan(Point start,Point goal,List<Circle> zones){
        if(clear(start,goal,zones))return List.of(goal);
        if(!clear(start,start,zones)||!clear(goal,goal,zones))return List.of();
        List<Circle> nearby=zones.stream().sorted(Comparator.comparingDouble(c->segmentDistance(start,goal,c)-c.radius)).limit(24).toList();
        List<Point> nodes=new ArrayList<>();nodes.add(start);nodes.add(goal);
        for(var c:nearby)for(int i=0;i<16;i++){
            double angle=i*Math.PI/8,r=(c.radius+MARGIN+2)/Math.cos(Math.PI/16);
            Point p=new Point(c.x+Math.cos(angle)*r,c.z+Math.sin(angle)*r);
            if(clear(p,p,zones))nodes.add(p);
        }
        int count=nodes.size();double[] cost=new double[count];Arrays.fill(cost,Double.POSITIVE_INFINITY);cost[0]=0;
        int[] previous=new int[count];Arrays.fill(previous,-1);boolean[] done=new boolean[count];
        for(int iteration=0;iteration<count;iteration++){
            int best=-1;double score=Double.POSITIVE_INFINITY;
            for(int i=0;i<count;i++)if(!done[i]&&cost[i]+distance(nodes.get(i),goal)<score){best=i;score=cost[i]+distance(nodes.get(i),goal);}
            if(best<0)break;if(best==1){List<Point> result=new ArrayList<>();for(int at=1;at>0;at=previous[at])result.add(nodes.get(at));Collections.reverse(result);return result;}
            done[best]=true;
            for(int i=1;i<count;i++)if(!done[i]){
                double next=cost[best]+distance(nodes.get(best),nodes.get(i));
                if(next<cost[i]&&clear(nodes.get(best),nodes.get(i),zones)){cost[i]=next;previous[i]=best;}
            }
        }
        return List.of();
    }
}
