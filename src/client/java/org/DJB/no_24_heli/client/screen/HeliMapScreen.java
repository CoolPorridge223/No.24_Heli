package org.DJB.no_24_heli.client.screen;

import org.DJB.no_24_heli.block.HelipadBlock;
import org.DJB.no_24_heli.client.No_24_heliClient;
import org.DJB.no_24_heli.client.hud.HeliRadar;
import org.DJB.no_24_heli.client.map.ExploredMap;
import org.DJB.no_24_heli.client.map.MapTerrainRenderer;
import org.DJB.no_24_heli.client.map.MapWaypointBook;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.network.HelipadMapPayload;
import org.DJB.no_24_heli.network.NavigationPayload;





import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 北向探索地图及本机导航点抽屉；端侧 CLIENT，仅在客户端界面/渲染线程运行。
 * net.minecraft.client.gui.screen.Screen 负责输入，net.minecraft.client.gui.DrawContext 绘制裁切地图。
 * 打开时加载记录，关闭时释放 GPU 页；导航仍经原有 C2S 由服务端复核。
 * 排查：点图失效先查抽屉命中与裁切；名称丢失查本机地图目录；边距查统一 inset。
 */
@Environment(EnvType.CLIENT)
public final class HeliMapScreen extends Screen {
    private double centerX,centerZ,blocksPerPixel=2;
    private int left,top,mapWidth,mapHeight;
    /** 标题栏方形按钮、抽屉把手和列表行尺寸，单位均为 GUI 逻辑像素。 */
    private static final int TITLE_BUTTON_SIZE=20,HANDLE_WIDTH=14,HANDLE_HEIGHT=32,ROW_HEIGHT=22,HEADER_HEIGHT=22;
    /** 地图列表的本机记录及当前服务端停机坪快照，不改飞行状态。 */
    private MapWaypointBook waypointBook;
    private List<MapWaypointBook.Entry> waypoints=List.of();
    private List<HelipadMapPayload.Entry> padSnapshot=List.of();
    /** 抽屉始终在地图内部左沿；垂直位置按可移动范围的比例保存，适应窗口缩放。 */
    private boolean drawerOpen;
    private double drawerPosition=.25;
    private int drawerY,drawerWidth,drawerHeight,listOffset;
    /** 鼠标手势在按下时选定归属，避免拖抽屉/编辑名称时带动地图或设点。 */
    private boolean drawerGesture,drawerMoved,handlePressed,mapDragging;
    /** 抽屉按下时的鼠标 Y 和初始面板 Y，单位为 GUI 像素，避免逐帧累积拖动误差。 */
    private double drawerPressY,drawerDragStartY;
    /** 行内名称输入；用 net.minecraft.client.gui.widget.TextFieldWidget 接收字符与光标操作。 */
    private TextFieldWidget nameField;
    private MapWaypointBook.Entry editing;
    /** 保存失败只在抽屉内部短时提示，页面底部不再出现说明文字。 */
    private int saveErrorTicks;
    /** 只在首次打开时定位玩家，窗口缩放不重置已经平移的地图。 */
    private boolean centered;
    /** 地形按区块增量上传，GPU 每帧缩放缓存页；不再有整屏 10 Hz 重采样。 */
    private final MapTerrainRenderer terrain=new MapTerrainRenderer();
    /** 停机坪圆形按钮的半径，单位为 GUI 逻辑像素；绘制与点击共用，缩放地图不改变尺寸。 */
    private static final int PAD_ICON_RADIUS=7;
    /** 其他飞机的指针为本机的八成，单位为尺寸比例；地图缩放不改变此比例。 */
    private static final float OTHER_AIRCRAFT_SCALE=.8f;
    /** 点击停机坪后到松开左键期间消费拖动，防止按下按钮同时带动地图。 */
    private boolean padClickConsumed;
    public HeliMapScreen(){super(Text.translatable("screen.no_24_heli.map"));}
    /** 统一地图四边外距，标题按钮放在上方空隙；Screen 重建布局时保留地图与抽屉位置。 */
    @Override protected void init(){
        int inset=Math.clamp(Math.min(width,height)/12,24,32);
        left=inset+2;top=inset+2;mapWidth=width-2*left;mapHeight=height-2*top;
        if(!centered&&client.player!=null){centerX=client.player.getX();centerZ=client.player.getZ();centered=true;}
        if(waypointBook==null){
            waypointBook=new MapWaypointBook(ExploredMap.storageDirectory());
            refreshWaypoints();
        }
        layoutDrawer();
        addDrawableChild(new MapIconButton(left+mapWidth-2*TITLE_BUTTON_SIZE,top-TITLE_BUTTON_SIZE-3,
                Text.translatable("screen.no_24_heli.clear_waypoint"),false,b->{
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity)
                ClientPlayNetworking.send(new NavigationPayload(0,0,true));
        }));
        addDrawableChild(new MapIconButton(left+mapWidth-TITLE_BUTTON_SIZE,top-TITLE_BUTTON_SIZE-3,
                Text.translatable("screen.no_24_heli.close_map"),true,b->close()));
        nameField=addSelectableChild(new TextFieldWidget(textRenderer,0,0,drawerWidth-46,18,
                Text.translatable("screen.no_24_heli.waypoint_name")));
        nameField.setMaxLength(MapWaypointBook.NAME_LIMIT);
        nameField.setPlaceholder(Text.translatable("screen.no_24_heli.waypoint_auto_name"));
        nameField.setVisible(false);editing=null;
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
    /** 自定义点和列表共用旧导航校验与载荷；保存坐标不会绕过服务端禁区/边界复核。 */
    private void setWaypoint(double x,double z,boolean remember){
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity))return;
        if(HeliRadar.zones.stream().anyMatch(zone->Math.hypot(x-zone.pos().getX()-.5,z-zone.pos().getZ()-.5)<=zone.radius())
                ||HeliRadar.mapHelipads().stream().anyMatch(p->HeliRadar.restricted(p.pos())
                    &&Math.hypot(x-p.pos().getX()-.5,z-p.pos().getZ()-.5)<=HelipadBlock.RADIUS)){
            client.player.sendMessage(Text.translatable("message.no_24_heli.waypoint_restricted"),true);return;
        }
        ClientPlayNetworking.send(new NavigationPayload(x,z,false));
        if(remember){
            int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
            // net.minecraft.client.world.ClientWorld 只用客户端已同步的 WORLD_SURFACE；未知区块高度留空。
            Integer y=client.world.getChunkManager().isChunkLoaded(bx>>4,bz>>4)
                    ?client.world.getTopY(Heightmap.Type.WORLD_SURFACE,bx,bz):null;
            if(!waypointBook.remember(x,y,z))saveErrorTicks=100;
            waypoints=waypointBook.entries();layoutDrawer();
        }
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
    /** 停机坪完整快照变化才更新本机目录，避免每帧读盘或写盘。 */
    private void refreshWaypoints(){
        padSnapshot=List.copyOf(HeliRadar.mapHelipads());
        if(!waypointBook.syncPads(padSnapshot))saveErrorTicks=100;
        waypoints=waypointBook.entries();layoutDrawer();
    }
    /** 抽屉宽高与滚动范围随地图尺寸约束，把手在展开和折叠时都留在地图内部。 */
    private void layoutDrawer(){
        drawerWidth=Math.min(184,mapWidth-HANDLE_WIDTH-8);
        int rows=Math.max(1,Math.min(8,waypoints.size()));
        drawerHeight=Math.min(HEADER_HEIGHT+rows*ROW_HEIGHT+6,mapHeight-12);
        drawerY=top+6+(int)Math.round(drawerPosition*Math.max(0,mapHeight-drawerHeight-12));
        listOffset=Math.clamp(listOffset,0,Math.max(0,waypoints.size()-visibleRows()));
    }
    /** 完整行才允许命中，最下方裁切留白不对应下一行。 */
    private int visibleRows(){return Math.max(1,(drawerHeight-HEADER_HEIGHT-6)/ROW_HEIGHT);}
    /** 抽屉把手随展开位置移动，箭头始终紧邻当前抽屉的右缘。 */
    private int handleX(){return left+(drawerOpen?drawerWidth:0);}
    /** 输入消费覆盖抽屉和把手，不允许遮挡下的停机坪或地图收到同一点击。 */
    private boolean insideDrawer(double x,double y){
        return x>=handleX()&&x<handleX()+HANDLE_WIDTH&&y>=drawerY&&y<drawerY+HANDLE_HEIGHT
                ||drawerOpen&&x>=left&&x<left+drawerWidth&&y>=drawerY&&y<drawerY+drawerHeight;
    }
    /** 只返回清单的完整可见行；标题和留白没有导航操作。 */
    private MapWaypointBook.Entry rowAt(double x,double y){
        if(!drawerOpen||x<left||x>=left+drawerWidth||y<drawerY+HEADER_HEIGHT)return null;
        int row=(int)(y-drawerY-HEADER_HEIGHT)/ROW_HEIGHT;
        int index=listOffset+row;
        return row<visibleRows()&&index<waypoints.size()?waypoints.get(index):null;
    }
    /** 行内编辑选中已有自定义名称，空名称继续由稳定编号显示，不把默认文字写入记录。 */
    private void startRename(MapWaypointBook.Entry entry){
        editing=entry;nameField.setText(entry.name());nameField.setVisible(true);
        positionNameField();setFocused(nameField);nameField.setFocused(true);
        nameField.setSelectionStart(0);nameField.setSelectionEnd(nameField.getText().length());
    }
    /** 输入框位置跟随抽屉和当前行，避免第一次点击时仍用上一帧坐标。 */
    private void positionNameField(){
        if(editing==null)return;
        nameField.setX(left+6);
        nameField.setY(drawerY+HEADER_HEIGHT+(waypoints.indexOf(editing)-listOffset)*ROW_HEIGHT+2);
    }
    /** Enter/对勾提交，Esc 放弃；失败保留输入供重试，正常关闭不会留下输入焦点。 */
    private void finishRename(boolean save){
        if(save&&!waypointBook.rename(editing,nameField.getText())){saveErrorTicks=100;return;}
        editing=null;nameField.setVisible(false);nameField.setFocused(false);setFocused(null);
        waypoints=waypointBook.entries();
    }
    /** 根据当前地图缩放选择 1/2/5 系列的整米长度，比例尺与世界坐标共用 blocksPerPixel。 */
    private void drawScale(DrawContext ctx){
        double maximum=Math.min(96,mapWidth/4.0)*blocksPerPixel;
        double base=Math.pow(10,Math.floor(Math.log10(maximum)));
        double units=maximum/base;
        int meters=(int)(base*(units>=5?5:units>=2?2:1));
        int pixels=(int)Math.round(meters/blocksPerPixel);
        String label=meters>=1000?meters/1000+" km":meters+" m";
        int right=left+mapWidth-12,bottom=top+mapHeight-12;
        int labelWidth=textRenderer.getWidth(label),boxWidth=Math.max(pixels,labelWidth);
        ctx.fill(right-boxWidth-6,bottom-19,right+6,bottom+5,0xb0192424);
        ctx.drawText(textRenderer,label,right-(pixels+labelWidth)/2,bottom-15,0xfff3ead2,false);
        ctx.fill(right-pixels,bottom-1,right+1,bottom+1,0xfff3ead2);
        ctx.fill(right-pixels,bottom-5,right-pixels+2,bottom+1,0xfff3ead2);
        ctx.fill(right-1,bottom-5,right+1,bottom+1,0xfff3ead2);
    }
    /** 绘制地图内抽屉和名称编辑；net.minecraft.client.gui.DrawContext 的裁切状态成对恢复。 */
    private void drawDrawer(DrawContext ctx,int mx,int my,float delta){
        int hx=handleX();
        if(drawerOpen){
            ctx.fill(left,drawerY,left+drawerWidth,drawerY+drawerHeight,0xf21c2929);
            ctx.fill(left,drawerY,left+drawerWidth,drawerY+HEADER_HEIGHT,0xff344541);
            ctx.drawText(textRenderer,Text.translatable("screen.no_24_heli.waypoints"),left+8,drawerY+7,0xffe2cd98,false);
            // 右侧三条短线标识可拖动区域，不把草图中的说明文字加入页面。
            for(int i=0;i<3;i++)ctx.fill(left+drawerWidth-16,drawerY+7+i*3,left+drawerWidth-7,drawerY+8+i*3,0xff9baaa1);
            ctx.enableScissor(left+2,drawerY+HEADER_HEIGHT,left+drawerWidth-2,drawerY+drawerHeight-2);
            for(int row=0;row<visibleRows()&&listOffset+row<waypoints.size();row++){
                var entry=waypoints.get(listOffset+row);int y=drawerY+HEADER_HEIGHT+row*ROW_HEIGHT;
                boolean hovered=entry.equals(rowAt(mx,my));
                if(hovered)ctx.fill(left+3,y,left+drawerWidth-3,y+ROW_HEIGHT,0xff40574f);
                if(entry.equals(editing)){
                    positionNameField();nameField.render(ctx,mx,my,delta);
                    line(ctx,left+drawerWidth-26,y+11,left+drawerWidth-23,y+14,0xffe2cd98);
                    line(ctx,left+drawerWidth-23,y+14,left+drawerWidth-17,y+7,0xffe2cd98);
                }else{
                    int color=entry.helipad()&&HeliRadar.restricted(BlockPos.ofFloored(entry.x(),entry.y(),entry.z()))
                            ?0xffff8877:0xfff3ead2;
                    ctx.drawText(textRenderer,textRenderer.trimToWidth(entry.label().getString(),drawerWidth-42),left+8,y+7,color,false);
                    line(ctx,left+drawerWidth-25,y+14,left+drawerWidth-18,y+7,0xffb9c6b9);
                    line(ctx,left+drawerWidth-23,y+15,left+drawerWidth-16,y+8,0xffb9c6b9);
                }
            }
            if(waypoints.isEmpty())ctx.drawText(textRenderer,Text.translatable("screen.no_24_heli.waypoints_empty"),left+8,drawerY+HEADER_HEIGHT+7,0xff9baaa1,false);
            ctx.disableScissor();
            if(waypoints.size()>visibleRows()){
                int track=drawerHeight-HEADER_HEIGHT-6,thumb=Math.max(8,track*visibleRows()/waypoints.size());
                int y=drawerY+HEADER_HEIGHT+(track-thumb)*listOffset/(waypoints.size()-visibleRows());
                ctx.fill(left+drawerWidth-4,y,left+drawerWidth-2,y+thumb,0xffbba67a);
            }
            if(saveErrorTicks>0)ctx.drawText(textRenderer,Text.translatable("screen.no_24_heli.save_failed"),left+8,drawerY-11,0xffff8877,true);
        }
        ctx.fill(hx,drawerY,hx+HANDLE_WIDTH,drawerY+HANDLE_HEIGHT,0xff344541);
        int cx=hx+HANDLE_WIDTH/2,cy=drawerY+HANDLE_HEIGHT/2,direction=drawerOpen?-1:1;
        line(ctx,cx-direction*2,cy-5,cx+direction*3,cy,0xffe2cd98);
        line(ctx,cx+direction*3,cy,cx-direction*2,cy+5,0xffe2cd98);
        if(editing==null){
            var hovered=rowAt(mx,my);
            if(hovered!=null){
                Text y=hovered.y()==null?Text.translatable("screen.no_24_heli.coordinate_unknown"):Text.literal(hovered.y().toString());
                ctx.drawTooltip(textRenderer,Text.literal("X: "+(int)Math.floor(hovered.x())+"  Y: ")
                        .append(y).append("  Z: "+(int)Math.floor(hovered.z())),mx,my);
            }
        }
    }
    /** 用整数像素连线绘制箭头/铅笔/标题栏图标，不依赖字体字形或外部纹理。 */
    private static void line(DrawContext ctx,int x,int y,int endX,int endY,int color){
        int dx=Math.abs(endX-x),dy=-Math.abs(endY-y),sx=x<endX?1:-1,sy=y<endY?1:-1,error=dx+dy;
        while(true){
            ctx.fill(x,y,x+1,y+1,color);if(x==endX&&y==endY)break;
            int twice=2*error;if(twice>=dy){error+=dy;x+=sx;}if(twice<=dx){error+=dx;y+=sy;}
        }
    }
    /** 标题栏按钮保留原版键盘焦点、提示与朗读；只替换 net.minecraft.client.gui.widget.ButtonWidget 的外观。 */
    @Environment(EnvType.CLIENT)
    private static final class MapIconButton extends ButtonWidget{
        /** true 绘制关闭叉号，false 绘制草图中的回转箭头。 */
        private final boolean closeIcon;
        /** 无可见文字的按钮仍提供本地化名称供 Tooltip 与无障碍朗读使用。 */
        private MapIconButton(int x,int y,Text label,boolean closeIcon,PressAction action){
            super(x,y,TITLE_BUTTON_SIZE,TITLE_BUTTON_SIZE,label,action,DEFAULT_NARRATION_SUPPLIER);
            this.closeIcon=closeIcon;setTooltip(Tooltip.of(label));
        }
        /** 标题按钮与地图使用相同纸色边线，取消在左、关闭在右，不绘制文本。 */
        @Override protected void renderWidget(DrawContext ctx,int mx,int my,float delta){
            int x=getX(),y=getY(),color=0xffead9af;
            ctx.fill(x,y,x+width,y+height,color);
            ctx.fill(x+1,y+1,x+width-1,y+height-1,isHovered()||isFocused()?0xff536358:0xff263731);
            if(closeIcon){
                line(ctx,x+6,y+6,x+13,y+13,color);line(ctx,x+13,y+6,x+6,y+13,color);
            }else{
                for(int i=0;i<28;i++){
                    double a=Math.toRadians(-45+i*10);
                    int px=x+10+(int)Math.round(Math.cos(a)*5),py=y+10+(int)Math.round(Math.sin(a)*5);
                    ctx.fill(px,py,px+1,py+1,color);
                }
                line(ctx,x+5,y+9,x+6,y+14,color);line(ctx,x+6,y+14,x+11,y+13,color);
            }
        }
    }
    /** 关闭/切换屏幕释放GPU纹理；区块缓存继续跨次打开复用。 */
    @Override public void removed(){terrain.close();}
    @Override public void render(DrawContext ctx,int mx,int my,float delta){
        super.render(ctx,mx,my,delta);
        ctx.drawText(textRenderer,textRenderer.trimToWidth(title.getString(),mapWidth-2*TITLE_BUTTON_SIZE-8),left,top-17,0xffead9af,false);
        // 原版地图色配合羊皮纸底和深色边框；未知区域留纸色，不伪造尚未探索的地形。
        ctx.fill(left-2,top-2,left+mapWidth+2,top+mapHeight+2,0xff6d5030);
        ctx.fill(left-1,top-1,left+mapWidth+1,top+mapHeight+1,0xffe2cd98);
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
        var hoveredPad=insideDrawer(mx,my)?null:padAt(mx,my);
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
        ctx.drawText(textRenderer,"N ↑",left+6,top+6,0xffffffff,true);
        drawScale(ctx);drawDrawer(ctx,mx,my,delta);
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0){padClickConsumed=false;mapDragging=false;}
        if(insideDrawer(x,y)){
            if(button!=0)return true;
            boolean handle=x>=handleX()&&x<handleX()+HANDLE_WIDTH;
            if(handle||y<drawerY+HEADER_HEIGHT){
                if(editing!=null)finishRename(false);
                drawerGesture=true;drawerMoved=false;handlePressed=handle;
                drawerPressY=y;drawerDragStartY=drawerY;return true;
            }
            var entry=rowAt(x,y);
            if(entry!=null){
                if(entry.equals(editing)){
                    if(x>=left+drawerWidth-36)finishRename(true);
                    else super.mouseClicked(x,y,button);
                }else{
                    if(editing!=null)finishRename(false);
                    if(x>=left+drawerWidth-36)startRename(entry);
                    else{
                        centerX=entry.x();centerZ=entry.z();
                        if(entry.helipad())setPadWaypoint(new HelipadMapPayload.Entry(BlockPos.ofFloored(entry.x(),entry.y(),entry.z())));
                        else setWaypoint(entry.x(),entry.z(),false);
                    }
                }
            }
            return true;
        }
        if(editing!=null)finishRename(false);
        if(super.mouseClicked(x,y,button))return true;
        if(button==0&&insideMap(x,y)){
            var pad=padAt(x,y);
            if(pad!=null){padClickConsumed=true;setPadWaypoint(pad);}
            else mapDragging=true;
            return true;
        }
        if(button==1&&insideMap(x,y)){
            if(client.player!=null&&client.player.getVehicle() instanceof HelicopterEntity){
                var pad=padAt(x,y);
                if(pad!=null){setPadWaypoint(pad);return true;}
                double wx=centerX+(x-left-mapWidth/2.0)*blocksPerPixel,wz=centerZ+(y-top-mapHeight/2.0)*blocksPerPixel;
                setWaypoint(wx,wz,true);
            }
            return true;
        }
        return false;
    }
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        if(button==0&&drawerGesture){
            if(Math.abs(y-drawerPressY)>3)drawerMoved=true;
            if(drawerMoved){
                int travel=Math.max(0,mapHeight-drawerHeight-12);
                drawerPosition=travel==0?0:Math.clamp((drawerDragStartY+y-drawerPressY-top-6)/travel,0,1);
                layoutDrawer();
            }
            return true;
        }
        if(button==0&&padClickConsumed)return true;
        if(button==0&&mapDragging){
            centerX-=dx*blocksPerPixel;centerZ-=dy*blocksPerPixel;return true;
        }
        return super.mouseDragged(x,y,button,dx,dy);
    }
    /** net.minecraft.client.gui.screen.Screen 的松键回调结束按钮消费，随后可再次从空白处拖图。 */
    @Override public boolean mouseReleased(double x,double y,int button){
        if(button==0&&drawerGesture){
            drawerGesture=false;
            if(!drawerMoved&&handlePressed){drawerOpen=!drawerOpen;layoutDrawer();}
            return true;
        }
        if(button==0&&mapDragging){mapDragging=false;return true;}
        if(button==0&&padClickConsumed){padClickConsumed=false;return true;}
        return super.mouseReleased(x,y,button);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(insideDrawer(x,y)){
            if(editing==null)listOffset=Math.clamp(listOffset-(int)Math.signum(vertical)*3,0,
                    Math.max(0,waypoints.size()-visibleRows()));
            return true;
        }
        if(!insideMap(x,y))return super.mouseScrolled(x,y,horizontal,vertical);
        blocksPerPixel=Math.clamp(blocksPerPixel*Math.pow(1.2,-vertical),.5,16);return true;
    }
    /** Screen会吞掉普通按键绑定，故在地图本身处理M；重绑后的地图键同样生效。 */
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(editing!=null){
            if(key==GLFW.GLFW_KEY_ENTER||key==GLFW.GLFW_KEY_KP_ENTER){finishRename(true);return true;}
            if(key==GLFW.GLFW_KEY_ESCAPE){finishRename(false);return true;}
            return super.keyPressed(key,scan,modifiers);
        }
        if(No_24_heliClient.mapKeyMatches(key,scan)){close();return true;}return super.keyPressed(key,scan,modifiers);
    }
    @Override public void tick(){
        if(client.player==null||!(client.player.getVehicle() instanceof HelicopterEntity)){close();return;}
        if(!padSnapshot.equals(HeliRadar.mapHelipads())){
            if(editing!=null)finishRename(false);
            refreshWaypoints();
        }
        if(saveErrorTicks>0)saveErrorTicks--;
    }
    @Override public boolean shouldPause(){return false;}
}
