package org.DJB.no_24_heli.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.DJB.no_24_heli.*;

/** 北向探索地图：显示已记录区块，卸载后继续从本地缓存读取；未知区域留纸色。 */
public final class HeliMapScreen extends Screen {
    private double centerX,centerZ,blocksPerPixel=2;
    private int left,top,size;
    private final int[] terrain=new int[96*96];
    private boolean dirty=true;
    private long terrainTick=-100;
    public HeliMapScreen(){super(Text.translatable("screen.no_24_heli.map"));}
    @Override protected void init(){
        size=Math.max(64,Math.min(width-32,height-96));left=(width-size)/2;top=30;
        if(client.player!=null){centerX=client.player.getX();centerZ=client.player.getZ();}
        dirty=true;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.no_24_heli.clear_waypoint"),b->{
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity)
                ClientPlayNetworking.send(new NavigationPayload(0,0,true));
        }).dimensions(width/2-102,height-28,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(width/2+2,height-28,100,20).build());
    }
    /** 限频缓存 96×96 色块；每次缩放、拖动或每秒最多重采样一次。 */
    private void sample(){
        if(client.world==null)return;
        if(!dirty&&client.world.getTime()-terrainTick<20)return;
        terrainTick=client.world.getTime();dirty=false;
        for(int j=0;j<96;j++)for(int i=0;i<96;i++){
            int x=(int)Math.floor(centerX+(i/96.0-.5)*size*blocksPerPixel),z=(int)Math.floor(centerZ+(j/96.0-.5)*size*blocksPerPixel);
            int color=ExploredMap.color(x,z);
            terrain[j*96+i]=color;
        }
    }
    private int px(double x){return left+size/2+(int)((x-centerX)/blocksPerPixel);}
    private int py(double z){return top+size/2+(int)((z-centerZ)/blocksPerPixel);}
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);sample();
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xffffff);
        // 原版地图色配合羊皮纸底和深色边框；未知区域留纸色，不伪造尚未探索的地形。
        ctx.fill(left-6,top-6,left+size+6,top+size+6,0xff6d5030);
        ctx.fill(left-4,top-4,left+size+4,top+size+4,0xffe2cd98);
        ctx.enableScissor(left,top,left+size,top+size);
        for(int j=0;j<96;j++)for(int i=0;i<96;i++)ctx.fill(left+i*size/96,top+j*size/96,left+(i+1)*size/96+1,top+(j+1)*size/96+1,terrain[j*96+i]);
        for(var zone:HeliRadar.zones){int x=px(zone.pos().getX()+.5),y=py(zone.pos().getZ()+.5);HeliRadar.circle(ctx,x,y,(int)(zone.radius()/blocksPerPixel),0xffff6655);ctx.fill(x-2,y-2,x+3,y+3,0xffff6655);}
        for(Entity entity:HeliRadar.targets()){int x=px(entity.getX()),y=py(entity.getZ());ctx.fill(x-2,y-2,x+3,y+3,entity instanceof HelicopterEntity?0xffffbb55:0xff66ccff);}
        if(client.player!=null){
            int x=px(client.player.getX()),y=py(client.player.getZ());ctx.fill(x-2,y-3,x+3,y+4,0xffffffff);
            if(client.player.getVehicle() instanceof HelicopterEntity heli&&heli.flight.waypoint){
                x=px(heli.flight.waypointX);y=py(heli.flight.waypointZ);HeliRadar.circle(ctx,x,y,5,0xffffff55);
                ctx.fill(x-7,y,x+8,y+1,0xffffff55);ctx.fill(x,y-7,x+1,y+8,0xffffff55);
            }
        }
        ctx.disableScissor();
        ctx.drawText(textRenderer,"N ↑   "+(int)(size*blocksPerPixel)+" m",left+4,top+4,0xffffffff,true);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.map_hint"),width/2,height-58,0xccddcc);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.map_legend"),width/2,height-44,0xccddcc);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==1&&x>=left&&x<left+size&&y>=top&&y<top+size){
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli&&heli.isPilot(client.player))
                ClientPlayNetworking.send(new NavigationPayload(centerX+(x-left-size/2.0)*blocksPerPixel,centerZ+(y-top-size/2.0)*blocksPerPixel,false));
            return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&x>=left&&x<left+size&&y>=top&&y<top+size){centerX-=dx*blocksPerPixel;centerZ-=dy*blocksPerPixel;dirty=true;return true;}return super.mouseDragged(x,y,button,dx,dy);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        blocksPerPixel=Math.clamp(blocksPerPixel*Math.pow(1.2,-vertical),.5,16);dirty=true;return true;
    }
    /** Screen会吞掉普通按键绑定，故在地图本身处理M；重绑后的地图键同样生效。 */
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(No_24_heliClient.mapKeyMatches(key,scan)){close();return true;}return super.keyPressed(key,scan,modifiers);
    }
    @Override public void tick(){
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity))close();
    }
    @Override public boolean shouldPause(){return false;}
}
