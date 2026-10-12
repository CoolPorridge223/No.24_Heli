package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import org.DJB.no_24_heli.*;
import java.util.Locale;

/** 飞行数据右下、机体与自动控制左下、姿态仪及禁飞区雷达右上。 */
public final class HeliHud {
    /** 在原版 GUI 比例上缩小到 85%，保留边缘留白和快捷栏空间。 */
    private static final float SCALE=.85f;
    private static String f(double value){return String.format(Locale.ROOT,"%.1f",value);}
    private static void text(DrawContext ctx,String key,int x,int y,int color,Object... args){
        ctx.drawText(MinecraftClient.getInstance().textRenderer,Text.translatable(key,args),x,y,color,true);
    }
    public static void draw(DrawContext ctx,RenderTickCounter counter){
        var mc=MinecraftClient.getInstance();
        if(mc.player==null||mc.options.hudHidden||!(mc.player.getVehicle() instanceof HelicopterEntity h)||mc.currentScreen!=null)return;
        var s=h.flight;var m=ctx.getMatrices();m.push();m.scale(SCALE,SCALE,1);
        int w=(int)(ctx.getScaledWindowWidth()/SCALE),height=(int)(ctx.getScaledWindowHeight()/SCALE),x=10;
        // 底部统一缩放，窄窗口也为左侧仪表、中央血条和右侧飞行数据留出独立空间。
        float bottomScale=Math.min(1,w/560f);int bw=(int)(w/bottomScale);
        m.push();m.translate(0,height,0);m.scale(bottomScale,bottomScale,1);
        m.push();m.translate(2,-59,0);m.scale(.78f,.78f,1);
        gauge(ctx,31,32,29,"fuel_dial",s.fuel,0,HeliConfig.INSTANCE.fuelCapacity,"",0xffffd66b);
        gauge(ctx,96,32,29,"rpm_dial",s.rpm*100,0,100,"%",0xff7ce3bc);
        gauge(ctx,161,32,29,"collective_dial",s.collective*100,0,100,"%",0xff7ce3bc);
        m.pop();
        text(ctx,s.difficultyMode==2?"hud.no_24_heli.state_zobayan":s.hardcoreMode?"hud.no_24_heli.state_mamba":"hud.no_24_heli.state",4,-84,0xffffd66b);
        String status="H "+(s.altitudeHold?"●":"○")+"  J "+(s.trim?"●":"○")+"  I "+(s.cruise?"●":"○")+"  G "+(s.gear?"↓":"↑");
        ctx.drawText(mc.textRenderer,status,4,-70,0xffb6edc8,true);
        if(s.autoPhase!=0)text(ctx,"hud.no_24_heli.auto_phase_"+s.autoPhase,4,-120,0xff8fffb1);
        if(s.autopilot)text(ctx,"hud.no_24_heli.autopilot",4,-108,0xff8fffb1);
        if(s.waypoint)text(ctx,"hud.no_24_heli.waypoint",4,-96,0xffe9e897,(int)Math.hypot(s.waypointX-h.getX(),s.waypointZ-h.getZ()));
        // 机组状态位于机身模式和自动控制文字上方，保留原先的占位方框语义。
        for(int i=0;i<2;i++){
            int sx=4+i*21,sy=-142,color=0xffb6edc8;
            if(i<h.getPassengerList().size())ctx.fill(sx,sy,sx+15,sy+15,color);
            else{ctx.fill(sx,sy,sx+15,sy+1,color);ctx.fill(sx,sy+14,sx+15,sy+15,color);ctx.fill(sx,sy,sx+1,sy+15,color);ctx.fill(sx+14,sy,sx+15,sy+15,color);}
        }
        // 五段血条紧密拼接；每个部件仍独立扣血，总宽由 240 缩短到 150。
        double[] values={s.hull,s.rotor,s.tail,s.engine,s.tank};String[] keys={"hull","rotor","tail","engine","tank"};
        int barWidth=150,barX=(bw-barWidth)/2,segment=barWidth/5;
        ctx.fill(barX,-10,barX+barWidth,-5,0xdd273c33);
        for(int i=0;i<5;i++){
            int sx=barX+i*segment;
            ctx.fill(sx,-10,sx+(int)(segment*Math.clamp(values[i],0,100)/100),-5,values[i]<35?0xfff15c51:0xff6bc48b);
            m.push();m.translate(sx+segment/2.0,-21,0);m.scale(.65f,.65f,1);
            ctx.drawCenteredTextWithShadow(mc.textRenderer,Text.translatable("hud.no_24_heli."+keys[i]),0,0,0xffd8e8d9);m.pop();
        }
        // 右下从右向左依次为空速盘、垂速竖条、高度及带正负号的垂速数字。
        gauge(ctx,bw-40,-40,35,"airspeed",Math.hypot(s.vx,s.vz)*72,0,160,"km/h",0xff7ce3bc);
        verticalTape(ctx,bw-104,-75,70,s.vy*20);
        int numberX=bw-192;
        text(ctx,"hud.no_24_heli.altimeter",numberX,-70,0xff7ce3bc);
        ctx.drawText(mc.textRenderer,String.format(Locale.ROOT,"%.0f m",h.getY()),numberX,-57,0xffe6f4ee,true);
        text(ctx,"hud.no_24_heli.vario",numberX,-36,0xff7ce3bc);
        ctx.drawText(mc.textRenderer,String.format(Locale.ROOT,"%+.1f m/s",s.vy*20),numberX,-23,0xffe6f4ee,true);
        m.pop();
        int warningY=10;
        if(s.tail<20){warn(ctx,"tail",warningY);warningY+=11;}
        if(s.rotor<50){warn(ctx,"rotor",warningY);warningY+=11;}
        if(s.fuel<80){warn(ctx,"fuel",warningY);warningY+=11;}
        if(!s.running&&!h.isOnGround()&&s.armed){warn(ctx,"autorotation",warningY);warningY+=11;}
        if(s.running&&!s.armed){warn(ctx,"spooling",warningY);warningY+=11;}
        if(s.restricted){warn(ctx,"restricted",warningY);warningY+=11;}
        int proximity=GroundProximityWarning.level();
        if(proximity>0){
            int[] bands={0,30,20,10,5};
            int color=proximity>=3?(h.age%8<4?0xffff6655:0xffffd66b):0xffffd66b;
            text(ctx,"warning.no_24_heli.ground",10,warningY,color,bands[proximity]);
        }
        // 右上缩小地平仪，旋转线条真实表达俯仰和横滚。
        int cx=w-143,cy=32;ctx.fill(cx-25,cy-25,cx+25,cy+25,0xa0193329);
        m.push();m.translate(cx,cy,0);m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float)-s.roll));
        int offset=(int)Math.clamp(s.pitch*.5,-10,10);
        ctx.fill(-17,offset-1,17,offset+1,0xff90e0b4);
        ctx.fill(-8,offset-8,8,offset-7,0xff547969);ctx.fill(-8,offset+7,8,offset+8,0xff547969);m.pop();
        ctx.fill(cx-14,cy,cx-4,cy+1,0xffffd66b);ctx.fill(cx+4,cy,cx+14,cy+1,0xffffd66b);ctx.fill(cx-1,cy-2,cx+1,cy+3,0xffffd66b);
        HeliRadar.draw(ctx,h,w-4);
        m.pop();
    }
    /** 中线代表零垂速；正值上移、负值下移，超量程只限制指针，旁边数字保留真实值。 */
    private static void verticalTape(DrawContext ctx,int x,int top,int height,double speed){
        int mid=top+height/2;
        ctx.fill(x,top,x+25,top+height,0xdc101c1b);
        ctx.fill(x,top,x+1,top+height,0xff53716a);ctx.fill(x+24,top,x+25,top+height,0xff53716a);
        for(int i=-4;i<=4;i++){
            int y=mid-i*(height-8)/8;ctx.fill(x+2,y,x+(i==0?22:10),y+1,i==0?0xffe6f4ee:0xff53716a);
        }
        int pointer=mid-(int)Math.round(Math.clamp(speed/20,-1,1)*(height-8)/2);
        for(int i=0;i<6;i++)ctx.fill(x+12+i,pointer-i/2,x+13+i,pointer+i/2+1,0xffffd66b);
        ctx.drawText(MinecraftClient.getInstance().textRenderer,"+",x+14,top+2,0xff7ce3bc,false);
        ctx.drawText(MinecraftClient.getInstance().textRenderer,"-",x+14,top+height-10,0xff7ce3bc,false);
    }
    /** 刻度、指针和数字共用量程；超量程仅钳制指针，数字保持实际值。 */
    private static void gauge(DrawContext ctx,int cx,int cy,int radius,String label,double value,double min,double max,String unit,int color){
        var mc=MinecraftClient.getInstance();
        for(int y=-radius;y<=radius;y++){int half=(int)Math.sqrt(radius*radius-y*y);ctx.fill(cx-half,cy+y,cx+half+1,cy+y+1,0xdc101c1b);}
        HeliRadar.circle(ctx,cx,cy,radius,0xff53716a);
        for(int i=0;i<=8;i++){
            double a=Math.toRadians(135+i*270.0/8);
            line(ctx,cx+(int)(Math.cos(a)*(radius-3)),cy+(int)(Math.sin(a)*(radius-3)),cx+(int)(Math.cos(a)*(radius-7)),cy+(int)(Math.sin(a)*(radius-7)),color);
        }
        double a=Math.toRadians(135+Math.clamp((value-min)/(max-min),0,1)*270);
        line(ctx,cx,cy,cx+(int)(Math.cos(a)*(radius-9)),cy+(int)(Math.sin(a)*(radius-9)),0xffffd66b);
        ctx.fill(cx-2,cy-2,cx+3,cy+3,0xffffd66b);
        var m=ctx.getMatrices();m.push();m.translate(cx,cy-radius/2f,0);m.scale(.85f,.85f,1);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,Text.translatable("hud.no_24_heli."+label),0,0,color);m.pop();
        m.push();m.translate(cx,cy+radius*.43,0);m.scale(.75f,.75f,1);
        //ctx.fill(-28,-1,29,10,0xff101c1b)
        ;ctx.drawCenteredTextWithShadow(mc.textRenderer,f(value)+unit,0,0,0xffe6f4ee);m.pop();
    }
    private static void line(DrawContext ctx,int x,int y,int ex,int ey,int color){
        int steps=Math.max(Math.abs(ex-x),Math.abs(ey-y));
        for(int i=0;i<=steps;i++){int px=x+(ex-x)*i/Math.max(steps,1),py=y+(ey-y)*i/Math.max(steps,1);ctx.fill(px,py,px+1,py+1,color);}
    }
    private static void warn(DrawContext ctx,String key,int y){text(ctx,"warning.no_24_heli."+key,10,y,0xffff6a58);}
}
