/** 文件：机舱内实体仪表、雷达、警示灯与涂装标识；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.client.hud.GroundProximityWarning;
import org.DJB.no_24_heli.client.hud.HeliRadar;
import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.entity.HelicopterEntity;

/**
 * 职责：将飞行 HUD 的全部信息移入固定在机体上的仪表板，指针、刻度和屏幕均参与世界深度遮挡。
 * 引用：net.minecraft.client.render.VertexConsumer 绘制表盘网格；
 * net.minecraft.client.font.TextRenderer 在世界矩阵上写字，TextLayerType.NORMAL 保留遮挡。
 * 生命周期：HelicopterRenderer 每帧调用；雷达复用服务器快照，不新增同步协议。
 * 端侧/线程：客户端渲染线程，只读飞行状态；启动后的文字独立背光，停机文字接受舱灯块光。
 * 残骸只绘制环境光下的烧蚀破损面板，不提交正常读数、雷达扫描或背光文字。
 * 排查：文字镜像查面板 Y 轴 180° 和字体负 Y 缩放；指针反转查角度公式；雷达方向查 HeliRadar.relative。
 */
@Environment(EnvType.CLIENT)
public final class CockpitDashboardRenderer {
    /** 表盘圆周 48 段，保持现有轮廓精度。 */
    private static final int SEGMENTS = 48;
    /** 表面距主面板两毫米，方框表也读取同一基准，避免不同仪表悬在不同深度。 */
    static final double FACE_Z = .002;
    /** 刻线和文字位于面板前三毫米内，指针基准六毫米；文字仍位于刻线前、指针后。 */
    private static final double INK_Z = .0025, TEXT_Z = .003, NEEDLE_Z = .006;
    /** 雷达导航笔画半宽 1.5 毫米，圆头半径相同；位于网格前、标签和目标后，避免共面闪烁。 */
    private static final double NAVIGATION_HALF_WIDTH=.0015, NAVIGATION_Z=(INK_Z+TEXT_Z)/2;
    /** 环境光下的外壳、表盘、文字及告警色；屏幕使用同一纯白实体底纹。 */
    private static final int CASE = 0xff343e47, BLACK = 0xff10191c, INK = 0xffd8eadc;
    private static final int GREEN = 0xff7ce3bc, AMBER = 0xffffd66b, RED = 0xffff6655;
    /** 图标的完整笔画宽度，单位为 -1..1 图标坐标；仅影响图标，不改变仪表刻线和表针。 */
    private static final double SYMBOL_STROKE_WIDTH = .07;
    /** 火焰六段三次贝塞尔轮廓：每段为起点、两控制点和终点，局部坐标约 -1..1，避免直角折线。 */
    private static final double[][] FIRE_CURVES = {
            {0,-.85, -.55,-.85, -.90,-.45, -.75,.02},
            {-.75,.02, -.70,.35, -.42,.40, -.43,.70},
            {-.43,.70, -.22,.50, -.26,.19, -.12,.10},
            {-.12,.10, .16,.48, .25,.74, .09,1.05},
            {.09,1.05, .58,.86, .87,.24, .78,-.25},
            {.78,-.25, .74,-.65, .38,-.85, 0,-.85}};
    /** 残骸为低反照率焦黑材质，破口和裂纹也只使用环境光，不使用满亮或自发光图层。 */
    private static final int WRECK_CASE = 0xff242321, WRECK_FACE = 0xff0b0d0e, WRECK_EDGE = 0xff39352e;
    /** 面板朝向舱内 +Z，转换到机体后朝 -Z，保证两种驾驶视角看到同一套仪表。 */
    private static final Vec3d NORMAL = new Vec3d(0, 0, 1);
    /** 面板按真实内舱截面缩放到 80%，保证下排表盘和右侧图标不穿入侧壁内衬。 */
    private static final float PANEL_SCALE = .80f;

    /** 字体在全部几何提交后统一绘制，避免 Immediate 切换图层使先前的 VertexConsumer 失效。 */
    private final List<Label> labels = new ArrayList<>();
    /** 方框表沿用屏幕 HUD 的量程、凸字窗与连续数字鼓，独立于圆形表针。 */
    private final CockpitTapeRenderer tapes = new CockpitTapeRenderer();
    /** 单帧文字保存独立矩阵，不能持有随后被 pop 的矩阵栈条目。 */
    private record Label(Matrix4f matrix, Text value, float x, float y, int color, int light) {}

