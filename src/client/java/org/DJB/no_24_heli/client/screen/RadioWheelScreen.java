package org.DJB.no_24_heli.client.screen;

import org.DJB.no_24_heli.client.audio.RadioLibrary;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.network.RadioPayload;




import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** 按住Tab打开八等分圆盘，移动鼠标预选，松开Tab确认；中心圆用于关闭电台。 */
public final class RadioWheelScreen extends Screen {
    private int selected=-1;
    private boolean released;
    private final boolean[] available=new boolean[8];
    public RadioWheelScreen(){super(Text.translatable("screen.no_24_heli.radio"));}
    @Override protected void init(){for(int i=0;i<8;i++)available[i]=RadioLibrary.available(i);}
    /** 正上方是槽位1，顺时针排列；中心24像素为关台区，避免小幅鼠标漂移误切歌。 */
    public static int selection(double dx,double dy){
        if(Math.hypot(dx,dy)<24)return -1;
        double angle=(Math.atan2(dx,-dy)+Math.PI*2+Math.PI/8)%(Math.PI*2);return (int)(angle/(Math.PI/4));
    }
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);int cx=width/2,cy=height/2;selected=selection(mx-cx,my-cy);
        int radius=Math.min(90,height/2-35);
        // 用逐行圆内像素填充分区，避免引入额外贴图；只在菜单打开时绘制，半径受屏幕高度限制。
        for(int y=-radius;y<=radius;y++){
            int edge=(int)Math.sqrt(radius*radius-y*y),x=-edge;
            while(x<=edge){
                int start=x,sector=selection(x,y);while(x<=edge&&selection(x,y)==sector)x++;
                int color=sector==selected?0xdd6f8650:0xcc172c26;ctx.fill(cx+start,cy+y,cx+x,cy+y+1,color);
            }
        }
        for(int i=0;i<8;i++){
            double a=i*Math.PI/4;int x=cx+(int)(Math.sin(a)*radius*.72),y=cy-(int)(Math.cos(a)*radius*.72);
            ctx.drawCenteredTextWithShadow(textRenderer,Integer.toString(i+1),x,y-5,available[i]?0xffffe5a1:0xff87958c);
            if(!available[i])ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("radio.no_24_heli.empty"),x,y+7,0xff87958c);
        }
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("radio.no_24_heli.off"),cx,cy-4,0xffffffff);
        ctx.drawCenteredTextWithShadow(textRenderer,title,cx,cy-radius-24,0xffffff);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("radio.no_24_heli.hint"),cx,cy+radius+14,0xffc5d8c7);
    }
    @Override public void tick(){
        // 服务器目录可能在圆盘打开后才到达，逐tick刷新，避免把仍在同步的槽位一直显示为空。
        for(int i=0;i<8;i++)available[i]=RadioLibrary.available(i);
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity)){close();return;}
        if(!InputUtil.isKeyPressed(client.getWindow().getHandle(),GLFW.GLFW_KEY_TAB)&&!released){
            released=true;
            if(selected<0||available[selected])ClientPlayNetworking.send(new RadioPayload(selected));
            else client.player.sendMessage(Text.translatable("radio.no_24_heli.missing",selected+1),true);
            close();
        }
    }
    @Override public boolean shouldPause(){return false;}
    // 拦截原版Tab焦点遍历；选择只由鼠标方向决定，Esc则取消本次选择。
    @Override public boolean keyPressed(int key,int scan,int mods){return key==GLFW.GLFW_KEY_TAB||super.keyPressed(key,scan,mods);}
}
