package org.DJB.no_24_heli.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import org.DJB.no_24_heli.HelicopterEntity;
import org.DJB.no_24_heli.HelipadBlock;
import org.DJB.no_24_heli.NavigationPayload;
import org.DJB.no_24_heli.RadarContactsPayload;

/** 北向探索地图：显示已记录区块，卸载后继续从本地缓存读取；未知区域留纸色。 */
@Environment(EnvType.CLIENT)
public final class HeliMapScreen extends Screen {
    private double centerX,centerZ,blocksPerPixel=2;
    private int left,top,mapWidth,mapHeight;
    private static final int PIXELS_X=192,PIXELS_Y=112;
    private net.minecraft.client.texture.NativeImageBackedTexture texture;
    private net.minecraft.util.Identifier textureId;
    private long mapRevision=-1;
    private boolean dirty=true;
    private long sampleTime;
    /** 悬停选中的停机坪中心；移开鼠标仍保留，点击空白、拖图或完成设点后清除。 */
    private BlockPos selectedPad;
    /** 弹层使用真实 net.minecraft.client.gui.widget.ButtonWidget，支持鼠标点击与键盘焦点。 */
    private ButtonWidget padButton;
    /** 弹层边界用于阻止底下地图抢走点击；高度单位为 GUI 逻辑像素。 */
    private static final int PAD_POPUP_HEIGHT=62;
    private int padPopupX,padPopupY,padPopupWidth;
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
        padButton=addSelectableChild(ButtonWidget.builder(Text.translatable("screen.no_24_heli.set_waypoint"),
                button->setPadWaypoint()).dimensions(0,0,128,20).build());
        padButton.visible=false;padButton.active=false;
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
    /** 地图命中只限地图内部，避免裁切之外的标记隔着边框触发弹层。 */
    private boolean insideMap(double x,double y){return x>=left&&x<left+mapWidth&&y>=top&&y<top+mapHeight;}
    /** 弹层包含按钮与标题间的空隙，鼠标从标记移动到按钮期间保持选中。 */
    private boolean insidePadPopup(double x,double y){
        return padButton.visible&&x>=padPopupX&&x<padPopupX+padPopupWidth
                &&y>=padPopupY&&y<padPopupY+PAD_POPUP_HEIGHT;
    }
    /** 在七像素命中半径内选最近的 H 标记，位置来自本机当前服务端扫描快照。 */
    private RadarContactsPayload.Helipad padAt(double x,double y,HelicopterEntity heli){
        if(!insideMap(x,y))return null;
        RadarContactsPayload.Helipad result=null;double closest=7*7;
        for(var pad:HeliRadar.helipads(heli)){
            int sx=px(pad.pos().getX()+.5),sy=py(pad.pos().getZ()+.5);
            if(!insideMap(sx,sy))continue;
            double distance=(x-sx)*(x-sx)+(y-sy)*(y-sy);
            if(distance<=closest){closest=distance;result=pad;}
        }
        return result;
    }
    /**
     * 悬停只负责选中，不以离开标记作为隐藏条件；弹层中移动不被背后的其他 H 标记抢占。
     * 扫描目标消失、移出地图或主动点击空白时才清除；禁区变化实时更新按钮可用状态。
     */
    private void renderPadPopup(DrawContext ctx,int mx,int my){
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity heli)){
            selectedPad=null;padButton.visible=false;return;
        }
        if(!insidePadPopup(mx,my)){
            var hovered=padAt(mx,my,heli);
            if(hovered!=null)selectedPad=hovered.pos();
        }
        var pad=HeliRadar.helipads(heli).stream().filter(p->p.pos().equals(selectedPad)).findFirst().orElse(null);
        if(pad==null||!insideMap(px(pad.pos().getX()+.5),py(pad.pos().getZ()+.5))){
            selectedPad=null;padButton.visible=false;return;
        }
        boolean restricted=HeliRadar.restricted(pad);
        Text label=Text.translatable("screen.no_24_heli.pad_location",pad.pos().getX(),pad.pos().getZ());
        Text reason=Text.translatable("screen.no_24_heli.pad_restricted");
        padPopupWidth=Math.min(width-12,Math.max(144,Math.max(textRenderer.getWidth(label),textRenderer.getWidth(reason))+16));
        padPopupX=Math.clamp(px(pad.pos().getX()+.5)+10,6,Math.max(6,width-padPopupWidth-6));
        padPopupY=Math.clamp(py(pad.pos().getZ()+.5)+10,top,Math.max(top,top+mapHeight-PAD_POPUP_HEIGHT));
        padButton.setX(padPopupX+8);padButton.setY(padPopupY+34);padButton.setWidth(padPopupWidth-16);
        padButton.visible=true;padButton.active=!restricted;
        ctx.fill(padPopupX-1,padPopupY-1,padPopupX+padPopupWidth+1,padPopupY+PAD_POPUP_HEIGHT+1,0xff709184);
        ctx.fill(padPopupX,padPopupY,padPopupX+padPopupWidth,padPopupY+PAD_POPUP_HEIGHT,0xf0182927);
        ctx.drawTextWithShadow(textRenderer,label,padPopupX+8,padPopupY+6,0xffc7ffe8);
        if(restricted)ctx.drawTextWithShadow(textRenderer,reason,padPopupX+8,padPopupY+20,0xffff8877);
    }
    /** 提交精确的圆盘中心；禁用状态在点击时再次读取，最终由服务端复核真实禁区。 */
    private void setPadWaypoint(){
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity heli))return;
        var pad=HeliRadar.helipads(heli).stream().filter(p->p.pos().equals(selectedPad)).findFirst().orElse(null);
        if(pad==null)return;
        if(HeliRadar.restricted(pad))client.player.sendMessage(Text.translatable("message.no_24_heli.pad_restricted"),true);
        else ClientPlayNetworking.send(new NavigationPayload(pad.pos().getX()+.5,pad.pos().getZ()+.5,false));
        selectedPad=null;padButton.visible=false;
    }
    /**
     * 北向地图的箭头尖端指向机头：Minecraft 偏航 0 为南、90 为西，基准朝北的图形需旋转 yaw+180°。
     * net.minecraft.client.util.math.MatrixStack 成对保存/还原，仅改变图标，不改玩家或飞机朝向。
     */
    private static void headingArrow(DrawContext ctx,int x,int y,float yaw,int color){
        var matrices=ctx.getMatrices();matrices.push();matrices.translate(x,y,0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(yaw+180));
        for(int row=0;row<5;row++)ctx.fill(-row,-6+row,row+1,-5+row,color);
        ctx.fill(-1,-2,2,6,color);matrices.pop();
    }
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
        for(Entity entity:HeliRadar.targets()){
            int x=px(entity.getX()),y=py(entity.getZ());
            if(entity instanceof HelicopterEntity)headingArrow(ctx,x,y,entity.getYaw(delta),0xffffbb55);
            else ctx.fill(x-2,y-2,x+3,y+3,0xff66ccff);
        }
        if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli)
            for(var pad:HeliRadar.helipads(heli)){
                int x=px(pad.pos().getX()+.5),y=py(pad.pos().getZ()+.5);
                int color=HeliRadar.restricted(pad)?0xffff6655:0xff7fffd4;
                ctx.fill(x-6,y-6,x+7,y+7,color);ctx.fill(x-5,y-5,x+6,y+6,0xff19342e);
                ctx.drawCenteredTextWithShadow(textRenderer,"H",x,y-4,color);
            }
        if(client.player!=null){
            int x=px(client.player.getX()),y=py(client.player.getZ());
            if(client.player.getVehicle() instanceof HelicopterEntity heli)
                headingArrow(ctx,px(heli.getX()),py(heli.getZ()),heli.getYaw(delta),0xffffffff);
            else ctx.fill(x-2,y-3,x+3,y+4,0xffffffff);
            if(client.player.getVehicle() instanceof HelicopterEntity heli&&heli.flight.waypoint){
                x=px(heli.flight.waypointX);y=py(heli.flight.waypointZ);HeliRadar.circle(ctx,x,y,5,0xffffff55);
                ctx.fill(x-7,y,x+8,y+1,0xffffff55);ctx.fill(x,y-7,x+1,y+8,0xffffff55);
            }
        }
        ctx.disableScissor();
        ctx.drawText(textRenderer,"N ↑   "+(int)(mapWidth*blocksPerPixel)+" m",left+4,top+4,0xffffffff,true);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.map_hint"),width/2,height-58,0xccddcc);
        ctx.drawCenteredTextWithShadow(textRenderer,Text.translatable("screen.no_24_heli.map_legend"),width/2,height-44,0xccddcc);
        renderPadPopup(ctx,mx,my);
        // 背景模糊由开头的 Screen.render 处理；弹层按钮手动最后绘制，不再次模糊地图。
        if(padButton.visible)padButton.render(ctx,mx,my,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(super.mouseClicked(x,y,button))return true;
        if(insidePadPopup(x,y))return true;
        if(button==0&&insideMap(x,y)){
            var pad=client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli?padAt(x,y,heli):null;
            selectedPad=pad==null?null:pad.pos();if(pad==null)padButton.visible=false;
            return true;
        }
        if(button==1&&insideMap(x,y)){
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity heli){
                var pad=padAt(x,y,heli);
                if(pad!=null){selectedPad=pad.pos();setPadWaypoint();return true;}
                selectedPad=null;padButton.visible=false;
                double wx=centerX+(x-left-mapWidth/2.0)*blocksPerPixel,wz=centerZ+(y-top-mapHeight/2.0)*blocksPerPixel;
                if(HeliRadar.zones.stream().anyMatch(zone->Math.hypot(wx-zone.pos().getX()-.5,wz-zone.pos().getZ()-.5)<=zone.radius())
                        ||HeliRadar.helipads(heli).stream().anyMatch(p->HeliRadar.restricted(p)
                            &&Math.hypot(wx-p.pos().getX()-.5,wz-p.pos().getZ()-.5)<=HelipadBlock.RADIUS))
                    client.player.sendMessage(Text.translatable("message.no_24_heli.waypoint_restricted"),true);
                else ClientPlayNetworking.send(new NavigationPayload(wx,wz,false));
            }
            return true;
        }
        selectedPad=null;padButton.visible=false;return false;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&insideMap(x,y)&&!insidePadPopup(x,y)){
            selectedPad=null;padButton.visible=false;
            centerX-=dx*blocksPerPixel;centerZ-=dy*blocksPerPixel;dirty=true;return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
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