    /**
     * 功能：绘制有厚度的主面板及全部仪表；引用 MC API：net.minecraft.client.util.math.MatrixStack 隔离机体内面板变换。
     * 端侧/线程：渲染线程；参数：heli/delta/matrices/consumers/light 为实体及帧环境；返回：无。
     * 副作用：提交世界顶点和文字；排查：所有信息在机体本地坐标内，不能调用屏幕 HUD 绘制入口。
     */
    public void render(HelicopterEntity heli, float delta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
        if (!heli.flight.wreck) light = light & 0xffff0000 | Math.max(light & 0xffff, LightmapTextureManager.pack(11, 0));
        matrices.push(); matrices.translate(0, 1.10, .82); matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
        matrices.scale(PANEL_SCALE, PANEL_SCALE, 1);
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(HelicopterRenderer.TEXTURE));
        if(heli.flight.wreck){
            damagedPanel(matrices,buffer,light);
            matrices.pop();return;
        }
        roundedPanel(matrices, buffer, -.85, -.42, .85, .35, 0, CASE, light);
        var s = heli.flight;
        var display = FlightInstruments.sample(heli, delta);
        int instrumentLight = s.running && !s.wreck ? LightmapTextureManager.MAX_LIGHT_COORDINATE : light;
        // 左驾驶席正前方：上排动力仪表，下排空速、垂速与世界高度。
        dial(matrices, buffer, -.65, .18, .105, "fuel_dial", display.fuel, 0, HeliConfig.INSTANCE.fuelCapacity, "", instrumentLight);
        dial(matrices, buffer, -.37, .18, .105, "rpm_dial", display.rpm * 100, 0, 100, "%", instrumentLight);
        dial(matrices, buffer, -.09, .18, .105, "collective_dial", display.collective * 100, 0, 100, "%", instrumentLight);
        dial(matrices, buffer, -.65, -.14, .115, "airspeed", display.airspeed, 0, 160, "km/h", instrumentLight);
        tapes.draw(matrices, buffer, -.455, .022, .17, .315, true, display.verticalSpeed, instrumentLight);
        tapes.draw(matrices, buffer, -.225, .022, .17, .315, false, display.altitude, instrumentLight);
        text(matrices, Text.translatable("hud.no_24_heli.vario"), -.37, .043, .0028f, .20, INK, instrumentLight);
        text(matrices, Text.translatable("hud.no_24_heli.altimeter"), -.14, .043, .0028f, .20, INK, instrumentLight);
        text(matrices, Text.literal("m/s"), -.37, .003, .0023f, .12, GREEN, instrumentLight);
        text(matrices, Text.literal("m"), -.14, .003, .0023f, .12, GREEN, instrumentLight);
        attitude(matrices, buffer, .16, .18, .082, display.pitch, display.roll, instrumentLight);
        radar(heli, delta, matrices, buffer, .54, .18, .12, instrumentLight);
        systems(heli, matrices, instrumentLight);
        indicators(heli, matrices, buffer, instrumentLight);
        matrices.pop();
        flushText(consumers,s.running&&!s.wreck);
    }

    /**
     * 功能：用撕裂边缘、空白破屏和缺损圆表替代残骸的整套正常仪表。
     * 引用 net.minecraft.client.render.VertexConsumer 提交普通受光实体几何，不请求背光或字体图层。
     * 端侧：客户端渲染线程；light 原样使用环境光；不采样飞行读数或修改服务端残骸状态。
     * 排查：坠机仍亮查是否误入正常仪表分支；亮点查是否提交了正常数字、指示灯或 queueText。
     */
    private static void damagedPanel(MatrixStack m,VertexConsumer b,int light){
        // 外轮廓缺角并向内凹陷；保留背板，避免透过破口直接看到机头外的天空。
        double[][] outline={{-.85,-.42},{.34,-.42},{.42,-.385},{.53,-.42},{.75,-.42},{.78,-.34},
                {.70,-.27},{.85,-.19},{.85,.14},{.76,.19},{.81,.30},{.69,.35},
                {-.68,.35},{-.72,.30},{-.81,.31},{-.85,.23}};
        Vec3d center=Vec3d.ZERO;
        for(int i=0;i<outline.length;i++){
            double[] current=outline[i],next=outline[(i+1)%outline.length];
            Vec3d p=new Vec3d(current[0],current[1],0),q=new Vec3d(next[0],next[1],0);
            triangle(m,b,center,p,q,WRECK_CASE,light);
            // 沿用正常面板 4.5 厘米厚度，断面使用暗色金属，不露出明亮的完整表壳。
            quad(m,b,p,p.add(0,0,-.045),q.add(0,0,-.045),q,WRECK_EDGE,light);
        }
        damagedDial(m,b,-.65,.18,.105,1,light);
        damagedDial(m,b,-.37,.18,.105,3,light);
        damagedDial(m,b,-.09,.18,.105,5,light);
        damagedDial(m,b,-.65,-.14,.115,7,light);
        damagedDial(m,b,.16,.18,.082,9,light);
        damagedDial(m,b,.54,.18,.12,11,light);
        // 高度/垂速屏及状态灯区彻底熄灭，不留下仍可读的彩色数字或亮灯。
        for(double x:new double[]{-.455,-.225}){
            rectangle(m,b,x,-.293,x+.17,.022,FACE_Z,WRECK_FACE,light);
            line(m,b,x+.025,-.27,x+.12,-.14,.002,NEEDLE_Z,WRECK_EDGE,light);
            line(m,b,x+.12,-.14,x+.08,-.04,.0015,NEEDLE_Z,WRECK_EDGE,light);
            line(m,b,x+.12,-.14,x+.16,-.10,.0015,NEEDLE_Z,WRECK_EDGE,light);
        }
        rectangle(m,b,.27,-.302,.79,.005,FACE_Z,WRECK_FACE,light);
        line(m,b,.30,-.29,.47,-.14,.003,NEEDLE_Z,WRECK_EDGE,light);
        line(m,b,.47,-.14,.44,-.02,.002,NEEDLE_Z,WRECK_EDGE,light);
        line(m,b,.47,-.14,.73,-.09,.002,NEEDLE_Z,WRECK_EDGE,light);
        // 仪表之间的烧蚀裂口贯穿底板，与表玻璃的细裂纹使用不同宽度。
        line(m,b,-.82,.30,-.48,.03,.008,FACE_Z,WRECK_FACE,light);
        line(m,b,-.48,.03,-.53,-.39,.006,FACE_Z,WRECK_FACE,light);
        line(m,b,-.48,.03,.02,-.05,.004,FACE_Z,WRECK_FACE,light);
    }

    /**
     * 功能：绘制缺了一段表圈、破裂玻璃和折断表针的静止残表，不调用正常 dial 或雷达。
     * 引用 net.minecraft.util.math.Vec3d 描述破口；客户端渲染线程，variant 固定不同表盘的破损方向。
     * 故障排查：破损不应随帧随机闪烁，所有裂纹深度在表面之前且共用普通环境光。
     */
    private static void damagedDial(MatrixStack m,VertexConsumer b,double x,double y,double r,int variant,int light){
        disk(m,b,x,y,r,FACE_Z,WRECK_FACE,light);
        for(int i=0;i<SEGMENTS;i++){
            int sector=(i+variant*3)%SEGMENTS;
            if(sector<7)continue;
            double a=i*Math.PI*2/SEGMENTS,next=(i+1)*Math.PI*2/SEGMENTS;
            double inner=r*(sector==7||sector==SEGMENTS-1?.70:.91);
            quad(m,b,new Vec3d(x+Math.cos(a)*inner,y+Math.sin(a)*inner,INK_Z),
                    new Vec3d(x+Math.cos(a)*r,y+Math.sin(a)*r,INK_Z),
                    new Vec3d(x+Math.cos(next)*r,y+Math.sin(next)*r,INK_Z),
                    new Vec3d(x+Math.cos(next)*inner,y+Math.sin(next)*inner,INK_Z),WRECK_EDGE,light);
        }
        double crackX=x+r*.17,crackY=y-r*.13;
        for(int ray=0;ray<4;ray++){
            double angle=variant*.73+ray*1.53;
            double middleX=x+Math.cos(angle)*r*.55,middleY=y+Math.sin(angle)*r*.55;
            line(m,b,crackX,crackY,middleX,middleY,.0016,NEEDLE_Z,WRECK_EDGE,light);
            line(m,b,middleX,middleY,x+Math.cos(angle+.18)*r*.94,y+Math.sin(angle+.18)*r*.94,
                    .0012,NEEDLE_Z,WRECK_EDGE,light);
        }
        // 两截暗金属残针方向不同，不再跟随实际飞行数据。
        double angle=variant*.41;
        line(m,b,x,y,x+Math.cos(angle)*r*.28,y+Math.sin(angle)*r*.28,.003,NEEDLE_Z,WRECK_EDGE,light);
        line(m,b,x+r*.22,y-r*.32,x+r*.53,y-r*.41,.003,NEEDLE_Z,WRECK_EDGE,light);
    }

    /**
     * 功能：构造圆角厚面板，角部连续分段；引用 MC API：net.minecraft.client.render.VertexConsumer 接收封边。
     * 端侧/线程：渲染线程；参数：m/b 为矩阵与缓存，x0/y0/x1/y1/z 为格数，color/light 为颜色和光照。
     * 返回：无；副作用：提交封闭正面及厚度；排查：平视边缘缺失查侧墙，背面由仪表罩遮住。
     */
    private static void roundedPanel(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1,
            double z, int color, int light) {
        final double radius = .055, thickness = .045;
        Vec3d center = new Vec3d((x0 + x1) / 2, (y0 + y1) / 2, z);
        for (int corner = 0; corner < 4; corner++) for (int step = 0; step < 8; step++) {
            double cx = corner == 0 || corner == 3 ? x1 - radius : x0 + radius;
            double cy = corner < 2 ? y1 - radius : y0 + radius;
            double a = (corner * 8 + step) * Math.PI / 16, next = a + Math.PI / 16;
            Vec3d p = new Vec3d(cx + radius * Math.cos(a), cy + radius * Math.sin(a), z);
            Vec3d q = new Vec3d(cx + radius * Math.cos(next), cy + radius * Math.sin(next), z);
            triangle(m, b, center, p, q, color, light);
            quad(m, b, p, p.add(0, 0, -thickness), q.add(0, 0, -thickness), q, color, light);
            // 每个圆角末端与下一角起点之间补齐直边及其侧墙。
            if (step == 7) {
                int nextCorner = (corner + 1) % 4;
                double nx = nextCorner == 0 || nextCorner == 3 ? x1 - radius : x0 + radius;
                double ny = nextCorner < 2 ? y1 - radius : y0 + radius;
                Vec3d r = new Vec3d(nx + radius * Math.cos(next), ny + radius * Math.sin(next), z);
                triangle(m, b, center, q, r, color, light);
                quad(m, b, q, q.add(0, 0, -thickness), r.add(0, 0, -thickness), r, color, light);
            }
        }
    }

    /**
     * 功能：绘制真实圆表圈、刻线、数字和针轴；引用 MC API：net.minecraft.client.render.VertexConsumer 绘制局部几何。
     * 端侧/线程：渲染线程；参数：m/b 为绘制环境，cx/cy/r 为格数，key/value/min/max/unit 为表量程；
     * 返回：无；副作用：提交圆形仪表；排查：表针钳制在量程内，数字仍显示实际空速或动力读数。
     */
    private void dial(MatrixStack m, VertexConsumer b, double cx, double cy, double r,
            String key, double value, double min, double max, String unit, int light) {
        disk(m, b, cx, cy, r, FACE_Z - .001, 0xff89979a, light);
        disk(m, b, cx, cy, r - .008, FACE_Z, BLACK, light);
        for (int i = 0; i <= 20; i++) {
            double angle = Math.toRadians(-225 + i * 13.5);
            double inner = r * (i % 5 == 0 ? .69 : .79);
            line(m, b, cx + Math.cos(angle) * inner, cy - Math.sin(angle) * inner,
                    cx + Math.cos(angle) * r * .89, cy - Math.sin(angle) * r * .89, .002, INK_Z, INK, light);
            if (i % 5 == 0) text(m, Text.literal(String.format(Locale.ROOT, "%.0f", min + (max - min) * i / 20)),
                    cx + Math.cos(angle) * r * .54, cy - Math.sin(angle) * r * .54, .0018f, r * .4, INK, light);
        }
        double fraction = Math.clamp((value - min) / (max - min), 0, 1);
        double angle = Math.toRadians(-225 + fraction * 270);
        line(m, b, cx, cy, cx + Math.cos(angle) * r * .76, cy - Math.sin(angle) * r * .76,
                .003, NEEDLE_Z, AMBER, light);
        disk(m, b, cx, cy, .008, NEEDLE_Z + .001, INK, light);
        text(m, Text.translatable("hud.no_24_heli." + key), cx,
                key.equals("airspeed") ? .043 : cy + r + .018, .0028f, .24, INK, light);
        text(m, Text.literal(String.format(Locale.ROOT, "%.0f", value) + unit),
                cx, cy - r * .58, .0023f, r * 1.3, GREEN, light);
    }

    /**
     * 功能：绘制圆形人工地平仪，地平线随俯仰和横滚变化；引用 MC API：net.minecraft.client.util.math.MatrixStack 组合姿态。
     * 端侧/线程：渲染线程；参数：m/b 为绘制环境，x/y/r 为表位，pitch/roll 为度，light 为光照；返回：无。
     * 副作用：提交圆内裁切的天地面；排查：水平机体时黄翼应与蓝褐分界平行。
     */
    private void attitude(MatrixStack m, VertexConsumer b, double x, double y, double r,
            double pitch, double roll, int light) {
        disk(m, b, x, y, r, FACE_Z - .001, 0xff89979a, light);
        // 沿圆内的水平细条裁切天地色，不用矩形背景穿出表圈。
        m.push(); m.translate(x, y, FACE_Z); m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float) -roll));
        for (int row = -32; row < 32; row++) {
            double yy = row * (r - .008) / 32, next = (row + 1) * (r - .008) / 32;
            double half = Math.sqrt(Math.max(0, (r - .008) * (r - .008) - yy * yy));
            double halfNext = Math.sqrt(Math.max(0, (r - .008) * (r - .008) - next * next));
            quad(m, b, new Vec3d(-half, yy, 0), new Vec3d(half, yy, 0), new Vec3d(halfNext, next, 0),
                    new Vec3d(-halfNext, next, 0), yy > -pitch * .002 ? 0xff397b9c : 0xff89633d, light);
        }
        double horizon = Math.clamp(-pitch * .002, -r * .85, r * .85);
        double width = Math.sqrt(r * r - horizon * horizon) * .83;
        line(m, b, -width, horizon, width, horizon, .002, .002, INK, light); m.pop();
        line(m, b, x - r * .65, y, x - r * .2, y, .003, NEEDLE_Z, AMBER, light);
        line(m, b, x + r * .2, y, x + r * .65, y, .003, NEEDLE_Z, AMBER, light);
        text(m, Text.translatable("cockpit.no_24_heli.attitude"), x, y + r + .018, .0028f, .24, INK, light);
    }

    /**
     * 功能：在实体圆屏重现完整机载雷达；引用 MC API：net.minecraft.client.render.VertexConsumer 提交圆环、目标和禁区弧。
     * 端侧/线程：渲染线程；参数：h/delta/m/b/cx/cy/r/light 为飞机、插值、环境及表位；返回：无；副作用：提交雷达。
     * 排查：512 米外导航点钳在圆边；所有目标使用 HeliRadar.relative，与第三人称雷达方向一致。
     */
    private void radar(HelicopterEntity h, float delta, MatrixStack m, VertexConsumer b,
            double cx, double cy, double r, int light) {
        disk(m, b, cx, cy, r + .008, FACE_Z - .001, 0xff89979a, light);
        disk(m, b, cx, cy, r, FACE_Z, BLACK, light);
        for (int i = 0; i < SEGMENTS; i++) {
            double a = i * Math.PI * 2 / SEGMENTS, next = (i + 1) * Math.PI * 2 / SEGMENTS;
            for (double radius : new double[]{r, r / 2}) line(m, b, cx + Math.cos(a) * radius, cy + Math.sin(a) * radius,
                    cx + Math.cos(next) * radius, cy + Math.sin(next) * radius, .001, INK_Z, 0xff3f6559, light);
        }
        line(m, b, cx - r, cy, cx + r, cy, .001, INK_Z, 0xff3f6559, light);
        line(m, b, cx, cy - r, cx, cy + r, .001, INK_Z, 0xff3f6559, light);
        double scan = (h.age + delta) * .045;
        line(m, b, cx, cy, cx + Math.sin(scan) * r, cy + Math.cos(scan) * r, .001, INK_Z, GREEN, light);
        navigationRadar(NavigationChartRenderer.radarStrokes(h,delta),m,b,cx,cy,r,light);
        Vec3d radarPosition=h.getLerpedPos(delta);
        double radarYaw=h.getYaw(delta);
        for (var zone : HeliRadar.zones) {
            double[] local = HeliRadar.relative(zone.pos().getX() + .5 - radarPosition.x, zone.pos().getZ() + .5 - radarPosition.z, radarYaw);
            double zx = local[0] / 512 * r, zy = -local[1] / 512 * r, zr = zone.radius() / 512.0 * r;
            if (Math.hypot(zx, zy) > r + zr || Math.hypot(zx, zy) + r < zr) continue;
            int steps = Math.max(64, (int) Math.ceil(zr / r * 256));
            for (int i = 0; i < steps; i++) {
                double a = i * Math.PI * 2 / steps, next = (i + 1) * Math.PI * 2 / steps;
                double x = zx + Math.cos(a) * zr, y = zy + Math.sin(a) * zr;
                double nx = zx + Math.cos(next) * zr, ny = zy + Math.sin(next) * zr;
                if (Math.hypot(x, y) <= r && Math.hypot(nx, ny) <= r)
                    line(m, b, cx + x, cy + y, cx + nx, cy + ny, .0015, NEEDLE_Z, RED, light);
            }
        }
        for (var contact : HeliRadar.contacts(h)) {
            double[] local = HeliRadar.relative(contact.x() - radarPosition.x, contact.z() - radarPosition.z, radarYaw);
            if (Math.hypot(local[0], local[1]) <= 512)
                disk(m, b, cx + local[0] / 512 * r, cy - local[1] / 512 * r, .004, NEEDLE_Z, contact.helicopter() ? AMBER : 0xff70cfff, light);
        }
        for (var pad : HeliRadar.helipads(h)) {
            double[] local = HeliRadar.relative(pad.pos().getX() + .5 - radarPosition.x, pad.pos().getZ() + .5 - radarPosition.z, radarYaw);
            if (Math.hypot(local[0], local[1]) <= 512)
                text(m, Text.literal("H"), cx + local[0] / 512 * r, cy - local[1] / 512 * r, .0017f, .025,
                        HeliRadar.restricted(pad) ? RED : GREEN, light);
        }
        if (h.flight.waypoint) {
            double[] local = HeliRadar.relative(h.flight.waypointX - radarPosition.x, h.flight.waypointZ - radarPosition.z, radarYaw);
            double scale = r / Math.max(512, Math.hypot(local[0], local[1]));
            double x = cx + local[0] * scale, y = cy - local[1] * scale;
            quad(m, b, new Vec3d(x, y + .008, NEEDLE_Z), new Vec3d(x - .008, y, NEEDLE_Z),
                    new Vec3d(x, y - .008, NEEDLE_Z), new Vec3d(x + .008, y, NEEDLE_Z), AMBER, light);
        }
        triangle(m, b, new Vec3d(cx, cy + .009, NEEDLE_Z), new Vec3d(cx - .006, cy - .006, NEEDLE_Z),
                new Vec3d(cx + .006, cy - .006, NEEDLE_Z), INK, light);
        String[] directions = {"north", "east", "south", "west"};
        double[][] world = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < directions.length; i++) {
            double[] local = HeliRadar.relative(world[i][0], world[i][1], radarYaw);
            text(m, Text.translatable("hud.no_24_heli." + directions[i]), cx + local[0] * (r + .024),
                    cy - local[1] * (r + .024), .002f, .025, INK, light);
        }
    }

    /**
     * 功能：将 HUD 共用的单位圆虚线投影到舱内面板，Y 翻转且每划首尾补圆头；仅客户端渲染线程。
     * 引用 net.minecraft.client.render.VertexConsumer 提交实体几何，沿用仪表光照及世界深度遮挡。
     * 排查：圆头半径须等于 line 的半宽，标签与目标始终在航路前，不在每个密集采样点重画端帽。
     */
    private static void navigationRadar(List<NavigationChartRenderer.RadarStroke> navigation,MatrixStack m,VertexConsumer b,
                                         double cx,double cy,double r,int light) {
        for(int i=0;i<navigation.size();i++) {
            var stroke=navigation.get(i);
            double ax=cx+stroke.ax()*r,ay=cy-stroke.ay()*r,bx=cx+stroke.bx()*r,by=cy-stroke.by()*r;
            line(m,b,ax,ay,bx,by,NAVIGATION_HALF_WIDTH,NAVIGATION_Z,stroke.color(),light);
            if(i==0||!navigation.get(i-1).connectsTo(stroke))
                disk(m,b,ax,ay,NAVIGATION_HALF_WIDTH,NAVIGATION_Z,stroke.color(),light);
            if(i+1==navigation.size()||!stroke.connectsTo(navigation.get(i+1)))
                disk(m,b,bx,by,NAVIGATION_HALF_WIDTH,NAVIGATION_Z,stroke.color(),light);
        }
    }

    /**
     * 功能：保留机组、模式、导航距离及离地读数，机内不再绘制机身或部件血条。
     * 引用 MC API：net.minecraft.text.Text 提供本地化名称，Entity#getName 读取实际机组姓名。
     * 端侧：渲染线程；参数：h/m/light 为飞机及环境；返回：无；副作用：提交面板读数。
     * 排查：故障和自动控制使用右框图标；离地读数不能替代世界高度表。
     */
    private void systems(HelicopterEntity h, MatrixStack m, int light) {
        var s = h.flight;
        text(m, Text.translatable(s.difficultyMode == 2 ? "hud.no_24_heli.state_zobayan" : s.hardcoreMode
                ? "hud.no_24_heli.state_mamba" : "hud.no_24_heli.state"), .53, -.335, .0020f, .52, GREEN, light);
        if (s.waypoint) text(m, Text.translatable("hud.no_24_heli.waypoint",
                (int) Math.hypot(s.waypointX - h.getX(), s.waypointZ - h.getZ())), .53, -.365, .002f, .52, AMBER, light);
        if (s.takeoffPowerLow)
            text(m, Text.translatable("cockpit.no_24_heli.takeoff_power"),
                    .53, -.395, .0021f, .52, AMBER, light);
        else if (GroundProximityWarning.level() > 0 && MinecraftClient.getInstance().player != null
                && MinecraftClient.getInstance().player.getVehicle() == h)
            text(m, Text.translatable("hud.no_24_heli.ground_height", (int) Math.ceil(GroundProximityWarning.height())),
                    .53, -.395, .0021f, .52, INK, light);
        for (int seat = 0; seat < 2; seat++) {
            final boolean pilot = seat == 0;
            Text name = h.cabin.occupant(seat)!=null ? h.cabin.occupant(seat).getName()
                    : Text.translatable("hud.no_24_heli.empty_seat");
            text(m, Text.translatable(pilot ? "hud.no_24_heli.pilot" : "hud.no_24_heli.copilot").append(": ").append(name),
                    pilot ? -.54 : -.02, -.355, .0025f, .48, INK, light);
        }
    }

    /**
     * 功能：在右侧双框排列十二种异常与九种控制图标；引用 MC API：net.minecraft.util.math.Vec3d 绘制实体指示器。
     * 端侧：渲染线程；参数：h/m/b/light 为飞机及环境；返回：无；副作用：提交状态图标。
     * 排查：异常发生才点亮红/琥珀灯，控制启用显示绿色；近地警告只读取当前乘坐飞机。
     */
    private static void indicators(HelicopterEntity h, MatrixStack m, VertexConsumer b, int light) {
        var s = h.flight;
        var player = MinecraftClient.getInstance().player;
        boolean sink = player != null && player.getVehicle() == h && GroundProximityWarning.warning();
        boolean[] faults = {s.engine < 20, s.rotor < 50, s.tail < 20, s.fuel < 80, s.hull < 35, s.tank < 35,
                !s.running && !h.isOnGround() && s.armed, s.running && !s.armed, s.restricted, sink, s.landingBlocked, s.hasFire()};
        String[] warningKeys = {"engine", "rotor", "tail", "fuel", "hull", "tank", "autorotation", "spooling", "restricted", "sink", "landing_blocked", "fire"};
        roundedPanel(m, b, .26, -.17, .80, .015, FACE_Z - .001, 0xff586775, light);
        rectangle(m, b, .27, -.16, .79, .005, FACE_Z, BLACK, light);
        // 上框两排六列的最后一格加入统一火灾灯，不挪动已有指示器。
        for (int i = 0; i < faults.length; i++) icon(m, b, warningKeys[i], .315 + i % 6 * .086,
                -.035 - i / 6 * .075, .044, faults[i], i == 7 ? AMBER : RED, light);
        boolean[] enabled = {s.altitudeHold, s.trim, s.cruise, s.gear, s.autopilot, s.autoPhase == 1 || s.autoPhase == 2,
                s.autoPhase == 3, s.waypoint,s.searchlight&&!s.wreck};
        String[] controlKeys = {"altitude", "trim", "cruise", "gear", "autopilot", "takeoff", "landing", "waypoint","searchlight"};
        roundedPanel(m, b, .26, -.31, .80, -.185, FACE_Z - .001, 0xff586775, light);
        rectangle(m, b, .27, -.302, .79, -.193, FACE_Z, BLACK, light);
        for (int i = 0; i < enabled.length; i++) icon(m, b, controlKeys[i], .32 + i % 5 * .105,
                -.217 - i / 5 * .057, .038, enabled[i], GREEN, light);
    }

    /**
     * 功能：绘制带灯座的单个符号，停用时保留暗轮廓；引用 MC API：net.minecraft.client.util.math.MatrixStack 隔离符号缩放。
     * 端侧：渲染线程；参数：m/b/key/x/y/size/active/onColor/light 为环境及状态；返回：无；副作用：提交图标。
     * 排查：不能用文字标签冒充图标，颜色和灯角共同区分激活与停用。
     */
    private static void icon(MatrixStack m, VertexConsumer b, String key, double x, double y, double size,
            boolean active, int onColor, int light) {
        rectangle(m, b, x - size * .68, y - size * .64, x + size * .68, y + size * .64,
                INK_Z, active ? 0xff243b40 : 0xff111b23, light);
        m.push(); m.translate(x, y, NEEDLE_Z); m.scale((float) (size / 2), (float) (size / 2), 1);
        int color = active ? onColor : 0xff435560;
        switch (key) {
            case "altitude", "trim", "cruise", "gear", "autopilot", "takeoff", "landing", "waypoint","searchlight" -> controlSymbol(m, b, key, color, light);
            default -> faultSymbol(m, b, key, color, light);
        }
        disk(m, b, 1.07, -.92, .11, .002, active ? onColor : 0xff26353b, light);
        m.pop();
    }

    /**
     * 功能：绘制发动机、桨叶、油泵、舱体、油箱及飞行异常的专属象形符号。
     * 引用 MC API：net.minecraft.client.render.VertexConsumer 提交线条、圆环和警示三角。
     * 端侧：渲染线程；参数：m/b/key/color/light 为环境及符号；返回：无；副作用：提交故障图形。
     * 排查：尾桨和主桨分别用尾梁与旋翼俯视图，油量与油箱破损不能使用同一个符号。
     */
    private static void faultSymbol(MatrixStack m, VertexConsumer b, String key, int color, int light) {
        switch (key) {
            case "fire" -> fireOutline(m, b, color, light);
            case "engine" -> {
                // 发动机缸体、上部进气和右侧排气，采用常见的发动机故障轮廓。
                path(m,b,color,light, -.66,.38, -.38,.38, -.38,.63, .28,.63, .28,.38, .62,.38,
                        .62,.12, .87,.27, .87,-.35, .62,-.20, .62,-.57, -.45,-.57, -.66,-.33, -.66,.38);
                path(m,b,color,light, -.9,.22, -.9,-.25);
                stroke(m,b,-.9,-.02,-.66,-.02,color,light);
                stroke(m,b,-.27,.86,.23,.86,color,light);
            }
            case "rotor" -> rotorSymbol(m,b,0,0,.9,color,light);
            case "spooling" -> {
                rotorSymbol(m,b,0,0,.58,color,light);
                arc(m,b,0,0,.91,25,330,color,light);
                path(m,b,color,light, .46,-.55, .79,-.46, .83,-.81);
            }
            case "tail" -> {
                path(m,b,color,light, -.9,-.20, -.9,.2, -.45,.2, .28,.04);
                stroke(m,b,-.45,-.20,.28,-.04,color,light);
                path(m,b,color,light, -.57,.19, -.67,.64, -.82,.64, -.78,.2);
                rotorSymbol(m,b,.45,0,.46,color,light);
            }
            case "fuel" -> {
                path(m,b,color,light, -.74,-.77, -.74,.64, -.61,.78, .15,.78, .28,.64, .28,-.77);
                path(m,b,color,light, -.56,.5, .08,.5, .08,.12, -.56,.12, -.56,.5);
                stroke(m,b,-.91,-.8,.45,-.8,color,light);
                curve(m,b,.28,-.22,.7,-.22,.34,-.65,.73,-.65,color,light);
                path(m,b,color,light, .73,-.65, .84,-.52, .84,.37, .57,.65, .43,.52, .65,.24);
            }
            case "hull" -> {
                helicopterSymbol(m,b,color,light);
                path(m,b,color,light, -.17,.12, -.33,-.06, -.1,-.17, -.27,-.37);
            }
            case "tank" -> {
                path(m,b,color,light, -.68,-.52, -.68,.47, -.47,.69, .35,.69, .61,.45, .61,-.52, -.68,-.52);
                path(m,b,color,light, -.32,.69, -.32,.9, .12,.9, .12,.69);
                path(m,b,color,light, -.47,.39, -.27,.16, -.4,-.03, -.15,-.31);
                // 单独滴落的油滴区别于低燃油的加油机，避免两种故障看起来一样。
                curve(m,b,.36,-.55,.14,-.80,.12,-.95,.35,-.95,color,light);
                curve(m,b,.35,-.95,.58,-.95,.56,-.80,.36,-.55,color,light);
            }
            case "autorotation" -> {
                rotorSymbol(m,b,-.28,.22,.57,color,light);
                arrow(m,b,.64,.66,.64,-.65,color,light);
                arc(m,b,-.28,.22,.79,65,300,color,light);
            }
            case "restricted" -> {
                ring(m,b,0,0,.89,color,light);
                stroke(m,b,-.62,-.62,.62,.62,color,light);
            }
            case "sink" -> {
                arrow(m,b,0,.86,0,-.39,color,light);
                path(m,b,color,light, -.38,.26, 0,-.1, .38,.26);
                path(m,b,color,light, -.88,-.83, -.61,-.60, -.3,-.77, .04,-.6, .41,-.79, .85,-.6);
            }
            case "landing_blocked" -> {
                arrow(m,b,-.47,.85,-.47,.02,color,light);
                path(m,b,color,light, -.9,-.84, .9,-.84);
                path(m,b,color,light, -.61,-.7, -.61,-.22, -.19,-.22, -.19,-.7);
                stroke(m,b,-.61,-.46,-.19,-.46,color,light);
                stroke(m,b,.19,.07,.79,.67,color,light);
                stroke(m,b,.19,.67,.79,.07,color,light);
            }
        }
    }

    /** 逐段连接轮廓点；net.minecraft.client.render.VertexConsumer 由 stroke 写入，CLIENT 渲染线程，无填充面。 */
    private static void path(MatrixStack m, VertexConsumer b, int color, int light, double... xy) {
        for (int i=2;i<xy.length;i+=2) stroke(m,b,xy[i-2],xy[i-1],xy[i],xy[i+1],color,light);
    }

    /**
     * 功能：按切线法向构造十六段连续细线带，油管、机身和火焰共用，避免短矩形拼接的锯齿缝隙。
     * 引用 net.minecraft.client.render.VertexConsumer 经 quad 提交；仅 CLIENT 渲染线程，无状态副作用。
     */
    private static void curve(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1,
            double x2, double y2, double x3, double y3, int color, int light) {
        double px=x0,py=y0,half=SYMBOL_STROKE_WIDTH/2;
        double length=Math.hypot(x1-x0,y1-y0);
        double nx=-(y1-y0)/length*half,ny=(x1-x0)/length*half;
        for (int i=1;i<=16;i++) {
            double t=i/16.0,u=1-t;
            double x=u*u*u*x0+3*u*u*t*x1+3*u*t*t*x2+t*t*t*x3;
            double y=u*u*u*y0+3*u*u*t*y1+3*u*t*t*y2+t*t*t*y3;
            double tx=u*u*(x1-x0)+2*u*t*(x2-x1)+t*t*(x3-x2);
            double ty=u*u*(y1-y0)+2*u*t*(y2-y1)+t*t*(y3-y2);
            length=Math.hypot(tx,ty);
            double nextX=-ty/length*half,nextY=tx/length*half;
            quad(m,b,new Vec3d(px+nx,py+ny,.001),new Vec3d(px-nx,py-ny,.001),
                    new Vec3d(x-nextX,y-nextY,.001),new Vec3d(x+nextX,y+nextY,.001),color,light);
            px=x;py=y;nx=nextX;ny=nextY;
        }
    }

    /** 以度数定义圆弧，半径为图标局部单位；CLIENT 渲染线程，用于预转、自动驾驶和速度表轮廓。 */
    private static void arc(MatrixStack m, VertexConsumer b, double x, double y, double radius,
            double from, double to, int color, int light) {
        int steps=(int)Math.ceil(Math.abs(to-from)/12);
        double inner=radius-SYMBOL_STROKE_WIDTH/2,outer=radius+SYMBOL_STROKE_WIDTH/2;
        for (int i=0;i<steps;i++) {
            double a=Math.toRadians(from+(to-from)*i/steps),next=Math.toRadians(from+(to-from)*(i+1)/steps);
            quad(m,b,new Vec3d(x+Math.cos(a)*inner,y+Math.sin(a)*inner,.001),
                    new Vec3d(x+Math.cos(a)*outer,y+Math.sin(a)*outer,.001),
                    new Vec3d(x+Math.cos(next)*outer,y+Math.sin(next)*outer,.001),
                    new Vec3d(x+Math.cos(next)*inner,y+Math.sin(next)*inner,.001),color,light);
        }
    }

    /** 侧视直升机的机舱、风挡、尾梁、主桨和滑橇轮廓；CLIENT 绘制，作为机身损伤及自动起降的实物识别基础。 */
    private static void helicopterSymbol(MatrixStack m, VertexConsumer b, int color, int light) {
        curve(m,b,-.28,.24,-.69,.30,-.91,.04,-.86,-.18,color,light);
        curve(m,b,-.86,-.18,-.79,-.43,-.20,-.42,.18,-.25,color,light);
        path(m,b,color,light, .18,-.25, .34,.08, .79,.07, .94,.41, .78,.41, .65,.20, -.28,.24);
        path(m,b,color,light, -.37,.22, -.45,-.02, -.79,-.02);
        path(m,b,color,light, -.20,.25, -.20,.53, -.70,.53, .43,.53);
        stroke(m,b,-.53,-.36,-.53,-.59,color,light);
        stroke(m,b,.05,-.30,.05,-.59,color,light);
        curve(m,b,-.84,-.49,-.85,-.64,-.58,-.61,.30,-.61,color,light);
    }

    /**
     * 功能：用统一细线勾勒火焰外轮廓及一条内焰，不填充色块，避免卡通贴纸观感。
     * 引用 net.minecraft.client.render.VertexConsumer 通过曲线笔画提交轮廓；CLIENT 渲染线程。
     * 排查：线条粗细由 stroke 统一控制，不能重新改用三角扇填充。
     */
    private static void fireOutline(MatrixStack m, VertexConsumer b, int color, int light) {
        for (double[] c : FIRE_CURVES) curve(m,b,c[0],c[1],c[2],c[3],c[4],c[5],c[6],c[7],color,light);
        curve(m,b,-.15,-.55,-.34,-.28,.02,-.03,.04,.27,color,light);
        curve(m,b,.04,.27,.41,-.18,.30,-.56,-.15,-.55,color,light);
    }

    /**
     * 功能：绘制辅助、起降、导航与探照灯九种简笔图标；引用 MC API：net.minecraft.client.render.VertexConsumer 提交形状。
     * 端侧：渲染线程；参数：m/b/key/color/light 为环境及符号；返回：无；副作用：提交控制图形。
     * 排查：起飞与降落箭头朝向相反，起落架使用双轮，不用 ON/OFF 字串代替状态。
     */
    private static void controlSymbol(MatrixStack m, VertexConsumer b, String key, int color, int light) {
        switch (key) {
            case "altitude" -> {
                stroke(m,b,-.79,-.8,-.79,.8,color,light);
                for (int tick=0;tick<5;tick++) stroke(m,b,-.79,-.8+tick*.4,-.55,-.8+tick*.4,color,light);
                m.push();m.translate(.18,.03,0);m.scale(.65f,.65f,1);helicopterSymbol(m,b,color,light);m.pop();
            }
            case "trim" -> {
                // 正面机身与两侧水平基准，直接表达姿态配平。
                arc(m,b,0,-.02,.29,0,360,color,light);
                path(m,b,color,light, -.84,.47, .84,.47);
                stroke(m,b,0,.47,0,.26,color,light);
                path(m,b,color,light, -.84,-.55, -.84,-.12, -.44,-.12);
                path(m,b,color,light, .84,-.55, .84,-.12, .44,-.12);
                stroke(m,b,-.84,-.55,.84,-.55,color,light);
            }
            case "cruise" -> {
                arc(m,b,0,-.13,.81,0,180,color,light);
                stroke(m,b,-.81,-.13,.81,-.13,color,light);
                stroke(m,b,0,-.13,.45,.43,color,light);
                for (double angle:new double[]{25,90,155}) {
                    double a=Math.toRadians(angle);
                    stroke(m,b,Math.cos(a)*.62,Math.sin(a)*.62-.13,
                            Math.cos(a)*.76,Math.sin(a)*.76-.13,color,light);
                }
                arrow(m,b,-.52,-.58,.60,-.58,color,light);
            }
            case "gear" -> {
                // 侧视双滑橇及斜撑，不使用轮式起落架或类似警铃的正面剪影。
                stroke(m,b,-.52,.72,.42,.72,color,light);
                path(m,b,color,light, -.37,.72, -.51,-.12, -.15,-.55);
                path(m,b,color,light, .29,.72, .17,-.12, .52,-.55);
                stroke(m,b,-.85,-.12,.55,-.12,color,light);
                curve(m,b,.55,-.12,.78,-.12,.81,.02,.84,.13,color,light);
                stroke(m,b,-.53,-.55,.79,-.55,color,light);
                curve(m,b,.79,-.55,.95,-.55,.99,-.39,.99,-.30,color,light);
            }
            case "autopilot" -> {
                m.push();m.scale(.65f,.65f,1);helicopterSymbol(m,b,color,light);m.pop();
                arc(m,b,0,0,.96,20,320,color,light);
                path(m,b,color,light, .52,-.57, .75,-.62, .81,-.88);
            }
            case "takeoff", "landing" -> {
                m.push();m.translate(-.27,.19,0);m.scale(.64f,.64f,1);helicopterSymbol(m,b,color,light);m.pop();
                stroke(m,b,-.90,-.82,.90,-.82,color,light);
                arrow(m,b,.65,key.equals("takeoff")?-.51:.82,.65,key.equals("takeoff")?.82:-.51,color,light);
            }
            case "waypoint" -> {
                curve(m,b,0,-.92,-.18,-.57,-.65,-.04,-.65,.31,color,light);
                curve(m,b,-.65,.31,-.65,1.08,.65,1.08,.65,.31,color,light);
                curve(m,b,.65,.31,.65,-.04,.18,-.57,0,-.92,color,light);
                ring(m,b,0,.31,.23,color,light);
            }
            case "searchlight" -> {
                curve(m,b,-.25,.57,-1.12,.57,-1.12,-.42,-.25,-.42,color,light);
                stroke(m,b,-.25,-.42,-.25,.57,color,light);
                path(m,b,color,light, -.66,-.4, -.66,-.76, -.03,-.76);
                stroke(m,b,.03,.40,.85,.72,color,light);
                stroke(m,b,.03,.06,.95,.06,color,light);
                stroke(m,b,.03,-.27,.85,-.58,color,light);
            }
        }
    }

    /**
     * 功能：绘制四叶旋翼符号；引用 MC API：无；端侧：渲染线程。
     * 参数：m/b/x/y/r/color/light 为环境和符号尺寸；返回：无；副作用：提交桨叶与轴圈；排查：四臂需相隔 90°。
     */
    private static void rotorSymbol(MatrixStack m, VertexConsumer b, double x, double y, double r, int color, int light) {
        ring(m, b, x, y, r * .16, color, light);
        for (int i = 0; i < 4; i++) {
            m.push();m.translate(x,y,0);m.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(i*90));
            path(m,b,color,light, .16*r,.07*r, .77*r,.16*r, .95*r,.07*r,
                    .92*r,-.11*r, .19*r,-.07*r, .16*r,.07*r);
            m.pop();
        }
    }

    /**
     * 功能：绘制指示器闭合圆环；引用 MC API：无；端侧：渲染线程。
     * 参数：m/b/x/y/r/color/light 为绘制环境和半径；返回：无；副作用：提交环线；排查：不填满中心以保留内部象形图。
     */
    private static void ring(MatrixStack m, VertexConsumer b, double x, double y, double r, int color, int light) {
        arc(m,b,x,y,r,0,360,color,light);
    }

    /**
     * 功能：绘制控制箭头；引用 MC API：无；端侧：渲染线程。
     * 参数：m/b/x0/y0/x1/y1/color/light 为环境与端点；返回：无；副作用：提交箭杆及箭头；排查：头部须朝向第二个端点。
     */
    private static void arrow(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1, int color, int light) {
        stroke(m, b, x0, y0, x1, y1, color, light);
        double angle = Math.atan2(y1 - y0, x1 - x0);
        for (double side : new double[]{-.6, .6})
            stroke(m, b, x1, y1, x1 - Math.cos(angle + side) * .4, y1 - Math.sin(angle + side) * .4, color, light);
    }

    /**
     * 功能：绘制统一粗细的符号笔画；引用 MC API：net.minecraft.client.render.VertexConsumer 接收面板条带。
     * 端侧：渲染线程；参数：m/b/x0/y0/x1/y1/color/light 为环境与线段；返回：无；副作用：提交图标线。
     * 排查：完整线宽为 SYMBOL_STROKE_WIDTH，line 接收半宽；随图标矩阵缩放，不使用实心填充图形。
     */
    private static void stroke(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1, int color, int light) {
        line(m, b, x0, y0, x1, y1, SYMBOL_STROKE_WIDTH/2, .001, color, light);
    }

    /**
     * 功能：在实体表面居中写字且保留深度遮挡；引用 MC API：net.minecraft.client.font.TextRenderer#draw 使用 NORMAL 层。
     * 端侧/线程：渲染线程；参数：m/value/x/y/scale/maxWidth/color/light 为环境、文字及格数尺寸；返回：无。
     * 副作用：提交字体顶点；排查：长姓名或翻译通过 maxWidth 等比缩小，不伸出面板；禁用 SEE_THROUGH。
     */
    private void text(MatrixStack m, Text value, double x, double y, float scale,
            double maxWidth, int color, int light) {
        var font = MinecraftClient.getInstance().textRenderer;
        int width = font.getWidth(value);
        float actual = Math.min(scale, (float) (maxWidth / Math.max(1, width)));
        m.push(); m.translate(x, y, TEXT_Z); m.scale(actual, -actual, actual);
        labels.add(new Label(new Matrix4f(m.peek().getPositionMatrix()), value,
                -width / 2f, -font.fontHeight / 2f, color, light)); m.pop();
    }

    /**
     * 功能：在几何结束后提交文字批次；先写入实际字形深度，启动时再补背光，避免仅靠背景面深度裁切字形。
     * 引用 MC API：net.minecraft.client.font.TextRenderer#draw 始终使用普通深度字体层，背光沿用同一矩阵。
     * 端侧/线程：渲染线程；参数：vertices 为消费者提供者；返回：无；副作用：提交并清空单帧字体缓存。
     * 排查：Not building 错误查是否在几何中途请求字体图层，不能复用已被刷新过的消费者。
     */
    private void flushText(VertexConsumerProvider vertices,boolean powered) {
        var font = MinecraftClient.getInstance().textRenderer;
        for (Label label : labels) {
            font.draw(label.value, label.x, label.y, label.color, false, label.matrix,
                    vertices, TextRenderer.TextLayerType.NORMAL, 0, label.light);
            if(powered)SearchlightRenderer.queueText(label.value,label.x,label.y,label.color,label.matrix);
        }
        labels.clear();
    }

    /**
     * 功能：提交世界面板矩形；引用 MC API：net.minecraft.util.math.Vec3d 定义局部顶点。
     * 端侧：渲染线程；参数：m/b/x0/y0/x1/y1/z/color/light 为环境及格数矩形；返回：无；副作用：提交面。
     * 排查：消失查矩形面积和深度。
     */
    private static void rectangle(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1,
            double z, int color, int light) {
        if (x0 == x1) return;
        quad(m, b, new Vec3d(x0, y0, z), new Vec3d(x1, y0, z), new Vec3d(x1, y1, z), new Vec3d(x0, y1, z), color, light);
    }

    /**
     * 功能：绘制有宽度的表针或刻线；引用 MC API：net.minecraft.util.math.Vec3d 构造平面条带。
     * 端侧：渲染线程；参数：m/b/x0/y0/x1/y1/width/z/color/light 为环境与格数线段；返回：无；副作用：提交条带。
     * 排查：零长线不提交，避免法线或 UV 出现无效值。
     */
    private static void line(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1,
            double width, double z, int color, int light) {
        double length = Math.hypot(x1 - x0, y1 - y0);
        if (length == 0) return;
        double dx = -(y1 - y0) / length * width, dy = (x1 - x0) / length * width;
        quad(m, b, new Vec3d(x0 + dx, y0 + dy, z), new Vec3d(x0 - dx, y0 - dy, z),
                new Vec3d(x1 - dx, y1 - dy, z), new Vec3d(x1 + dx, y1 + dy, z), color, light);
    }

    /**
     * 功能：用非退化四边形扇面绘制圆盘；引用 MC API：net.minecraft.util.math.Vec3d 表达周向节点。
     * 端侧：渲染线程；参数：m/b/x/y/r/z/color/light 为环境及格数圆；返回：无；副作用：提交圆盘。
     * 排查：圆形棱角查 SEGMENTS；不用重复顶点拼成退化四边形，以兼容光影切线。
     */
    private static void disk(MatrixStack m, VertexConsumer b, double x, double y, double r, double z, int color, int light) {
        Vec3d center = new Vec3d(x, y, z);
        int segments = r < .015 ? 12 : SEGMENTS;
        // 两个相邻扇区共用一个非退化四边形，避免把每个圆盘拆为 144 个面。
        for (int i = 0; i < segments; i += 2) {
            double a = i * Math.PI * 2 / segments, middle = (i + 1) * Math.PI * 2 / segments;
            double next = (i + 2) * Math.PI * 2 / segments;
            quad(m, b, center, new Vec3d(x + r * Math.cos(a), y + r * Math.sin(a), z),
                    new Vec3d(x + r * Math.cos(middle), y + r * Math.sin(middle), z),
                    new Vec3d(x + r * Math.cos(next), y + r * Math.sin(next), z), color, light);
        }
    }

    /**
     * 功能：把三角形分为三个非退化四边形；引用 MC API：net.minecraft.util.math.Vec3d#lerp 求边中点。
     * 端侧：渲染线程；参数：m/b/a/c/d/color/light 为环境与三顶点；返回：无；副作用：提交面。
     * 排查：光影暗面查是否退回重复顶点的伪四边形。
     */
    private static void triangle(MatrixStack m, VertexConsumer b, Vec3d a, Vec3d c, Vec3d d, int color, int light) {
        Vec3d center = a.add(c).add(d).multiply(1.0 / 3), ac = a.lerp(c, .5), cd = c.lerp(d, .5), da = d.lerp(a, .5);
        quad(m, b, a, ac, center, da, color, light); quad(m, b, c, cd, center, ac, color, light);
        quad(m, b, d, da, center, cd, color, light);
    }

    /**
     * 功能：提交与机体统一格式的面板四边形；引用 MC API：net.minecraft.client.render.VertexConsumer 写入实体属性。
     * 端侧：渲染线程；参数：m/b/a/c/d/e/color/light 为环境及四顶点；返回：无；副作用：提交四顶点。
     * 排查：UV 在各面展开，矩阵、覆盖层和环境光由 HelicopterModel.vertex 统一处理。
     */
    private static void quad(MatrixStack m, VertexConsumer b, Vec3d a, Vec3d c, Vec3d d, Vec3d e, int color, int light) {
        HelicopterModel.vertex(m, b, a, NORMAL, color, light, 0, 0);
        HelicopterModel.vertex(m, b, c, NORMAL, color, light, 1, 0);
        HelicopterModel.vertex(m, b, d, NORMAL, color, light, 1, 1);
        HelicopterModel.vertex(m, b, e, NORMAL, color, light, 0, 1);
    }
}
