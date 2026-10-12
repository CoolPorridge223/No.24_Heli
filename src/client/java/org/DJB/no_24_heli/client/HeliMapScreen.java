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
    private int left,top,mapWidth,mapHeight;
    private static final int PIXELS_X=192,PIXELS_Y=112;
    private net.minecraft.client.texture.NativeImageBackedTexture texture;
    private net.minecraft.util.Identifier textureId;
    private long mapRevision=-1;
    private boolean dirty=true;
    private long sampleTime;
    public HeliMapScreen(){super(Text.translatable("screen.no_24_heli.map"));}
    @Override protected void init(){
        mapWidth=Math.max(64,width-40);mapHeight=Math.max(48,height-112);left=(width-mapWidth)/2;top=30;
        if(texture==null){texture=new net.minecraft.client.texture.NativeImageBackedTexture(PIXELS_X,PIXELS_Y,false);textureId=client.getTextureManager().registerDynamicTexture("no24_navigation",texture);}
        if(client.player!=null){centerX=client.player.getX();centerZ=client.player.getZ();}
        dirty=true;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.no_24_heli.clear_waypoint"),b->{
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity)
                ClientPlayNetworking.send(new NavigationPayload(0,0,true));
        }).dimensions(width/2-102,height-28,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(width/2+2,height-28,100,20).build());
    }
    /** 地形只在变更时以最多10Hz更新GPU纹理，每帧仅提交一个地图四边形。 */
    private void sample(){
        if(client.world==null||texture==null)return;
        long now=System.nanoTime();if(now-sampleTime<100_000_000L)return;
        if(!dirty&&mapRevision==ExploredMap.revision())return;
        sampleTime=now;dirty=false;mapRevision=ExploredMap.revision();
        var image=texture.getImage();
        for(int j=0;j<PIXELS_Y;j++)for(int i=0;i<PIXELS_X;i++){
            int x=(int)Math.floor(centerX+(i/(double)PIXELS_X-.5)*mapWidth*blocksPerPixel);
            int z=(int)Math.floor(centerZ+(j/(double)PIXELS_Y-.5)*mapHeight*blocksPerPixel);
            int argb=ExploredMap.color(x,z);
            image.setColor(i,j,(argb&0xff00ff00)|((argb>>>16)&255)|((argb&255)<<16));
        }
        texture.upload();
    }
    private int px(double x){return left+mapWidth/2+(int)((x-centerX)/blocksPerPixel);}
    private int py(double z){return top+mapHeight/2+(int)((z-centerZ)/blocksPerPixel);}
    /** 关闭/切换屏幕释放GPU纹理；区块缓存继续跨次打开复用。 */
    @Override public void removed(){if(textureId!=null)client.getTextureManager().destroyTexture(textureId);texture=null;textureId=null;}
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);sample();
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xffffff);
        // 原版地图色配合羊皮纸底和深色边框；未知区域留纸色，不伪造尚未探索的地形。
        ctx.fill(left-6,top-6,left+mapWidth+6,top+mapHeight+6,0xff6d5030);
        ctx.fill(left-4,top-4,left+mapWidth+4,top+mapHeight+4,0xffe2cd98);
        ctx.enableScissor(left,top,left+mapWidth,top+mapHeight);
        if(textureId!=null)ctx.drawTexture(textureId,left,top,mapWidth,mapHeight,0,0,PIXELS_X,PIXELS_Y,PIXELS_X,PIXELS_Y);
        for(var zone:HeliRadar.zones){
            double radius=zone.radius()/blocksPerPixel;int zx=px(zone.pos().getX()+.5),zy=py(zone.pos().getZ()+.5);
            if(zx+radius<left||zx-radius>left+mapWidth||zy+radius<top||zy-radius>top+mapHeight)continue;
            int x=px(zone.pos().getX()+.5),y=py(zone.pos().getZ()+.5);HeliRadar.circle(ctx,x,y,(int)(zone.radius()/blocksPerPixel),0xffff6655);ctx.fill(x-2,y-2,x+3,y+3,0xffff6655);}
        for(Entity entity:HeliRadar.targets()){int x=px(entity.getX()),y=py(entity.getZ());ctx.fill(x-2,y-2,x+3,y+3,entity instanceof HelicopterEntity?0xffffbb55:0xff66ccff);}
        if(client.player!=null){
            int x=px(client.player.getX()),y=py(client.player.getZ());ctx.fill(x-2,y-3,x+3,y+4,0xffffffff);
            if(client.player.getVehicle() instanceof HelicopterEntity heli&&heli.flight.waypoint){
                x=px(heli.flight.waypointX);y=py(heli.flight.waypointZ);HeliRadar.circle(ctx,x,y,5,0xffffff55);
                ctx.fill(x-7,y,x+8,y+1,0xffffff55);ctx.fill(x,y-7,x+1,y+8,0xffffff55);
            }
        }
        ctx.disableScissor();
        ctx.drawText(textRenderer,"N ↑   "+(int)(mapWidth*blocksPerPixel)+" m",left+4,top+4,0xffffffff,true);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.map_hint"),width/2,height-58,0xccddcc);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.map_legend"),width/2,height-44,0xccddcc);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==1&&x>=left&&x<left+mapWidth&&y>=top&&y<top+mapHeight){
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli){
                double wx=centerX+(x-left-mapWidth/2.0)*blocksPerPixel,wz=centerZ+(y-top-mapHeight/2.0)*blocksPerPixel;
                if(HeliRadar.zones.stream().anyMatch(zone->Math.hypot(wx-zone.pos().getX()-.5,wz-zone.pos().getZ()-.5)<=zone.radius()))
                    client.player.sendMessage(Text.translatable("message.no_24_heli.waypoint_restricted"),true);
                else ClientPlayNetworking.send(new NavigationPayload(wx,wz,false));
            }
            return true;
        }
        return super.mouseClicked(x,y,button);
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&x>=left&&x<left+mapWidth&&y>=top&&y<top+mapHeight){centerX-=dx*blocksPerPixel;centerZ-=dy*blocksPerPixel;dirty=true;return true;}return super.mouseDragged(x,y,button,dx,dy);
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
