/**
 * 文件：乘机 HUD，负责飞行仪表、机组信息、机体状态与雷达。
 * 端侧：CLIENT；关联实体注册名：no_24_heli:helicopter。
 */
package org.DJB.no_24_heli.client.hud;

import org.DJB.no_24_heli.client.input.ParachuteClient;
import org.DJB.no_24_heli.client.render.FlightInstruments;
import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;




import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import java.util.Locale;

/**
 * 职责：绘制乘机 HUD，垂速与世界高度采用固定中心指针和滚动数字窗。
 * 引用库：net.minecraft.client.gui.DrawContext 负责文字、矩形和裁剪；
 * net.minecraft.client.render.RenderTickCounter 提供实体位置插值；
 * net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback 在客户端初始化注册本类。
 * 生命周期：由 No_24_heliClient 注册，每帧调用 draw，换乘时重置仪表快照。
 * 端侧/线程：仅物理客户端渲染线程，仅读取服务端同步的飞行状态。
 * 故障排查：数字越界先查裁剪坐标转换；数字突跳查平滑快照与进位区间；
 * HUD 错位查矩阵栈 push/pop；专用服务端加载报错查 Environment 隔离。
 */
@Environment(EnvType.CLIENT)
public final class HeliHud {
    /** 在原版 GUI 比例上缩小到 85%，保留边缘留白和快捷栏空间。 */
    private static final float SCALE=.85f;
    /** 两个仪表共用外框尺寸，单位：缩放前 HUD 像素；仅客户端使用。 */
    private static final int TAPE_WIDTH=58,TAPE_HEIGHT=114,TAPE_HEADER=14;
    /** 两表内部间距 8 像素，随整体缩放；空速表左侧留白仍为 8 HUD 像素。 */
    private static final int TAPE_GAP=8;
    /** 空速表现有圆心高度与半径，单位：HUD 像素；供两条刻度带对齐其底边。 */
    private static final int AIRSPEED_CENTER_Y=-40,AIRSPEED_RADIUS=35;
    /** 两条刻度带连同标题、单位、数字窗统一缩小为原来的 2/3，仅客户端使用。 */
    private static final float TAPE_SCALE=2f/3f;
    /** 相邻短刻度间距，单位：HUD 像素；分别对应 5 m/s 和 50 m。 */
    private static final int TAPE_SPACING=12;
    /** 低位窗高 42、高位窗高 18、左侧箭头深度 7，单位：HUD 像素。 */
    private static final int READOUT_HEIGHT=42,READOUT_CENTER_HEIGHT=18,READOUT_ARROW=7;
    /** 符号距左框 3、低位距台阶 2；字符间距 6、滚动行距 11，单位：HUD 像素。 */
    private static final int READOUT_PADDING=3,READOUT_TAIL_PADDING=2,DIGIT_WIDTH=6,ROLL_PITCH=11;
    /** 数字窗文字放大到原字体的 125%，仅改变表现，不量化原始读数。 */
    private static final float READOUT_SCALE=1.25f;
    /** 两表标题从原来的 0.7 倍字体提高至 1.05 倍，即原文字大小的 1.5 倍。 */
    private static final float TAPE_TITLE_SCALE=1.05f;
    /** 高度末两位数字鼓的步长，单位：米；700→720 的整个区间连续滚动。 */
    private static final int ALTITUDE_ROLL_STEP=20;
    /** 登机 E 键帽的边长与文字间隔，单位：GUI 逻辑像素。 */
    private static final int BOARD_KEY_SIZE=18,BOARD_KEY_GAP=4;
    /**
     * net.minecraft.client.gui.DrawContext 绘制准星右侧的登机提示，E 居中放入圆角正方形。
     * 仅客户端渲染；两段翻译文本分别布局，避免依赖某种语言中 E 的字符位置。
     */
    private static void drawBoardHint(DrawContext ctx){
        drawKeyHint(ctx,Text.literal("E"),"hud.no_24_heli.rope_board_before","hud.no_24_heli.rope_board_after");
    }
    /** 开伞与登机共用原有金色文字和圆角键帽；重绑后按实际键名扩展键帽宽度。 */
    private static void drawKeyHint(DrawContext ctx,Text key,String beforeKey,String afterKey){
        var font=MinecraftClient.getInstance().textRenderer;
        Text before=Text.translatable(beforeKey),after=Text.translatable(afterKey);
        int keyWidth=Math.max(BOARD_KEY_SIZE,font.getWidth(key)+6);
        int width=font.getWidth(before)+keyWidth+font.getWidth(after)+2*BOARD_KEY_GAP;
        int x=Math.max(4,Math.min(ctx.getScaledWindowWidth()/2+24,ctx.getScaledWindowWidth()-width-4));
        int y=ctx.getScaledWindowHeight()/2-BOARD_KEY_SIZE/2,textY=y+(BOARD_KEY_SIZE-font.fontHeight)/2;
        ctx.drawText(font,before,x,textY,0xffffd477,true);
        int keyX=x+font.getWidth(before)+BOARD_KEY_GAP;
        // 方形默认键帽保持原样；长键名用左右两端圆角和中间填充延展。
        roundedKey(ctx,keyX,y,BOARD_KEY_SIZE,0xffffd477);
        if(keyWidth>BOARD_KEY_SIZE){
            roundedKey(ctx,keyX+keyWidth-BOARD_KEY_SIZE,y,BOARD_KEY_SIZE,0xffffd477);
            ctx.fill(keyX+BOARD_KEY_SIZE/2,y,keyX+keyWidth-BOARD_KEY_SIZE/2,y+BOARD_KEY_SIZE,0xffffd477);
        }
        roundedKey(ctx,keyX+1,y+1,BOARD_KEY_SIZE-2,0xe61c2530);
        if(keyWidth>BOARD_KEY_SIZE){
            roundedKey(ctx,keyX+keyWidth-BOARD_KEY_SIZE+1,y+1,BOARD_KEY_SIZE-2,0xe61c2530);
            ctx.fill(keyX+BOARD_KEY_SIZE/2,y+1,keyX+keyWidth-BOARD_KEY_SIZE/2,y+BOARD_KEY_SIZE-1,0xe61c2530);
        }
        ctx.drawText(font,key,keyX+(keyWidth-font.getWidth(key))/2,textY,0xffffffff,false);
        ctx.drawText(font,after,keyX+keyWidth+BOARD_KEY_GAP,textY,0xffffd477,true);
    }
    /** 以三像素圆角的逐行轮廓填充键帽，无额外纹理；引用 net.minecraft.client.gui.DrawContext#fill。 */
    private static void roundedKey(DrawContext ctx,int x,int y,int size,int color){
        for(int row=0;row<size;row++){
            int edge=Math.min(row,size-1-row),inset=edge==0?3:edge==1?1:0;
            ctx.fill(x+inset,y+row,x+size-inset,y+row+1,color);
        }
    }
    private static String f(double value){return String.format(Locale.ROOT,"%.1f",value);}
    private static void text(DrawContext ctx,String key,int x,int y,int color,Object... args){
        ctx.drawText(MinecraftClient.getInstance().textRenderer,Text.translatable(key,args),x,y,color,true);
    }
    /**
     * 功能：在乘机且没有打开界面时绘制完整 HUD，世界高度由底部高度表显示。
     * 引用 MC API：net.minecraft.client.MinecraftClient 提供玩家、字体和界面状态；
     * net.minecraft.client.render.RenderTickCounter#getTickDelta 提供位置插值；
     * net.minecraft.entity.Entity#getLerpedPos 提供连续世界坐标；
     * net.minecraft.client.gui.DrawContext 提供 GUI 绘制和矩阵栈。
     * 端侧/线程：客户端渲染线程。参数：ctx 为绘制上下文，counter 为当前帧计时器。
     * 返回：无。副作用：提交 HUD 绘制，更新客户端仪表滤波快照。
     * 故障排查：高度表使用世界 Y 与 120 ms 滤波，离地提示另由
     * GroundProximityWarning 提供；缩放错位检查两层矩阵恢复。
     */
    public static void draw(DrawContext ctx,RenderTickCounter counter){
        var mc=MinecraftClient.getInstance();
        if(mc.player==null||mc.options.hudHidden||mc.currentScreen!=null)return;
        if(org.DJB.no_24_heli.entity.ParachuteEntity.canDeploy(mc.player))
            drawKeyHint(ctx,ParachuteClient.keyName(),"hud.no_24_heli.parachute_before","hud.no_24_heli.parachute_after");
        else if(mc.player.getVehicle() instanceof org.DJB.no_24_heli.entity.ParachuteEntity canopy&&canopy.needsPull())
            drawKeyHint(ctx,ParachuteClient.keyName(),"hud.no_24_heli.parachute_pull_before","hud.no_24_heli.parachute_pull_after");
        if(mc.player.getVehicle() instanceof RopeSeatEntity rope&&rope.canBoardHelicopter(mc.player)){
            drawBoardHint(ctx);
        }
        if(!(mc.player.getVehicle() instanceof HelicopterEntity h))return;
        // 机内的全部飞行读数、机组、导航、部件与警告由实体仪表板呈现，不叠加屏幕 HUD。
        if(mc.options.getPerspective().isFirstPerson())return;
        var s=h.flight;var display=FlightInstruments.sample(h,counter.getTickDelta(false));
        var m=ctx.getMatrices();m.push();m.scale(SCALE,SCALE,1);
        int w=(int)(ctx.getScaledWindowWidth()/SCALE),height=(int)(ctx.getScaledWindowHeight()/SCALE),x=10;
        // 底部统一缩放，窄窗口也为左侧仪表、中央血条和右侧飞行数据留出独立空间。
        float bottomScale=Math.min(1,w/560f);int bw=(int)(w/bottomScale);
        m.push();m.translate(0,height,0);m.scale(bottomScale,bottomScale,1);
        m.push();m.translate(2,-59,0);m.scale(.78f,.78f,1);
        gauge(ctx,31,32,29,"fuel_dial",display.fuel,0,HeliConfig.INSTANCE.fuelCapacity,"",0xffffd66b);
        gauge(ctx,96,32,29,"rpm_dial",display.rpm*100,0,100,"%",0xff7ce3bc);
        gauge(ctx,161,32,29,"collective_dial",display.collective*100,0,100,"%",0xff7ce3bc);
        m.pop();
        text(ctx,s.difficultyMode==2?"hud.no_24_heli.state_zobayan":s.hardcoreMode?"hud.no_24_heli.state_mamba":"hud.no_24_heli.state",4,-84,0xffffd66b);
        String status="H "+(s.altitudeHold?"●":"○")+"  J "+(s.trim?"●":"○")+"  I "+(s.cruise?"●":"○")+"  G "+(s.gear?"↓":"↑");
        ctx.drawText(mc.textRenderer,status,4,-70,0xffb6edc8,true);
        if(s.autoPhase!=0)text(ctx,"hud.no_24_heli.auto_phase_"+s.autoPhase,4,-120,0xff8fffb1);
        if(s.autopilot)text(ctx,"hud.no_24_heli.autopilot",4,-108,0xff8fffb1);
        if(s.waypoint)text(ctx,"hud.no_24_heli.waypoint",4,-96,0xffe9e897,(int)Math.hypot(s.waypointX-h.getX(),s.waypointZ-h.getZ()));
        // 四行机组文字：座位标签与姓名独立成行。
        text(ctx,"hud.no_24_heli.pilot",4,-174,0xff7ce3bc);
        crewName(ctx,h,0,4,-162);
        text(ctx,"hud.no_24_heli.copilot",4,-150,0xff7ce3bc);
        crewName(ctx,h,1,4,-138);
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
        // 从左到右为垂速、高度、空速；两条刻度带共用外框和固定中心数字窗。
        gauge(ctx,bw-40,AIRSPEED_CENTER_Y,AIRSPEED_RADIUS,"airspeed",display.airspeed,0,160,"km/h",0xff7ce3bc);
        float altitudeX=bw-40-AIRSPEED_RADIUS-TAPE_GAP-TAPE_WIDTH*TAPE_SCALE;
        m.push();
        // 圆盘最下方像素占据 cy+radius 到 cy+radius+1，外框底边对齐同一条下边界。
        m.translate(altitudeX,AIRSPEED_CENTER_Y+AIRSPEED_RADIUS+1,0);m.scale(TAPE_SCALE,TAPE_SCALE,1);
        verticalTape(ctx,-TAPE_WIDTH-TAPE_GAP,-TAPE_HEIGHT,display.verticalSpeed,true);
        verticalTape(ctx,0,-TAPE_HEIGHT,display.altitude,false);
        m.pop();
        m.pop();
        int warningY=10;
        if(s.tail<20){warn(ctx,"tail",warningY);warningY+=11;}
        if(s.rotor<50){warn(ctx,"rotor",warningY);warningY+=11;}
        if(s.fuel<80){warn(ctx,"fuel",warningY);warningY+=11;}
        if(s.takeoffPowerLow){warn(ctx,"takeoff_power",warningY);warningY+=11;}
        if(!s.running&&!h.isOnGround()&&s.armed){warn(ctx,"autorotation",warningY);warningY+=11;}
        if(s.running&&!s.armed){warn(ctx,"spooling",warningY);warningY+=11;}
        if(s.restricted){warn(ctx,"restricted",warningY);warningY+=11;}
        int proximity=GroundProximityWarning.level();
        if(proximity>0){
            boolean dangerous=GroundProximityWarning.warning();
            text(ctx,dangerous?"warning.no_24_heli.sink_rate":"hud.no_24_heli.ground_height",10,warningY,
                    dangerous?0xffff6655:0xffe6f4ee,(int)Math.ceil(GroundProximityWarning.height()));
        }
        // 右上缩小地平仪，旋转线条真实表达俯仰和横滚。
        int cx=w-143,cy=32;ctx.fill(cx-25,cy-25,cx+25,cy+25,0xa0193329);
        m.push();m.translate(cx,cy,0);m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float)-display.roll));
        float offset=(float)Math.clamp(display.pitch*.5,-10,10);m.translate(0,offset,0);
        ctx.fill(-17,-1,17,1,0xff90e0b4);
        ctx.fill(-8,-8,8,-7,0xff547969);ctx.fill(-8,7,8,8,0xff547969);m.pop();
        ctx.fill(cx-14,cy,cx-4,cy+1,0xffffd66b);ctx.fill(cx+4,cy,cx+14,cy+1,0xffffd66b);ctx.fill(cx-1,cy-2,cx+1,cy+3,0xffffd66b);
        HeliRadar.draw(ctx,h,w-4);
        m.pop();
    }
    /** 姓名遵循真实座位顺序，空座给出明确文字。 */
    private static void crewName(DrawContext ctx,HelicopterEntity h,int seat,int x,int y){
        Text name=seat<h.getPassengerList().size()?h.getPassengerList().get(seat).getName():Text.translatable("hud.no_24_heli.empty_seat");
        ctx.drawText(MinecraftClient.getInstance().textRenderer,name,x,y,0xffe6f4ee,true);
    }
    /**
     * 功能：让刻度与数字在中心完全不透明、两端渐淡到 50%。
     * 引用 MC API：无，仅计算交给 net.minecraft.client.gui.DrawContext 的 ARGB 颜色。
     * 端侧/线程：客户端渲染线程。参数：offset 为离中心的像素距离，half 为半量程像素。
     * 返回：刻度颜色。副作用：无。故障排查：渐变反向时检查 offset 是否取绝对值。
     */
    public static int tapeColor(double offset,double half){
        int alpha=(int)Math.round(255*(1-.5*Math.clamp(Math.abs(offset)/half,0,1)));
        return alpha<<24|0xe6f4ee;
    }
    /**
     * 功能：计算刻度相对固定指针的位置，数值增加时刻度下移。
     * 引用 MC API：无。端侧/线程：客户端渲染线程。
     * 参数：value/mark 为当前值/刻度值，step 为单位步长，spacing 为像素间距。
     * 返回：向下为正的像素偏移。副作用：无。故障排查：滚动反向查 value-mark 顺序。
     */
    public static double tapeOffset(double value,double mark,double step,double spacing){return (value-mark)/step*spacing;}
    /**
     * 功能：绘制等大刻度带，两表刻度和数字统一靠左，整十/整百标长线与数字。
     * 引用 MC API：net.minecraft.client.gui.DrawContext#fill 绘制边框与刻线，
     * #drawText 绘制主刻度；#disableScissor 与 tapeScissor 成对恢复裁剪。
     * 端侧/线程：客户端渲染线程。参数：ctx 为绘制上下文，x/top 为外框左上角，
     * value 为平滑读数，verticalSpeed 区分 m/s 垂速与 m 世界高度。
     * 返回：无。副作用：提交 GUI 绘制。故障排查：短线出现数字查 major；
     * 0 带符号查 mark>0 分支；左右颠倒查 edge 是否固定在外框左侧。
     */
    private static void verticalTape(DrawContext ctx,int x,int top,double value,boolean verticalSpeed){
        var mc=MinecraftClient.getInstance();
        int bodyTop=top+TAPE_HEADER,bottom=top+TAPE_HEIGHT,mid=(bodyTop+bottom)/2;
        int step=verticalSpeed?5:50;
        ctx.fill(x,top,x+TAPE_WIDTH,bottom,0xdc101c1b);
        ctx.fill(x,top,x+TAPE_WIDTH,top+1,0xff53716a);
        ctx.fill(x,bottom-1,x+TAPE_WIDTH,bottom,0xff53716a);
        ctx.fill(x,top,x+1,bottom,0xff53716a);
        ctx.fill(x+TAPE_WIDTH-1,top,x+TAPE_WIDTH,bottom,0xff53716a);
        ctx.fill(x,bodyTop,x+TAPE_WIDTH,bodyTop+1,0xff53716a);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,verticalSpeed?"m/s":"m",x+TAPE_WIDTH/2,top+3,0xffb6edc8);
        tapeScissor(ctx,x+1,bodyTop+1,x+TAPE_WIDTH-1,bottom-1);
        int range=(int)Math.ceil((bottom-bodyTop)/2.0/TAPE_SPACING)+1;
        int center=(int)Math.floor(value/step);
        for(int i=center-range;i<=center+range;i++){
            double offset=tapeOffset(value,i*step,step,TAPE_SPACING);
            int y=mid+(int)Math.round(offset),mark=i*step;
            boolean major=i%2==0;
            int color=tapeColor(offset,(bottom-bodyTop)/2.0),length=major?9:5;
            int edge=x+1;
            ctx.fill(edge,y,edge+length,y+1,color);
            if(major){
                String number=(verticalSpeed&&mark>0?"+":"")+mark;
                int textX=edge+length+3;
                ctx.drawText(mc.textRenderer,number,textX,y-mc.textRenderer.fontHeight/2,color,false);
            }
        }
        ctx.disableScissor();
        tapeReadout(ctx,x,mid,value,verticalSpeed);
        var matrices=ctx.getMatrices();
        // 标题增大后上移 4 HUD 像素，保留与单位栏的间距。
        matrices.push();matrices.translate(x+TAPE_WIDTH/2.0,top-15,0);matrices.scale(TAPE_TITLE_SCALE,TAPE_TITLE_SCALE,1);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,
                Text.translatable("hud.no_24_heli."+(verticalSpeed?"vario":"altimeter")),0,0,0xff7ce3bc);
        matrices.pop();
    }

    /**
     * 功能：将仪表局部矩形转成实际 GUI 坐标，保证底部缩放后仍正确裁剪数字鼓。
     * 引用 MC API：net.minecraft.client.gui.DrawContext#getMatrices 读取当前位置矩阵，
     * #enableScissor 启用屏幕坐标裁剪；调用者必须用 #disableScissor 恢复。
     * 端侧/线程：客户端渲染线程。参数：ctx 为上下文，left/top/right/bottom 为局部矩形。
     * 返回：无。副作用：压入裁剪区域。故障排查：GUI 比例改变后文字越界查矩阵转换；
     * 后续 HUD 消失查调用者是否恢复裁剪。本处仅处理 HUD 的平移与正比例缩放。
     */
    private static void tapeScissor(DrawContext ctx,int left,int top,int right,int bottom){
        var matrix=ctx.getMatrices().peek().getPositionMatrix();
        var a=new org.joml.Vector3f(left,top,0).mulPosition(matrix);
        var b=new org.joml.Vector3f(right,bottom,0).mulPosition(matrix);
        ctx.enableScissor((int)Math.floor(a.x),(int)Math.floor(a.y),(int)Math.ceil(b.x),(int)Math.ceil(b.y));
    }

    /**
     * 功能：绘制向左凸字形数字窗，高位与符号占矮框，低位占上下加高的右框。
     * 引用 MC API：net.minecraft.client.gui.DrawContext#fill 绘制不透明窗体与指针；
     * net.minecraft.client.util.math.MatrixStack 的 push/pop 保持文字缩放成对恢复。
     * 端侧/线程：客户端渲染线程。参数：ctx 为上下文，x 为外框左边，mid 为固定中线，
     * value 为未取整的带符号读数，verticalSpeed 选择 1 m/s 或 20 m 数字鼓。
     * 返回：无。副作用：绘制并临时裁剪/缩放。故障排查：指针移动查 mid 来源；
     * 数字跳变查是否提前取整；台阶压字查 tailLeft 与 numberLeft 是否使用同一缩放；
     * 符号重复查是否误放进滚动行循环；零仍带符号查 centered。
     */
    private static void tapeReadout(DrawContext ctx,int x,int mid,double value,boolean verticalSpeed){
        int left=x+READOUT_ARROW,right=x+TAPE_WIDTH-2;
        int top=mid-READOUT_HEIGHT/2,bottom=mid+READOUT_HEIGHT/2;
        int centerTop=mid-READOUT_CENTER_HEIGHT/2,centerBottom=mid+READOUT_CENTER_HEIGHT/2;
        int step=verticalSpeed?1:ALTITUDE_ROLL_STEP;
        long lower=(long)Math.floor(value/step)*step;
        // 为下一次进位预留位数；Math.abs 仅用于字符宽度，不改变数轴和滚动方向。
        long largest=Math.max(Math.abs(lower-step),Math.abs(lower+2L*step));
        int digits=Math.max(verticalSpeed?3:4,Long.toString(largest).length());
        float scale=Math.min(READOUT_SCALE,(right-left-2f*READOUT_PADDING)/((digits+1)*DIGIT_WIDTH));
        double numberLeft=right-READOUT_PADDING-(digits+1)*DIGIT_WIDTH*scale;
        int tailWidth=verticalSpeed?1:2;
        // 台阶按实际低位字符位置计算：垂速仅个位，高度仅十位和个位进入高框。
        int tailLeft=(int)Math.floor(numberLeft+(digits-tailWidth+1)*DIGIT_WIDTH*scale)-READOUT_TAIL_PADDING;
        ctx.fill(left,centerTop,right,centerBottom,0xffe6f4ee);
        ctx.fill(tailLeft,top,right,bottom,0xffe6f4ee);
        // 两块内填色相交，中央保持连通，不在高低位之间画分隔线。
        ctx.fill(left+1,centerTop+1,right-1,centerBottom-1,0xff090f13);
        ctx.fill(tailLeft+1,top+1,right-1,bottom-1,0xff090f13);
        for(int i=0;i<READOUT_ARROW;i++){
            int px=left-1-i,half=READOUT_ARROW-1-i;
            ctx.fill(px,mid-half,px+1,mid+half+1,0xffe6f4ee);
            if(half>0)ctx.fill(px,mid-half+1,px+1,mid+half,0xff090f13);
        }
        tapeScissor(ctx,left+1,top+1,right-1,bottom-1);
        var matrices=ctx.getMatrices();
        // 符号独立固定在窗内左侧中央；以最靠近中心的数字行判零，避免显示 +0/-0。
        long centered=Math.round(value/step)*step;
        String sign=centered==0?"":centered<0?"-":verticalSpeed?"+":"";
        matrices.push();matrices.translate(left+READOUT_PADDING,mid,0);matrices.scale(scale,scale,1);
        matrices.translate(0,-MinecraftClient.getInstance().textRenderer.fontHeight/2.0,0);
        ctx.drawText(MinecraftClient.getInstance().textRenderer,sign,0,0,0xffe6f4ee,false);matrices.pop();
        matrices.push();matrices.translate(numberLeft,mid,0);matrices.scale(scale,scale,1);
        rollingDigits(ctx,value,step,digits,verticalSpeed);
        matrices.pop();ctx.disableScissor();
    }

    /**
     * 功能：低位沿真实数轴滚动，高位仅在中央单行格内完成当前位与下一位的替换。
     * 引用 MC API：net.minecraft.client.gui.DrawContext#drawText 绘制相邻数字行，
     * net.minecraft.client.util.math.MatrixStack#translate 保留亚像素滚动位置；
     * net.minecraft.client.gui.DrawContext#disableScissor 恢复高位格的嵌套裁剪。
     * 端侧/线程：客户端渲染线程。参数：value 为带符号读数，step 为 1 m/s 或 20 m，
     * digits 为数字位数，verticalSpeed 选择个位或高度末两位的滚动组；符号由调用者固定绘制。
     * 返回：无。副作用：提交文字绘制，恢复矩阵。故障排查：710 直接跳到 720 查是否提前取整；
     * 780→800 拼接错位查高低位是否共用 offset；高位多行查中央格裁剪及两行替换范围；
     * 零附近出现 9 查是否错误使用循环取模代替 -1、0、+1 的真实相邻行。
     */
    private static void rollingDigits(DrawContext ctx,double value,int step,int digits,boolean verticalSpeed){
        var mc=MinecraftClient.getInstance();
        long lower=(long)Math.floor(value/step)*step;
        String[] numbers=new String[4]; // 当前区间前后各补一行，-0.2 和 +0.2 两侧都能显示正负邻值。
        for(int row=0;row<numbers.length;row++){
            numbers[row]=String.format(Locale.ROOT,"%"+digits+"d",Math.abs(lower+(row-1)*step));
        }
        int tailWidth=verticalSpeed?1:2;
        // 高位裁剪为一个字符行高；换位时仅滑入下一位、滑出当前位，不绘制其他邻行。
        tapeScissor(ctx,DIGIT_WIDTH,-ROLL_PITCH/2,(digits-tailWidth+1)*DIGIT_WIDTH,ROLL_PITCH/2+1);
        for(int column=0;column<digits-tailWidth;column++){
            if(numbers[1].charAt(column)==numbers[2].charAt(column)){
                var matrices=ctx.getMatrices();
                matrices.push();matrices.translate((column+1)*DIGIT_WIDTH,-mc.textRenderer.fontHeight/2.0,0);
                ctx.drawText(mc.textRenderer,String.valueOf(numbers[1].charAt(column)),0,0,0xffe6f4ee,false);
                matrices.pop();
            }else{
                for(int row=1;row<=2;row++){
                    long mark=lower+(row-1)*step;
                    double offset=tapeOffset(value,mark,step,ROLL_PITCH);
                    var matrices=ctx.getMatrices();
                    matrices.push();matrices.translate((column+1)*DIGIT_WIDTH,offset-mc.textRenderer.fontHeight/2.0,0);
                    ctx.drawText(mc.textRenderer,String.valueOf(numbers[row].charAt(column)),0,0,0xffe6f4ee,false);
                    matrices.pop();
                }
            }
        }
        ctx.disableScissor();
        for(int row=0;row<numbers.length;row++){
            long mark=lower+(row-1)*step;
            // 与窗外共用 value-mark：数值增大时所有正负刻度向下，减小时向上。
            double offset=tapeOffset(value,mark,step,ROLL_PITCH);
            var matrices=ctx.getMatrices();
            matrices.push();matrices.translate(0,offset-mc.textRenderer.fontHeight/2.0,0);
            // 高度末两位成组滚动；实际零值写 0，其余整百读数仍保留 00。
            String tail=verticalSpeed?numbers[row].substring(digits-1)
                    :mark==0?" 0":String.format(Locale.ROOT,"%02d",Math.abs(mark)%100);
            ctx.drawText(mc.textRenderer,tail,(digits-tailWidth+1)*DIGIT_WIDTH,0,0xffe6f4ee,false);
            matrices.pop();
        }
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
        var m=ctx.getMatrices();m.push();m.translate(cx,cy-radius/2f,0);m.scale(.85f,.85f,1);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,Text.translatable("hud.no_24_heli."+label),0,0,color);m.pop();
        m.push();m.translate(cx,cy+radius*.43,0);m.scale(.75f,.75f,1);
        ctx.drawCenteredTextWithShadow(mc.textRenderer,f(value)+unit,0,0,0xffe6f4ee);m.pop();
        // 数字之后再绘制针与针轴，并提高 GUI 深度；浮点旋转保留平滑动画。
        m.push();m.translate(cx,cy,1);
        ctx.fill(-2,-2,3,3,0xffffd66b);
        m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float)(135+Math.clamp((value-min)/(max-min),0,1)*270)));
        ctx.fill(0,0,radius-8,1,0xffffd66b);m.pop();
    }
    private static void line(DrawContext ctx,int x,int y,int ex,int ey,int color){
        int steps=Math.max(Math.abs(ex-x),Math.abs(ey-y));
        for(int i=0;i<=steps;i++){int px=x+(ex-x)*i/Math.max(steps,1),py=y+(ey-y)*i/Math.max(steps,1);ctx.fill(px,py,px+1,py+1,color);}
    }
    private static void warn(DrawContext ctx,String key,int y){text(ctx,"warning.no_24_heli."+key,10,y,0xffff6a58);}
}
