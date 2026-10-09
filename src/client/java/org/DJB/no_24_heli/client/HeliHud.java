package org.DJB.no_24_heli.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import org.DJB.no_24_heli.*;
import java.util.Locale;

/** 紧凑三分区仪表：飞行数据左上、机体状态左下、姿态仪右上。 */
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
        // 左上只保留飞行数据；旋翼、燃油和部件统一归入左下状态区。
        ctx.fill(6,6,160,44,0xa015211c);
        text(ctx,"hud.no_24_heli.speed",x,10,0xffb6edc8,f(Math.hypot(s.vx,s.vz)*72));
        text(ctx,"hud.no_24_heli.height",x,21,0xffb6edc8,f(h.getY()));
        text(ctx,"hud.no_24_heli.vertical",x,32,0xffb6edc8,f(s.vy*20));
        int y=height-164;
        ctx.fill(6,y-4,160,y+104,0xa015211c);
        text(ctx,s.hardcoreMode?"hud.no_24_heli.state_mamba":"hud.no_24_heli.state",x,y,0xffffd66b);y+=12;
        text(ctx,"hud.no_24_heli.fuel",x,y,0xffb6edc8,f(s.fuel));y+=11;
        text(ctx,"hud.no_24_heli.rpm",x,y,0xffb6edc8,(int)(s.rpm*100),(int)(s.collective*100));y+=13;
        double[] values={s.hull,s.rotor,s.tail,s.engine,s.tank};String[] keys={"hull","rotor","tail","engine","tank"};
        for(int i=0;i<values.length;i++){
            text(ctx,"hud.no_24_heli."+keys[i],x,y,0xffd8e8d9);
            ctx.fill(x+45,y+2,x+140,y+6,0xff48564a);
            ctx.fill(x+45,y+2,x+45+(int)(Math.clamp(values[i],0,100)*.95),y+6,values[i]<35?0xfff15c51:0xff6bc48b);y+=10;
        }
        String status="H "+(s.altitudeHold?"●":"○")+"  J "+(s.trim?"●":"○")+"  I "+(s.cruise?"●":"○")+"  G "+(s.gear?"↓":"↑");
        ctx.drawText(mc.textRenderer,status,x,y+2,0xffb6edc8,true);
        int warningY=height-179;
        if(s.tail<20){warn(ctx,"tail",warningY);warningY-=11;}
        if(s.rotor<50){warn(ctx,"rotor",warningY);warningY-=11;}
        if(s.fuel<80){warn(ctx,"fuel",warningY);warningY-=11;}
        if(!s.running&&!h.isOnGround()&&s.armed){warn(ctx,"autorotation",warningY);warningY-=11;}
        if(s.running&&!s.armed)warn(ctx,"spooling",warningY);
        // 右上缩小地平仪，旋转线条真实表达俯仰和横滚。
        int cx=w-32,cy=32;ctx.fill(cx-25,cy-25,cx+25,cy+25,0xa0193329);
        m.push();m.translate(cx,cy,0);m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float)-s.roll));
        int offset=(int)Math.clamp(s.pitch*.5,-10,10);
        ctx.fill(-17,offset-1,17,offset+1,0xff90e0b4);
        ctx.fill(-8,offset-8,8,offset-7,0xff547969);ctx.fill(-8,offset+7,8,offset+8,0xff547969);m.pop();
        ctx.fill(cx-14,cy,cx-4,cy+1,0xffffd66b);ctx.fill(cx+4,cy,cx+14,cy+1,0xffffd66b);ctx.fill(cx-1,cy-2,cx+1,cy+3,0xffffd66b);
        m.pop();
    }
    private static void warn(DrawContext ctx,String key,int y){text(ctx,"warning.no_24_heli."+key,10,y,0xffff6a58);}
}
