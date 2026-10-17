package org.DJB.no_24_heli.client.screen;

import org.DJB.no_24_heli.block.HelipadBlock;
import org.DJB.no_24_heli.client.No_24_heliClient;
import org.DJB.no_24_heli.client.hud.HeliRadar;
import org.DJB.no_24_heli.client.map.MapTerrainRenderer;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.network.HelipadMapPayload;
import org.DJB.no_24_heli.network.NavigationPayload;





import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;

/** 北向探索地图：显示已记录区块，卸载后继续从本地缓存读取；未知区域留纸色。 */
@Environment(EnvType.CLIENT)
public final class HeliMapScreen extends Screen {
    private double centerX,centerZ,blocksPerPixel=2;
    private int left,top,mapWidth,mapHeight;
    /** 地形按区块增量上传，GPU 每帧缩放缓存页；不再有整屏 10 Hz 重采样。 */
    private final MapTerrainRenderer terrain=new MapTerrainRenderer();
    /** 停机坪圆形按钮的半径，单位为 GUI 逻辑像素；绘制与点击共用，缩放地图不改变尺寸。 */
    private static final int PAD_ICON_RADIUS=7;
    /** 其他飞机的指针为本机的八成，单位为尺寸比例；地图缩放不改变此比例。 */
    private static final float OTHER_AIRCRAFT_SCALE=.8f;
    /** 点击停机坪后到松开左键期间消费拖动，防止按下按钮同时带动地图。 */
    private boolean padClickConsumed;
    public HeliMapScreen(){super(Text.translatable("screen.no_24_heli.map"));}
    @Override protected void init(){
        mapWidth=Math.max(64,width-40);mapHeight=Math.max(48,height-112);left=(width-mapWidth)/2;top=30;
        if(client.player!=null){centerX=client.player.getX();centerZ=client.player.getZ();}
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.no_24_heli.clear_waypoint"),b->{
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity)
                ClientPlayNetworking.send(new NavigationPayload(0,0,true));
        }).dimensions(width/2-102,height-28,100,20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"),b->close()).dimensions(width/2+2,height-28,100,20).build());
    }
    private int px(double x){return left+mapWidth/2+(int)((x-centerX)/blocksPerPixel);}
    private int py(double z){return top+mapHeight/2+(int)((z-centerZ)/blocksPerPixel);}
    /** 地图命中只限地图内部，避免裁切之外的标记隔着边框触发导航。 */
    private boolean insideMap(double x,double y){return x>=left&&x<left+mapWidth&&y>=top&&y<top+mapHeight;}
    /**
     * 功能：在圆形按钮内选最近的停机坪；读取独立地图登记表，离开雷达范围仍可命中。
     * 引用 MC API：net.minecraft.client.gui.screen.Screen 提供 GUI 鼠标坐标，按逻辑像素比较。
     * 端侧/线程：客户端界面线程；若可见但无法点击，检查图标半径与地图裁切边界。
     */
    private HelipadMapPayload.Entry padAt(double x,double y){
        if(!insideMap(x,y))return null;
        HelipadMapPayload.Entry result=null;double closest=PAD_ICON_RADIUS*PAD_ICON_RADIUS;
        for(var pad:HeliRadar.mapHelipads()){
            int sx=px(pad.pos().getX()+.5),sy=py(pad.pos().getZ()+.5);
            double distance=(x-sx)*(x-sx)+(y-sy)*(y-sy);
            if(distance<=closest){closest=distance;result=pad;}
        }
        return result;
    }
    /** 提交精确的圆盘中心；禁用状态在点击时再次读取，最终由服务端复核真实禁区。 */
    private void setPadWaypoint(HelipadMapPayload.Entry pad){
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity))return;
        if(HeliRadar.restricted(pad.pos()))client.player.sendMessage(Text.translatable("message.no_24_heli.pad_restricted"),true);
        else ClientPlayNetworking.send(new NavigationPayload(pad.pos().getX()+.5,pad.pos().getZ()+.5,false));
    }
    /**
     * 功能：绘制实心圆供停机坪按钮的边框和底色复用，H 保持居中且不随地图缩放。
     * 引用 MC API：net.minecraft.client.gui.DrawContext#fill 使用单像素高的横条填充圆盘。
     * 端侧/线程：客户端渲染线程；若圆形被截断，先检查地图裁切区，再检查像素半径。
     */
    private static void filledCircle(DrawContext ctx,int x,int y,int radius,int color){
        for(int row=-radius;row<=radius;row++){
            int half=(int)Math.floor(Math.sqrt(radius*radius-row*row));
            ctx.fill(x-half,y+row,x+half+1,y+row+1,color);
        }
    }
    /**
     * 北向地图的箭头尖端指向机头：Minecraft 偏航 0 为南、90 为西，基准朝北的图形需旋转 yaw+180°。
     * net.minecraft.client.util.math.MatrixStack 成对保存/还原，仅改变图标，不改玩家或飞机朝向。
     */
    private static void headingArrow(DrawContext ctx,int x,int y,float yaw,int color,float scale){
        var matrices=ctx.getMatrices();matrices.push();matrices.translate(x,y,0);
        matrices.scale(scale,scale,1);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(yaw+180));
        // 11×12 逻辑像素的无柄导航箭头；尾端向内收口，避免与实体方点或停机坪 H 混淆。
        for(int row=-6;row<=5;row++){
            int half=(row+6)*5/11;
            if(row<=2)ctx.fill(-half,row,half+1,row+1,color);
            else{
                int notch=(row-2)*5/3;
                ctx.fill(-half,row,-notch+1,row+1,color);
                ctx.fill(notch,row,half+1,row+1,color);
            }
        }
        matrices.pop();
    }
    /**
     * 绘制无阴影的五乘七像素 H，笔画包围盒与圆形按钮共用同一像素中心。
     * net.minecraft.client.gui.DrawContext#fill 不经过字体的字宽尾留白、行高或阴影偏移，
     * 三条实色笔画在任意 GUI 比例下保持水平、垂直居中，圆形命中范围保持不变。
     */
    private static void padLetter(DrawContext ctx,int x,int y,int color){
        ctx.fill(x-2,y-3,x-1,y+4,color);
        ctx.fill(x+2,y-3,x+3,y+4,color);
        ctx.fill(x-2,y,x+3,y+1,color);
    }
    /** 关闭/切换屏幕释放GPU纹理；区块缓存继续跨次打开复用。 */
    @Override public void removed(){terrain.close();}
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);
        ctx.drawCenteredTextWithShadow(textRenderer,title,width/2,12,0xffffff);
        // 原版地图色配合羊皮纸底和深色边框；未知区域留纸色，不伪造尚未探索的地形。
        ctx.fill(left-6,top-6,left+mapWidth+6,top+mapHeight+6,0xff6d5030);
        ctx.fill(left-4,top-4,left+mapWidth+4,top+mapHeight+4,0xffe2cd98);
        ctx.enableScissor(left,top,left+mapWidth,top+mapHeight);
        terrain.draw(ctx,left,top,mapWidth,mapHeight,centerX,centerZ,blocksPerPixel);
        for(var zone:HeliRadar.zones){
            double radius=zone.radius()/blocksPerPixel;int zx=px(zone.pos().getX()+.5),zy=py(zone.pos().getZ()+.5);
            if(zx+radius<left||zx-radius>left+mapWidth||zy+radius<top||zy-radius>top+mapHeight)continue;
            int x=px(zone.pos().getX()+.5),y=py(zone.pos().getZ()+.5);HeliRadar.circle(ctx,x,y,(int)(zone.radius()/blocksPerPixel),0xffff6655);ctx.fill(x-2,y-2,x+3,y+3,0xffff6655);}
        for(Entity entity:HeliRadar.targets()){
            int x=px(entity.getX()),y=py(entity.getZ());
            if(entity instanceof HelicopterEntity)headingArrow(ctx,x,y,entity.getYaw(delta),0xffffbb55,OTHER_AIRCRAFT_SCALE);
            else ctx.fill(x-2,y-2,x+3,y+3,0xff66ccff);
        }
        var hoveredPad=padAt(mx,my);
        for(var pad:HeliRadar.mapHelipads()){
            int x=px(pad.pos().getX()+.5),y=py(pad.pos().getZ()+.5);
            if(x+PAD_ICON_RADIUS<left||x-PAD_ICON_RADIUS>=left+mapWidth
                    ||y+PAD_ICON_RADIUS<top||y-PAD_ICON_RADIUS>=top+mapHeight)continue;
            boolean restricted=HeliRadar.restricted(pad.pos());
            int color=restricted?0xffff6655:0xff7fffd4;
            filledCircle(ctx,x,y,PAD_ICON_RADIUS,color);
            filledCircle(ctx,x,y,PAD_ICON_RADIUS-1,pad.equals(hoveredPad)&&!restricted?0xff356859:0xff19342e);
            padLetter(ctx,x,y,color);
        }
        if(client.player!=null){
            int x=px(client.player.getX()),y=py(client.player.getZ());
            if(client.player.getVehicle() instanceof HelicopterEntity heli)
                headingArrow(ctx,px(heli.getX()),py(heli.getZ()),heli.getYaw(delta),0xffffffff,1);
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
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0)padClickConsumed=false;
        if(super.mouseClicked(x,y,button))return true;
        if(button==0&&insideMap(x,y)){
            var pad=padAt(x,y);
            if(pad!=null){padClickConsumed=true;setPadWaypoint(pad);}
            return true;
        }
        if(button==1&&insideMap(x,y)){
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity){
                var pad=padAt(x,y);
                if(pad!=null){setPadWaypoint(pad);return true;}
                double wx=centerX+(x-left-mapWidth/2.0)*blocksPerPixel,wz=centerZ+(y-top-mapHeight/2.0)*blocksPerPixel;
                if(HeliRadar.zones.stream().anyMatch(zone->Math.hypot(wx-zone.pos().getX()-.5,wz-zone.pos().getZ()-.5)<=zone.radius())
                        ||HeliRadar.mapHelipads().stream().anyMatch(p->HeliRadar.restricted(p.pos())
                            &&Math.hypot(wx-p.pos().getX()-.5,wz-p.pos().getZ()-.5)<=HelipadBlock.RADIUS))
                    client.player.sendMessage(Text.translatable("message.no_24_heli.waypoint_restricted"),true);
                else ClientPlayNetworking.send(new NavigationPayload(wx,wz,false));
            }
            return true;
        }
        return false;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&padClickConsumed)return true;
        if(button==0&&insideMap(x,y)){
            centerX-=dx*blocksPerPixel;centerZ-=dy*blocksPerPixel;return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }
    /** net.minecraft.client.gui.screen.Screen 的松键回调结束按钮消费，随后可再次从空白处拖图。 */
    @Override public boolean mouseReleased(double x,double y,int button){
        if(button==0&&padClickConsumed){padClickConsumed=false;return true;}
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        blocksPerPixel=Math.clamp(blocksPerPixel*Math.pow(1.2,-vertical),.5,16);return true;
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
