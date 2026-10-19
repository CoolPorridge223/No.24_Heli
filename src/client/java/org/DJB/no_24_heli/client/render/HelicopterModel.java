/**
 * 文件：参考蓝白警用直升机的连续曲面机模，包含开窗蒙皮、内饰与独立运动部件。
 * 端侧：CLIENT；关联实体：no_24_heli:helicopter。
 */
package org.DJB.no_24_heli.client.render;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.HelicopterCabin;

/**
 * 职责：缓存圆滑机身、锥形尾梁、弧形滑橇与旋翼的顶点，不以立方体拼接机身。
 * 引用：net.minecraft.util.math.Vec3d 计算曲面切线与法线；
 * net.minecraft.client.render.VertexConsumer 提交带光照的实体网格。
 * 生命周期：HelicopterRenderer 创建一次，逐帧只变换部件和选择涂装。
 * 端侧/线程：仅客户端渲染线程，不访问或修改权威物理。
 * 排查：玻璃不透先查蒙皮开孔与独立透明层；棱面查逐顶点法线；缺面查绕序。
 */
@Environment(EnvType.CLIENT)
public final class HelicopterModel {
    /** 近景保持原 48×56 细分，远景减为 24×28；坐标单位为格，+Z 为机头。 */
    private final int radialSegments, longitudinalSegments;
    /** 远景不生成毫米级板缝和铆钉；只改变显示细节，不改变实体碰撞与飞行。 */
    private final boolean distant;
    /** 腰带与机腹单独分区；默认警用色仍使用原来的主色和白色，其他涂装按名称配色。 */
    private static final int WHITE = 0, PAINT = 1, STRIPE = 2, DARK = 3, METAL = 4, SEAT = 5,
            GLASS = 6, LINER = 7, SEAL = 8, SEAM = 9, BELT = 10, BELLY = 11;
    /** 机身蒙皮厚 4.5 厘米，玻璃厚 6 毫米；端部按截面半径缩小厚度，避免内衬反穿。 */
    private static final double SHELL_THICKNESS = .045, GLASS_INSET = .012, GLASS_THICKNESS = .006;
    /** 面向机头的左侧保留 24，右侧使用 Laker；字样与蒙皮曲率一致，分别绑定材质。 */
    private final List<Face> number = new ArrayList<>(), wordmark = new ArrayList<>();
    /** 迷彩只缓存外壳喷漆面，不覆盖金属、窗框、板缝和内饰；三种环境共用连续 UV。 */
    private final List<Face> camouflage = new ArrayList<>();
    /** 旧编号 0–5 保留丛林、雪、沙、黑、救援及紫金色；新增默认 6 为参考图蓝白警用色。 */
    private static final int[] PAINT_COLORS = {0xff526b40, 0xffe5ebed, 0xffc3a06c, 0xff252b33, 0xffe97a28, 0xff64389b, 0xff234aa4};
    /** 顶点保持白色，透明中心由贴图 alpha 丢弃，窗缘反光不依赖顶点透明混合。 */
    private static final int GLASS_COLOR = 0xffffffff;
    /** 地板顶面沿用原 0.64 格高度，厚 3.5 厘米；相对内蒙皮另留 2.5 厘米净距。 */
    private static final double FLOOR_TOP = .64, FLOOR_THICKNESS = .035, FLOOR_CLEARANCE = .025;
    /** 地板限定在后隔舱之前与仪表罩下方，沿机腹按 32 段采样，单位：格。 */
    private static final double FLOOR_BACK = -1.02, FLOOR_FRONT = 1.10;
    private static final int FLOOR_SEGMENTS = 32;
    /** 色带按高度平面裁切，机腹分界低于字样下缘，防止金色字样落在金色底漆上。 */
    private static final double BELLY_TOP = .56, BELT_BOTTOM = .79, BELT_TOP = 1.10, TRIM_TOP = 1.15;
    /** 六种涂装的外漆/起落架颜色；迷彩外壳由独立贴图提供，不再保留白色底壳。 */
    private static final int[] LIVERY_COLORS = {0xff4b623d, 0xffe8edf0, 0xffcbb485, 0xff111214, 0xfff07721, 0xff552583};
    private static final int[] GEAR_COLORS = {0xff293628, 0xff697780, 0xff8a704d, 0xff111214, 0xff30363b, 0xfffdb927};
    /** 紫金采用饱和金色饰带，救援白腰带保留高可见度，三款迷彩每格约重复 0.36 次。 */
    private static final int GOLD = 0xfffdb927, RESCUE_WHITE = 0xfff2f4ee, RESCUE_DARK = 0xff30363b;
    private static final double CAMOUFLAGE_SCALE = .36;
    /** 各纵截面依次为 Z、半宽、中心高度、半高；形状按参考图的圆鼻、宽舱和收腰机尾采样。 */
    private static final double[][] BODY_PROFILE = {
            {-5.06, .075, 1.87, .075}, {-3.40, .14, 1.68, .14}, {-2.10, .20, 1.49, .20},
            {-1.78, .25, 1.40, .25}, {-1.55, .43, 1.21, .38}, {-1.15, .83, 1.23, .68},
            {-.65, .97, 1.24, .80}, {0, .98, 1.24, .83}, {.50, .91, 1.23, .80},
            {.95, .79, 1.16, .67}, {1.40, .60, 1.03, .47}, {1.75, .36, .91, .28},
            {1.97, .025, .86, .035}};
    /** 静态网格只构建一次，透明蒙皮与滑橇、旋翼分开绘制。 */
    private final List<Face> shell = new ArrayList<>(), glass = new ArrayList<>(), gear = new ArrayList<>();
    private final List<Face> rotor = new ArrayList<>(), tailRotor = new ArrayList<>();
    /** 内衬与座椅仅近距离绘制，灯罩单独自发光；漆面不再叠加影响光影深度的透明高光层。 */
    private final List<Face> interior = new ArrayList<>(), lamp = new ArrayList<>();
    /** 机腹云台的活动镜框、独立灯芯与机头/尾鳍红灯；静态网格跨实体复用。 */
    private final List<Face> searchlight=new ArrayList<>(),searchlightLens=new ArrayList<>(),navigationLights=new ArrayList<>();
    /** 支柱使用单位长度缓存，收放时仅更新四根支柱的变换，机身铰点保持固定。 */
    private final List<Face> strut = new ArrayList<>();
    /** 独立杆体以底座为原点，两侧共用几何并各自应用联动矩阵。 */
    private final List<Face> cyclic = new ArrayList<>();
    /** 四门的外壳、内衬、玻璃及涂装独立缓存，门号对应固定座号，所有面共用同一铰链。 */
    private final java.util.Map<String,List<Face>> doors = new java.util.HashMap<>();
    /** 舱灯位置只用于内饰光照衰减，单位：机体局部格数。 */
    private static final Vec3d CABIN_LAMP_POSITION = new Vec3d(0, 1.94, -.12);

    /** 曲面顶点保留连续法线；面色只描述材质，不在顶点中固定某种涂装。 */
    private record Vertex(Vec3d position, Vec3d normal, float u, float v) {
        /** 构造期展开 UV，避免每帧重复比较法线和计算纹理投影。 */
        private Vertex(Vec3d p, Vec3d n) {
            this(p, n, (float) ((Math.abs(n.x) > Math.abs(n.y) && Math.abs(n.x) > Math.abs(n.z) ? p.z : p.x) * .65),
                    (float) ((Math.abs(n.y) > Math.abs(n.x) && Math.abs(n.y) > Math.abs(n.z) ? p.z : p.y) * .65));
        }
    }
    /** 四边形按实体图层的 QUADS 格式提交，UV 与舱灯距离在构造期缓存。 */
    private record Face(Vertex a, Vertex b, Vertex c, Vertex d, int material, int blockLight) {
        /** 构造期缓存舱灯距离衰减，渲染时无需为每个面开方。 */
        private Face(Vertex a, Vertex b, Vertex c, Vertex d, int material) {
            this(a, b, c, d, material, LightmapTextureManager.pack(
                    (int) Math.clamp(14 - a.position.distanceTo(CABIN_LAMP_POSITION) * 3, 8, 14), 0));
        }
    }

    /**
     * 功能：建立机身及机舱；引用 MC API：net.minecraft.util.math.Vec3d 描述部件轴线。
     * 端侧/线程：客户端渲染线程；参数/返回：无；副作用：填充只读视觉网格。
     * 排查：机鼻形状查 BODY_PROFILE；部件错位查是否误用了旧模型的反向 Y 坐标。
     */
    public HelicopterModel() {
        this(false);
    }

    /**
     * 功能：按距离细节构造可复用网格；引用 MC API：无。
     * 端侧：客户端构造期；参数：distant 为远景标志；副作用：填充模型缓存；近景仍采用原始细分。
     */
    public HelicopterModel(boolean distant) {
        this.distant = distant;
        radialSegments = distant ? 24 : 48;
        longitudinalSegments = distant ? 28 : 56;
        buildCabin();
        if (!distant) buildPanelSeams();
        splitDoors(shell,"shell");splitDoors(interior,"interior");splitDoors(glass,"glass");
        buildDoorFrames();
        buildTail();
        buildNumber();
        splitDoors(number,"number");splitDoors(wordmark,"wordmark");
        buildInterior();
        buildGear();
        buildRotors();
        buildEngineFairing();
        tube(shell, new Vec3d(0, 2.18, -.17), new Vec3d(0, 2.71, -.17), .065, .065, METAL);
        // 连接座止于蒙皮内部，球罩整体在机腹下方，避免球罩上缘穿入机舱。
        Vec3d lightPivot = SearchlightRenderer.PIVOT;
        tube(shell, new Vec3d(0, profile(lightPivot.z, 2) - profile(lightPivot.z, 3) + .02, lightPivot.z),
                lightPivot.add(0, .17, 0), .065, .055, METAL);
        ellipsoid(shell, lightPivot, new Vec3d(.17, .19, .19), WHITE);
        ellipsoid(searchlight,new Vec3d(0,0,.177),new Vec3d(.115,.115,.03),DARK);
        ellipsoid(searchlightLens,new Vec3d(0,0,SearchlightRenderer.LENS_CENTER),
                new Vec3d(SearchlightRenderer.LENS_RADIUS,SearchlightRenderer.LENS_RADIUS,SearchlightRenderer.LENS_DEPTH),WHITE);
        for(double side:new double[]{-1,1})
            ellipsoid(navigationLights,new Vec3d(side*.47,2.42,-.35),new Vec3d(.04,.03,.045),WHITE);
        ellipsoid(navigationLights,new Vec3d(0,3.025,-5.55),new Vec3d(.03,.025,.04),WHITE);
        for(int door=0;door<HelicopterCabin.SEATS;door++){
            List<Face> camo=new ArrayList<>();
            for(Face face:doors.get("door"+door+"_shell"))if(paintedMaterial(face.material))
                camo.add(new Face(camouflageVertex(face.a),camouflageVertex(face.b),
                        camouflageVertex(face.c),camouflageVertex(face.d),WHITE));
            doors.put("door"+door+"_camouflage",camo);
        }
        for (Face face : shell) if (paintedMaterial(face.material)) {
            camouflage.add(new Face(camouflageVertex(face.a), camouflageVertex(face.b),
                    camouflageVertex(face.c), camouflageVertex(face.d), WHITE));
        }
    }

    /**
     * 功能：按前后门边界裁开真实蒙皮、窗框和贴花，固定中柱不随门移动；模型构造期调用。
     * 引用 net.minecraft.util.math.Vec3d 插值位置和法线，保留原 UV，关闭时仍拼成上一版机身。
     * 排查：开门残留面查四个裁切边界；必须在座椅/地板生成前分离内衬。
     */
    private void splitDoors(List<Face> source,String part){
        for(int door=0;door<HelicopterCabin.SEATS;door++){
            List<Face> moving=new ArrayList<>(),remaining=new ArrayList<>();
            for(Face face:source){
                List<Vertex> points=List.of(face.a,face.b,face.c,face.d);
                for(int boundary=0;boundary<4&&!points.isEmpty();boundary++){
                    polygon(remaining,clipDoor(points,door,boundary,false),face.material);
                    points=clipDoor(points,door,boundary,true);
                }
                polygon(moving,points,face.material);
            }
            source.clear();source.addAll(remaining);
            doors.put("door"+door+"_"+part,moving);
        }
    }

    /** 裁切门的前后缘及 45°/135° 上下缘；纯几何标量，正数表示门内侧。 */
    private static double doorDistance(Vertex vertex,int door,int boundary){
        Vec3d p=vertex.position;
        double distance=switch(boundary){
            case 0 -> p.z-HelicopterCabin.doorBack(door);
            case 1 -> HelicopterCabin.doorFront(door)-p.z;
            default -> (HelicopterCabin.side(door)==0?1:-1)*p.x/profile(p.z,1)
                    +(boundary==2?-1:1)*(p.y-profile(p.z,2))/profile(p.z,3);
        };
        return Math.abs(distance)<.0000001?0:distance;
    }

    /** 单边裁切保留整窗及字样 UV；net.minecraft.util.math.Vec3d#lerp 只在边界交点调用。 */
    private static List<Vertex> clipDoor(List<Vertex> points,int door,int boundary,boolean inside){
        List<Vertex> output=new ArrayList<>();
        Vertex previous=points.getLast();
        double previousDistance=doorDistance(previous,door,boundary);
        for(Vertex point:points){
            double distance=doorDistance(point,door,boundary);
            boolean keep=inside?distance>=0:distance<0;
            boolean previousKeep=inside?previousDistance>=0:previousDistance<0;
            if(keep!=previousKeep){
                double t=previousDistance/(previousDistance-distance);
                output.add(new Vertex(previous.position.lerp(point.position,t),
                        previous.normal.lerp(point.normal,t).normalize(),
                        (float)(previous.u+(point.u-previous.u)*t),(float)(previous.v+(point.v-previous.v)*t)));
            }
            if(keep)output.add(point);
            previous=point;previousDistance=distance;
        }
        for(int i=output.size()-1;i>=0&&output.size()>1;i--)
            if(output.get(i).position.squaredDistanceTo(output.get((i+1)%output.size()).position)<1.0e-16)
                output.remove(i);
        return output;
    }

    /** 门沿封住蒙皮厚度，齐平把手位于后缘；net.minecraft.util.math.Vec3d 描述真实曲面与铰链。 */
    private void buildDoorFrames(){
        for(int door=0;door<HelicopterCabin.SEATS;door++){
            double sign=HelicopterCabin.side(door)==0?1:-1;
            double back=HelicopterCabin.doorBack(door),front=HelicopterCabin.doorFront(door);
            List<Face> panel=doors.get("door"+door+"_shell");
            for(double z:new double[]{back,front})for(int i=0;i<12;i++)
                doorEdge(panel,bodyVertex(z,sign*(Math.PI/4+i*Math.PI/24)),
                        bodyVertex(z,sign*(Math.PI/4+(i+1)*Math.PI/24)));
            for(double angle:new double[]{Math.PI/4,3*Math.PI/4})for(int i=0;i<16;i++){
                double z=back+(front-back)*i/16,next=back+(front-back)*(i+1)/16;
                doorEdge(panel,bodyVertex(z,sign*angle),bodyVertex(next,sign*angle));
            }
            double handleZ=back+.16;
            Vec3d handle=numberVertex((int)sign,handleZ,1.13,back,front-back).position;
            Vec3d handleEnd=numberVertex((int)sign,handleZ+.14,1.13,back,front-back).position;
            tube(panel,handle,handleEnd,.012,.012,DARK);
            Vec3d hinge=HelicopterCabin.doorHinge(door);
            for(double y:new double[]{1.02,1.46})
                tube(panel,new Vec3d(hinge.x,y-.035,hinge.z),new Vec3d(hinge.x,y+.035,hinge.z),.014,.014,METAL);
        }
    }

    /** 封闭门的内外断面并加 6 毫米密封条，开门后边缘也具有厚度；仅模型构造期调用。 */
    private static void doorEdge(List<Face> panel,Vertex a,Vertex b){
        Vertex innerA=inset(a,SHELL_THICKNESS,true),innerB=inset(b,SHELL_THICKNESS,true);
        flat(panel,a.position,b.position,innerB.position,innerA.position,LINER);
        flat(panel,innerA.position,innerB.position,b.position,a.position,LINER);
        tube(panel,a.position,b.position,.006,.006,SEAL);
    }

    /**
     * 功能：用整圈裙边将顶部椭球整流罩接到真实舱顶，后方不再生成两根排气管。
     * 引用 MC API：net.minecraft.util.math.Vec3d 保存椭球环与舱顶交点；仅客户端构造期追加外壳。
     * 排查：悬空查裙边是否沿完整周向生成；舱内穿模查底缘仅嵌入外蒙皮 0.012 格。
     */
    private void buildEngineFairing() {
        Vec3d center = new Vec3d(0, 2.33, -.52), radius = new Vec3d(.49, .24, .91);
        ellipsoid(shell, center, radius, PAINT);
        // 与椭球已有的 -30° 纬线共用 24 个顶点，保持外观高度并封住罩底四周的空隙。
        for (int segment = 0; segment < 24; segment++) {
            Vertex a = ellipsoidVertex(center, radius, -Math.PI / 6, segment * Math.PI / 12);
            Vertex b = ellipsoidVertex(center, radius, -Math.PI / 6, (segment + 1) * Math.PI / 12);
            Vec3d na = new Vec3d(a.normal.x, 0, a.normal.z).normalize();
            Vec3d nb = new Vec3d(b.normal.x, 0, b.normal.z).normalize();
            shell.add(new Face(new Vertex(fairingFoot(a.position), na), new Vertex(a.position, na),
                    new Vertex(b.position, nb), new Vertex(fairingFoot(b.position), nb), PAINT));
        }
    }

    /** 功能：按机舱椭圆截面求裙边底缘；无世界访问，净距不越过 0.045 格厚的内衬。 */
    private static Vec3d fairingFoot(Vec3d point) {
        double ratio = point.x / profile(point.z, 1);
        double roof = profile(point.z, 2) + profile(point.z, 3) * Math.sqrt(1 - ratio * ratio);
        return new Vec3d(point.x, roof - .012, point.z);
    }

    /**
     * 功能：以插值截面形成连续开窗蒙皮；引用 MC API：无，纯几何计算。
     * 端侧/线程：客户端构造期；参数/返回：无；副作用：填充 shell/glass。
     * 排查：窗边锯齿查纵向采样是否包含门柱，玻璃后方不能再生成不透明机壳。
     */
    private void buildCabin() {
        // 窗和门柱边界明确参与采样，避免玻璃搭在一层完整实心机身之外。
        List<Double> stations = new ArrayList<>();
        for (int i = 0; i <= longitudinalSegments; i++)
            stations.add(-1.78 + 3.75 * i / longitudinalSegments);
        // 后舱壁也参与同一截面采样，使其边缘与舱顶内衬共用顶点，消除顶灯后方的局部相交。
        stations.addAll(List.of(-1.40, HelicopterCabin.DOOR_BACK, -1.03, -.92,
                HelicopterCabin.REAR_DOOR_FRONT, HelicopterCabin.FRONT_DOOR_BACK,
                .46, HelicopterCabin.DOOR_FRONT, .53, 1.47, 1.55));
        stations.sort(Double::compare);
        // 窗边可能恰好落在均匀截面上，先合并重合采样，避免零宽面导致漏光和光影暗斑。
        for (int i = stations.size() - 1; i > 0; i--)
            if (stations.get(i) - stations.get(i - 1) < .000001) stations.remove(i);
        for (int z = 0; z < stations.size() - 1; z++) {
            double front = stations.get(z + 1), back = stations.get(z);
            for (int a = 0; a < radialSegments; a++) {
                double angle = a * Math.PI * 2 / radialSegments;
                double next = (a + 1) * Math.PI * 2 / radialSegments;
                double midAngle = Math.abs(Math.atan2(Math.sin((angle + next) / 2), Math.cos((angle + next) / 2)));
                double midZ = (front + back) / 2;
                boolean window = isWindow(midZ, midAngle);
                Face face = new Face(bodyVertex(back, angle), bodyVertex(front, angle),
                        bodyVertex(front, next), bodyVertex(back, next), window ? GLASS : WHITE);
                if (window) {
                    face = windowUv(face, midZ);
                    glass.add(insetFace(face, GLASS_INSET, false, GLASS));
                    glass.add(insetFace(face, GLASS_INSET + GLASS_THICKNESS, true, GLASS));
                    if (z == 0 || !isWindow((stations.get(z - 1) + back) / 2, midAngle)) windowFrame(face.a, face.d);
                    if (z == stations.size() - 2 || !isWindow((front + stations.get(z + 2)) / 2, midAngle)) windowFrame(face.b, face.c);
                    if (!isWindow(midZ, angle - Math.PI / radialSegments)) windowFrame(face.a, face.b);
                    if (!isWindow(midZ, next + Math.PI / radialSegments)) windowFrame(face.d, face.c);
                } else {
                    paintedSurface(face);
                    interior.add(insetFace(face, SHELL_THICKNESS, true, LINER));
                }
            }
        }
        bodyCap(1.97, true, false, PAINT);
        bodyCap(1.97, false, true, LINER);
        // 尾梁与机舱在 -1.78 截面共用整圈顶点，不再用有缝的独立圆柱相插。
        bodyCap(-1.78, true, true, LINER);
        // 尾梁的所有根部结构位于隔舱后方，机内后视只能看到封闭的后舱壁。
        bodyCap(-1.40, true, true, LINER);
    }

    /**
     * 功能：共用窗孔判定以找到真正的窗框边界；引用 MC API：无。
     * 端侧：构造期；参数：z/angle 为格数和弧度；返回：是否为玻璃；副作用：无。
     * 排查：首尾圆周接缝需先把角度归一化，不能把最后一列误作新窗边。
     */
    private static boolean isWindow(double z, double angle) {
        double a = Math.abs(Math.atan2(Math.sin(angle), Math.cos(angle)));
        return z > -1.03 && z < .46 && a > .89 && a < 1.87 && !(z > -.38 && z < -.31)
                || z > .53 && z < 1.47 && a > .10 && a < 1.57;
    }

    /**
     * 功能：为每块完整机窗展开连续 UV；引用 MC API：net.minecraft.util.math.Vec3d 保留位置与法线。
     * 端侧：客户端模型构造期；参数：face 为窗面，z 为纵向中点；返回：带整窗 UV 的面；无世界访问。
     * 排查：窗缘不能在每个细分面重复一圈，角度边界须与圆周网格实际覆盖范围一致。
     */
    private Face windowUv(Face face, double z) {
        double back = z < -.38 ? -1.03 : z < .46 ? -.31 : .53;
        double front = z < -.38 ? -.38 : z < .46 ? .46 : 1.47;
        double step = Math.PI * 2 / radialSegments;
        double bottom = Math.ceil((z < .46 ? .89 : .10) / step - .5) * step;
        double top = (Math.floor((z < .46 ? 1.87 : 1.57) / step - .5) + 1) * step;
        return new Face(windowUv(face.a, back, front, bottom, top), windowUv(face.b, back, front, bottom, top),
                windowUv(face.c, back, front, bottom, top), windowUv(face.d, back, front, bottom, top), GLASS);
    }

    /** 功能：按截面参数映射整窗坐标；端侧：构造期；参数为顶点与窗边界；返回：新顶点；不改变几何。 */
    private static Vertex windowUv(Vertex vertex, double back, double front, double bottom, double top) {
        Vec3d point = vertex.position;
        double angle = Math.abs(Math.atan2(point.x / profile(point.z, 1),
                (point.y - profile(point.z, 2)) / profile(point.z, 3)));
        return new Vertex(point, vertex.normal, (float) Math.clamp((point.z - back) / (front - back), 0, 1),
                (float) Math.clamp((angle - bottom) / (top - bottom), 0, 1));
    }

    /**
     * 功能：向曲面内侧偏移并按需要反转法线；引用 MC API：net.minecraft.util.math.Vec3d 沿法线移动。
     * 端侧：构造期；参数：v/depth/reverse 为顶点、格数厚度和内表面标志；返回：偏移顶点；副作用：无。
     * 排查：鼻尖内衬相交查端部厚度钳制；内侧反转法线但保留表面 UV，避免整窗材质重新平铺。
     */
    private static Vertex inset(Vertex v, double depth, boolean reverse) {
        double thickness = Math.min(depth, Math.min(profile(v.position.z, 1), profile(v.position.z, 3)) * .4);
        return new Vertex(v.position.subtract(v.normal.multiply(thickness)), reverse ? v.normal.negate() : v.normal,
                v.u, v.v);
    }

    /**
     * 功能：构建有独立内侧绕序的蒙皮面；引用 MC API：无，复用缓存几何。
     * 端侧：构造期；参数：face/depth/reverse/material 为外面、厚度、绕序及材质；返回：内面；副作用：无。
     * 排查：背面剔除后漏面查反转时是否同时交换四顶点。
     */
    private static Face insetFace(Face face, double depth, boolean reverse, int material) {
        Vertex a = inset(face.a, depth, reverse), b = inset(face.b, depth, reverse);
        Vertex c = inset(face.c, depth, reverse), d = inset(face.d, depth, reverse);
        return reverse ? new Face(d, c, b, a, material) : new Face(a, b, c, d, material);
    }

    /**
     * 功能：封闭窗孔断面并添加细橡胶密封圈；引用 MC API：net.minecraft.util.math.Vec3d 描述断面厚度。
     * 端侧：构造期；参数：a/b 为窗边端点；返回：无；副作用：追加有厚度窗框。
     * 排查：从斜角透视机壳查是否漏掉纵向或周向边界。
     */
    private void windowFrame(Vertex a, Vertex b) {
        Vertex innerA = inset(a, SHELL_THICKNESS, true), innerB = inset(b, SHELL_THICKNESS, true);
        flat(shell, a.position, b.position, innerB.position, innerA.position, SEAL);
        // 断面既有内壁又有外壁，法线不能靠双面渲染伪装出厚度。
        flat(shell, innerA.position, innerB.position, b.position, a.position, SEAL);
        tube(shell, a.position, b.position, .007, .007, SEAL);
    }

    /**
     * 功能：封闭机鼻、机尾端面与隔舱；引用 MC API：net.minecraft.util.math.Vec3d 定义端面外法线。
     * 端侧：构造期；参数：z/forward/inside/material 为截面、朝向、内衬和材质；返回：无；副作用：追加封口。
     * 排查：机鼻小孔查端面是否存在；尾梁穿舱查后舱壁是否位于尾梁根部前方。
     */
    private void bodyCap(double z, boolean forward, boolean inside, int material) {
        List<Vertex> ring = new ArrayList<>();
        Vec3d normal = new Vec3d(0, 0, forward ? 1 : -1);
        for (int i = 0; i < radialSegments; i++) {
            Vertex outer = bodyVertex(z, (forward ? -i : i) * Math.PI * 2 / radialSegments);
            Vec3d point = inside ? inset(outer, SHELL_THICKNESS, false).position : outer.position;
            ring.add(new Vertex(point, normal));
        }
        polygon(inside ? interior : shell, ring, material);
    }

    /**
     * 功能：给喷漆蒙皮增加毫米级板缝和齐平铆钉；引用 MC API：无，全部采样同一机身曲面。
     * 端侧：构造期；参数/返回：无；副作用：追加局部细节；排查：板缝不得跨过玻璃，也不能拉成悬空直线。
     */
    private void buildPanelSeams() {
        for (double z : new double[]{-1.28, -.35, .50}) for (int a = 0; a < radialSegments; a++) {
            double angle = a * Math.PI * 2 / radialSegments, next = (a + 1) * Math.PI * 2 / radialSegments;
            if (isWindow(z, (angle + next) / 2)) continue;
            Face seam = new Face(bodyVertex(z - .0015, angle), bodyVertex(z + .0015, angle),
                    bodyVertex(z + .0015, next), bodyVertex(z - .0015, next), SEAM);
            // 负偏移只在这里表示向外抬高一毫米，保持与蒙皮曲率和法线完全一致。
            shell.add(insetFace(seam, -.001, false, SEAM));
            if (a % 6 == 0) {
                Face rivet = new Face(bodyVertex(z + .012, angle - .004), bodyVertex(z + .018, angle - .004),
                        bodyVertex(z + .018, angle + .004), bodyVertex(z + .012, angle + .004), METAL);
                shell.add(insetFace(rivet, -.0015, false, METAL));
            }
        }
    }

    /**
     * 功能：按精确高度平面分开蒙皮色带；引用 MC API：无，使用缓存的曲面顶点。
     * 端侧/线程：构造期；参数：face 为未着色曲面格；返回：无；副作用：追加互不覆盖的色带面。
     * 排查：机鼻条纹锯齿查裁切边界，不能仅按每个网格中心选色。
     */
    private void paintedSurface(Face face) {
        List<Vertex> points = List.of(face.a, face.b, face.c, face.d);
        polygon(shell, clipHeight(points, BELLY_TOP, false), BELLY);
        polygon(shell, clipHeight(clipHeight(points, BELLY_TOP, true), BELT_BOTTOM, false), WHITE);
        polygon(shell, clipHeight(clipHeight(points, BELT_BOTTOM, true), BELT_TOP, false), BELT);
        polygon(shell, clipHeight(clipHeight(points, BELT_TOP, true), TRIM_TOP, false), STRIPE);
        polygon(shell, clipHeight(points, TRIM_TOP, true), WHITE);
    }

    /**
     * 功能：保留高度半空间内的凸多边形；引用 MC API：net.minecraft.util.math.Vec3d#lerp 插值位置及连续法线。
     * 端侧/线程：构造期；参数：points/bound/above 为顶点、格数边界及保留方向；返回：裁切顶点；副作用：无。
     * 排查：色带接缝发黑查交点法线，必须与位置使用相同插值比例。
     */
    private static List<Vertex> clipHeight(List<Vertex> points, double bound, boolean above) {
        if (points.isEmpty()) return points;
        List<Vertex> output = new ArrayList<>();
        Vertex previous = points.getLast();
        for (Vertex point : points) {
            boolean inside = above ? point.position.y >= bound : point.position.y <= bound;
            boolean previousInside = above ? previous.position.y >= bound : previous.position.y <= bound;
            if (inside != previousInside) {
                double t = (bound - previous.position.y) / (point.position.y - previous.position.y);
                output.add(new Vertex(previous.position.lerp(point.position, t), previous.normal.lerp(point.normal, t).normalize()));
            }
            if (inside) output.add(point);
            previous = point;
        }
        return output;
    }

    /**
     * 功能：缓存裁切后的非退化四边形分区；引用 MC API：net.minecraft.util.math.Vec3d 保存中心与边中点。
     * 端侧/线程：构造期；参数：target/points/material 为目标、凸轮廓与材质；返回：无；副作用：追加网格。
     * 排查：三角形不能用重复顶点伪装四边形，光影需要非零 UV 面积。
     */
    private static void polygon(List<Face> target, List<Vertex> points, int material) {
        if (points.size() < 3) return;
        // 门沿和色带恰好经过顶点时，裁切可只剩共线轮廓；不能提交零面积面参与光影。
        Vec3d area=Vec3d.ZERO,origin=points.getFirst().position;
        for(int i=1;i+1<points.size();i++)
            area=area.add(points.get(i).position.subtract(origin).crossProduct(points.get(i+1).position.subtract(origin)));
        if(area.lengthSquared()<1.0e-20)return;
        if (points.size() == 4) {
            target.add(new Face(points.get(0), points.get(1), points.get(2), points.get(3), material)); return;
        }
        // 偶数边凸多边形可直接以扇形四边形铺满，不必为每条边生成一个中心分区。
        if (points.size() > 4) {
            int i = 1;
            for (; i + 2 < points.size(); i += 2)
                target.add(new Face(points.getFirst(), points.get(i), points.get(i + 1), points.get(i + 2), material));
            if (i + 1 < points.size()) polygon(target,
                    List.of(points.getFirst(), points.get(i), points.get(i + 1)), material);
            return;
        }
        Vec3d centerPoint = Vec3d.ZERO, centerNormal = Vec3d.ZERO;
        float centerU=0,centerV=0;
        for (Vertex point : points) {
            centerPoint = centerPoint.add(point.position); centerNormal = centerNormal.add(point.normal);
            centerU+=point.u;centerV+=point.v;
        }
        Vertex center = new Vertex(centerPoint.multiply(1.0 / points.size()), centerNormal.normalize(),
                centerU/points.size(),centerV/points.size());
        for (int i = 0; i < points.size(); i++) {
            Vertex point = points.get(i), before = points.get((i + points.size() - 1) % points.size());
            Vertex after = points.get((i + 1) % points.size());
            target.add(new Face(point, midpoint(point, after), center, midpoint(before, point), material));
        }
    }

    /**
     * 功能：插值平滑边中点；引用 MC API：net.minecraft.util.math.Vec3d#lerp 保留连续位置和法线。
     * 端侧/线程：构造期；参数：a/b 为边端点；返回：中点；副作用：无；排查：法线需重新单位化。
     */
    private static Vertex midpoint(Vertex a, Vertex b) {
        return new Vertex(a.position.lerp(b.position, .5), a.normal.lerp(b.normal, .5).normalize(),
                (a.u+b.u)*.5f,(a.v+b.v)*.5f);
    }

    /**
     * 功能：插值截面参数，半宽和半高使用平滑三次插值；引用 MC API：无。
     * 端侧/线程：构造期；参数：z 为纵向位置，column 为截面参数列；返回：对应形状值；副作用：无。
     * 排查：截面鼓包查插值切线，而非提高面数掩盖轮廓折线。
     */
    private static double profile(double z, int column) {
        int index = 0;
        while (index < BODY_PROFILE.length - 2 && z > BODY_PROFILE[index + 1][0]) index++;
        double[] a = BODY_PROFILE[index], b = BODY_PROFILE[index + 1];
        double t = Math.clamp((z - a[0]) / (b[0] - a[0]), 0, 1);
        double[] before = BODY_PROFILE[Math.max(0, index - 1)];
        double[] after = BODY_PROFILE[Math.min(BODY_PROFILE.length - 1, index + 2)];
        double tangentA = (b[column] - before[column]) / (b[0] - before[0]) * (b[0] - a[0]);
        double tangentB = (after[column] - a[column]) / (after[0] - a[0]) * (b[0] - a[0]);
        return (2 * t * t * t - 3 * t * t + 1) * a[column] + (t * t * t - 2 * t * t + t) * tangentA
                + (-2 * t * t * t + 3 * t * t) * b[column] + (t * t * t - t * t) * tangentB;
    }

    /**
     * 功能：计算椭圆截面上的机身点；引用 MC API：net.minecraft.util.math.Vec3d 保存局部坐标。
     * 端侧/线程：构造期；参数：z/angle 为纵向格数/圆周弧度；返回：曲面点；副作用：无。
     * 排查：机头方向反转查 +Z 约定。
     */
    private static Vec3d bodyPoint(double z, double angle) {
        return new Vec3d(profile(z, 1) * Math.sin(angle),
                profile(z, 2) + profile(z, 3) * Math.cos(angle), z);
    }

    /**
     * 功能：通过曲面偏导求连续法线；引用 MC API：net.minecraft.util.math.Vec3d#crossProduct 计算外法线。
     * 端侧/线程：构造期；参数：z/angle 为曲面参数；返回：带法线顶点；副作用：无。
     * 排查：镜面棱线查是否误改为每面共用一个法线；差分步长为千分之一格/弧度。
     */
    private static Vertex bodyVertex(double z, double angle) {
        Vec3d around = bodyPoint(z, angle + .001).subtract(bodyPoint(z, angle - .001));
        Vec3d along = bodyPoint(z + .001, angle).subtract(bodyPoint(z - .001, angle));
        return new Vertex(bodyPoint(z, angle), surfaceNormal(along, around));
    }

    /**
     * 功能：构建细长尾梁和流线尾翼；引用 MC API：net.minecraft.util.math.Vec3d 描述轴线。
     * 端侧/线程：构造期；参数/返回：无；副作用：填充 shell。
     * 排查：尾梁根部必须在后隔舱之后；薄尾翼需封闭上下端面，水平尾翼后缘需避开完整尾桨扫掠范围。
     */
    private void buildTail() {
        int stations = distant ? 8 : 16;
        for (int z = 0; z < stations; z++) for (int a = 0; a < radialSegments; a++) {
            double back = -5.06 + 3.28 * z / stations, front = -5.06 + 3.28 * (z + 1) / stations;
            double angle = a * Math.PI * 2 / radialSegments, next = (a + 1) * Math.PI * 2 / radialSegments;
            shell.add(new Face(bodyVertex(back, angle), bodyVertex(front, angle),
                    bodyVertex(front, next), bodyVertex(back, next), PAINT));
        }
        bodyCap(-5.06, false, false, PAINT);
        // 薄翼型沿高度后掠，顶部收尖；不用纵向拉长的球体代替尾鳍。
        for (int ring = 0; ring < 16; ring++) for (int a = 0; a < 24; a++) {
            double t = ring / 16.0, nextT = (ring + 1) / 16.0;
            double angle = a * Math.PI / 12, next = (a + 1) * Math.PI / 12;
            shell.add(new Face(finVertex(t, angle), finVertex(t, next),
                    finVertex(nextT, next), finVertex(nextT, angle), PAINT));
        }
        for (int end = 0; end < 2; end++) {
            List<Vertex> edge = new ArrayList<>();
            for (int i = 0; i < 24; i++) edge.add(new Vertex(finPoint(end,
                    (end == 0 ? -i : i) * Math.PI / 12), new Vec3d(0, end == 0 ? -1 : 1, 0)));
            polygon(shell, edge, PAINT);
        }
        // 水平尾翼前移 .20 格：后缘 z=-4.15，与尾桨最前端约 -4.289 留出 .139 格净距；同步分部碰撞盒。
        ellipsoid(shell, new Vec3d(0, 1.80, -3.86), new Vec3d(1.08, .045, .29), PAINT);
        tube(shell, new Vec3d(0, 1.84, -4.94), new Vec3d(.23, 1.84, -4.94), .055, .055, METAL);
        tube(shell, new Vec3d(0, 1.86, -5.18), new Vec3d(0, 1.41, -5.35), .017, .017, METAL);
    }

    /**
     * 功能：计算后掠薄尾鳍轮廓；引用 MC API：net.minecraft.util.math.Vec3d 保存局部曲面点。
     * 端侧/线程：构造期；参数：t 为翼根到翼尖比例，angle 为截面弧度；返回：格数坐标；副作用：无。
     * 排查：上端过宽查弦长 .24+.20*sin(πt)-.17t，后掠量为 .55 格。
     */
    private static Vec3d finPoint(double t, double angle) {
        double thickness = .012 + .035 * Math.sin(Math.PI * t);
        double chord = .24 + .20 * Math.sin(Math.PI * t) - .17 * t;
        return new Vec3d(thickness * Math.sin(angle), 1.50 + 1.50 * t,
                -5.0 - .55 * t + chord * Math.cos(angle));
    }

    /**
     * 功能：求薄尾鳍连续法线；引用 MC API：net.minecraft.util.math.Vec3d#crossProduct 组合曲面切线。
     * 端侧/线程：构造期；参数：t/angle 为尾鳍曲面参数；返回：顶点与外法线；副作用：无。
     * 排查：翼缘阴影断裂查 .001 差分，不能对相邻面逐面计算常量法线。
     */
    private static Vertex finVertex(double t, double angle) {
        Vec3d around = finPoint(t, angle + .001).subtract(finPoint(t, angle - .001));
        Vec3d along = finPoint(t + .001, angle).subtract(finPoint(t - .001, angle));
        return new Vertex(finPoint(t, angle), surfaceNormal(around, along));
    }

    /**
     * 功能：生成座椅、舱底、仪表罩与操纵杆；引用 MC API：net.minecraft.util.math.Vec3d 保存形状。
     * 端侧/线程：构造期；参数/返回：无；副作用：填充 interior/lamp；不生成遮挡风挡的实心内壳。
     * 排查：地板穿出机腹查 buildFloor 与内壳净距；第一人称黑屏查仪表罩高度与眼位是否相交。
     */
    private void buildInterior() {
        buildFloor();
        for (int row=0;row<2;row++) for (double side : new double[]{-.38, .38}) {
            double forward = HelicopterEntity.SEAT_FORWARD_OFFSET-row*HelicopterCabin.ROW_SPACING;
            ellipsoid(interior, new Vec3d(side, .69, -.12 + forward), new Vec3d(.29, .10, .36), SEAT);
            ellipsoid(interior, new Vec3d(side, 1.01, -.40 + forward), new Vec3d(.29, .43, .09), SEAT);
            ellipsoid(interior, new Vec3d(side, 1.38, -.41 + forward), new Vec3d(.18, .13, .09), SEAT);
            if(row==0)ellipsoid(interior, new Vec3d(side, CockpitControlsRenderer.BASE_Y, CockpitControlsRenderer.BASE_Z),
                    new Vec3d(.065, .045, .065), DARK);
        }
        tube(cyclic, Vec3d.ZERO, CockpitControlsRenderer.GRIP.add(0, -.03, 0), .023, .023, DARK);
        ellipsoid(cyclic, CockpitControlsRenderer.GRIP, new Vec3d(.04, .07, .04), DARK);
        // 灯具紧贴舱顶内侧；透镜独立自发光，内衬使用局部灯光，不依赖额外动态光照模组。
        ellipsoid(interior, new Vec3d(0, 1.965, -.12), new Vec3d(.14, .025, .095), METAL);
        ellipsoid(lamp, new Vec3d(0, 1.943, -.12), new Vec3d(.105, .012, .063), WHITE);
        // 仪表板正面由 CockpitDashboardRenderer 封闭，罩壳位于正面的机头一侧。
        ellipsoid(interior, new Vec3d(0, 1.04, 1.03), new Vec3d(.70, .31, .19), DARK);
        // 仪表细节按观察距离筛选；始终保留稍靠后的静态面板，避免舱外看到罩壳开口。
        buildDashboardBacking();
    }

    /**
     * 功能：让静态仪表背板与正面共用圆角轮廓，防止左上、右上露出矩形直角。
     * 引用 net.minecraft.util.math.Vec3d 描述机体局部坐标；仅客户端构造期生成，不增加逐帧工作。
     * 排查：尺寸与 CockpitDashboardRenderer 的 0.8 倍主面板一致，背板不能伸出正面边界。
     */
    private void buildDashboardBacking(){
        double radius=.055*.8;
        List<Vertex> outline=new ArrayList<>();
        for(int corner=0;corner<4;corner++)for(int step=0;step<=8;step++){
            double centerX=corner==0||corner==3?.68-radius:-.68+radius;
            double centerY=corner<2?1.38-radius:.764+radius;
            double angle=(corner*8+step)*Math.PI/16;
            outline.add(new Vertex(new Vec3d(centerX+radius*Math.cos(angle),
                    centerY+radius*Math.sin(angle),.822),new Vec3d(0,0,-1)));
        }
        polygon(interior,outline.reversed(),DARK);
    }

    /**
     * 功能：按机腹截面构建有厚度的平地板；引用 MC API：net.minecraft.util.math.Vec3d 描述闭合面。
     * 端侧：客户端构造期；参数/返回：无；副作用：追加 interior；不改变座位、乘员或碰撞箱。
     * 排查：顶面高度固定，底面与侧边都应留在内蒙皮内，不能用固定宽度椭球覆盖收窄的机鼻与机腹。
     */
    private void buildFloor() {
        Vec3d down = new Vec3d(0, -FLOOR_THICKNESS, 0);
        for (int segment = 0; segment < FLOOR_SEGMENTS; segment++) {
            double back = FLOOR_BACK + (FLOOR_FRONT - FLOOR_BACK) * segment / FLOOR_SEGMENTS;
            double front = FLOOR_BACK + (FLOOR_FRONT - FLOOR_BACK) * (segment + 1) / FLOOR_SEGMENTS;
            double backWidth = floorHalfWidth(back), frontWidth = floorHalfWidth(front);
            Vec3d a = new Vec3d(-backWidth, FLOOR_TOP, back), b = new Vec3d(-frontWidth, FLOOR_TOP, front);
            Vec3d c = new Vec3d(frontWidth, FLOOR_TOP, front), d = new Vec3d(backWidth, FLOOR_TOP, back);
            flat(interior, a, b, c, d, DARK);
            flat(interior, a.add(down), d.add(down), c.add(down), b.add(down), DARK);
            flat(interior, a, a.add(down), b.add(down), b, DARK);
            flat(interior, d, c, c.add(down), d.add(down), DARK);
            if (segment == 0) flat(interior, a, d, d.add(down), a.add(down), DARK);
            if (segment == FLOOR_SEGMENTS - 1) flat(interior, b, b.add(down), c.add(down), c, DARK);
        }
    }

    /**
     * 功能：按地板底面在内蒙皮截面中的位置求安全半宽；引用 MC API：无，纯几何计算。
     * 端侧：客户端构造期；参数：z 为纵向格数；返回：半宽格数；无副作用。
     * 排查：厚度与净距同时从截面扣除，底面控制宽度，防止上表面在舱内而底角穿到舱外。
     */
    private static double floorHalfWidth(double z) {
        double inset = SHELL_THICKNESS + FLOOR_CLEARANCE;
        double height = profile(z, 3) - inset, width = profile(z, 1) - inset;
        double bottom = (FLOOR_TOP - FLOOR_THICKNESS - profile(z, 2)) / height;
        return width * Math.sqrt(Math.max(0, 1 - bottom * bottom));
    }

    /**
     * 功能：缓存左 24、右 Laker 的贴合曲面；引用 MC API：net.minecraft.util.math.Vec3d 表示蒙皮采样点。
     * 端侧：构造期；参数/返回：无；副作用：追加两个材质区域；排查：+X 为左侧，文字上缘低于窗孔。
     */
    private void buildNumber() {
        for (int side : new int[]{1, -1}) {
            double start = side > 0 ? -.95 : -.92, length = side > 0 ? 1.15 : 1.90;
            int columns = side > 0 ? 24 : 48;
            List<Face> target = side > 0 ? number : wordmark;
            for (int z = 0; z < columns; z++) for (int y = 0; y < 6; y++) {
                double back = start + length * z / columns, front = start + length * (z + 1) / columns;
                double bottom = .62 + .365 * y / 6, top = .62 + .365 * (y + 1) / 6;
                Vertex a = numberVertex(side, back, bottom, start, length), b = numberVertex(side, front, bottom, start, length);
                Vertex c = numberVertex(side, front, top, start, length), d = numberVertex(side, back, top, start, length);
                target.add(side > 0 ? new Face(a, d, c, b, WHITE) : new Face(a, b, c, d, WHITE));
            }
        }
    }

    /**
     * 功能：投影编号到真实机身曲面；引用 MC API：net.minecraft.util.math.Vec3d 沿外法线留 2 毫米贴花间距。
     * 端侧：构造期；参数：side/z/y/start/length 为两侧符号、位置及文字宽度；返回：含整字 UV 的顶点；无副作用。
     * 排查：编号穿壳或浮起查是否误用了固定 X 平面。
     */
    private static Vertex numberVertex(int side, double z, double y, double start, double length) {
        double angle = side * Math.acos(Math.clamp((y - profile(z, 2)) / profile(z, 3), -1, 1));
        Vertex point = bodyVertex(z, angle);
        double u = (z - start) / length;
        return new Vertex(point.position.add(point.normal.multiply(.002)), point.normal,
                (float) (side > 0 ? 1 - u : u), (float) (1 - (y - .62) / .365));
    }

    /**
     * 功能：缓存连续迷彩投影；引用 MC API：net.minecraft.util.math.Vec3d 提供局部位置。
     * 端侧：构造期；参数：v 为外壳顶点；返回：保持位置与法线的纹理顶点；无副作用。
     * 排查：UV 不能按每个面的主法线切换，否则斑块会在机腹和尾梁断开；使用可平铺材质。
     */
    private static Vertex camouflageVertex(Vertex v) {
        return new Vertex(v.position, v.normal, (float) (v.position.z * CAMOUFLAGE_SCALE),
                (float) ((v.position.y + Math.abs(v.position.x) * 1.6) * CAMOUFLAGE_SCALE));
    }

    /** 功能：判定外漆材质槽；端侧：客户端；参数为材质编号；返回：是否喷漆面；无 MC API 与副作用。 */
    private static boolean paintedMaterial(int material) {
        return material == WHITE || material == PAINT || material == BELT || material == BELLY || material == STRIPE;
    }

    /**
     * 功能：按涂装名称为外漆和起落架选色；端侧：渲染线程；参数：paint/material/gear 为编号、材质与起落架标记。
     * 返回：ARGB；无 MC API 与副作用；排查：默认警用色不调用此方法，紫金字样下方保持紫色。
     */
    private static int liveryColor(int paint, int material, boolean gear) {
        if (gear) return GEAR_COLORS[paint];
        if (paint == 4) return switch (material) {
            case BELT -> RESCUE_WHITE; case BELLY, STRIPE -> RESCUE_DARK; default -> LIVERY_COLORS[paint];
        };
        if (paint == 5 && (material == BELLY || material == STRIPE)) return GOLD;
        return LIVERY_COLORS[paint];
    }

    /** 功能：提交已缓存 UV 的蒙皮顶点；引用 net.minecraft.client.render.VertexConsumer 写入材质；仅渲染线程，无几何修改。 */
    private static void surfaceVertex(MatrixStack m, VertexConsumer b, Vertex v, int color, int light) {
        vertex(m, b, v.position, v.normal, color, light, v.u, v.v);
    }

    /**
     * 功能：构建上翘弧形滑橇与斜支柱；引用 MC API：net.minecraft.util.math.Vec3d 定义折线节点。
     * 端侧/线程：构造期；参数/返回：无；副作用：填充连续 gear 与单位支柱；收放时铰点固定、滑橇沿舱外后移上升。
     * 排查：地面悬浮查最低滑橇中心 .12 格与半径 .045 格。
     */
    private void buildGear() {
        final int along = 24, around = 12;
        for (double side : new double[]{-1, 1}) {
            for (int z = 0; z < along; z++) for (int a = 0; a < around; a++) {
                double t = z / (double) along, next = (z + 1.0) / along;
                double angle = a * Math.PI * 2 / around, nextAngle = (a + 1) * Math.PI * 2 / around;
                gear.add(new Face(skidVertex(side, t, angle), skidVertex(side, next, angle),
                        skidVertex(side, next, nextAngle), skidVertex(side, t, nextAngle), WHITE));
            }
            for (int end = 0; end < 2; end++) {
                List<Vertex> ring = new ArrayList<>();
                for (int a = 0; a < around; a++) {
                    Vertex v = skidVertex(side, end, (end == 0 ? a : -a) * Math.PI * 2 / around);
                    ring.add(new Vertex(v.position, new Vec3d(0, 0, end == 0 ? -1 : 1)));
                }
                polygon(gear, ring, WHITE);
            }
        }
        tube(strut, Vec3d.ZERO, new Vec3d(0, 1, 0), .035, .035, WHITE);
    }

    /**
     * 功能：采样连续上翘滑橇，内部截面不生成端盖；引用 MC API：net.minecraft.util.math.Vec3d 保存曲面点。
     * 端侧：构造期；参数：side/t/angle 为侧向符号、纵向比例及弧度；返回：位置与平滑法线；副作用：无。
     * 排查：前翘过高查 .31 格弧高；断节查是否又用多个封闭小圆柱拼接。
     */
    private static Vertex skidVertex(double side, double t, double angle) {
        double z = -1.47 + 3.36 * t, rise = Math.max(0, (z - 1.23) / .66);
        Vec3d n = new Vec3d(Math.sin(angle), Math.cos(angle), -Math.cos(angle) * .62 * rise / .66).normalize();
        return new Vertex(new Vec3d(side * 1.05 + .045 * Math.sin(angle),
                .12 + .31 * rise * rise + .045 * Math.cos(angle), z), n);
    }

    /**
     * 功能：构建四叶主旋翼和四叶尾桨；引用 MC API：net.minecraft.util.math.Vec3d 描述正交桨叶轴。
     * 端侧：构造期；参数/返回：无；副作用：填充闭合桨叶网格。
     * 排查：尾桨必须在 XY 平面生成，主桨在 XZ 平面，旋转轴由实体渲染器定位。
     */
    private void buildRotors() {
        ellipsoid(rotor, Vec3d.ZERO, new Vec3d(.20, .075, .20), METAL);
        ellipsoid(tailRotor, Vec3d.ZERO, new Vec3d(.075, .075, .06), METAL);
        for (int blade = 0; blade < 4; blade++) {
            double angle = blade * Math.PI / 2;
            buildBlade(rotor, new Vec3d(Math.cos(angle), 0, Math.sin(angle)),
                    new Vec3d(-Math.sin(angle), 0, Math.cos(angle)), 3.70, .17, .022);
            buildBlade(tailRotor, new Vec3d(Math.cos(angle), Math.sin(angle), 0),
                    new Vec3d(-Math.sin(angle), Math.cos(angle), 0), .65, .095, .014);
        }
    }

    /**
     * 功能：生成有上下翼面、前后缘及端盖的薄桨叶；引用 MC API：net.minecraft.util.math.Vec3d 建立局部翼型基。
     * 端侧：构造期；参数：target/radial/across/length/chord/thickness 为网格、翼轴和格数尺寸；返回：无。
     * 副作用：追加闭合部件；排查：低角度消失查边缘和桨尖封盖，连续下反角不能在每段重置。
     */
    private static void buildBlade(List<Face> target, Vec3d radial, Vec3d across,
            double length, double chord, double thickness) {
        Vec3d normal = across.crossProduct(radial);
        for (int segment = 0; segment < 20; segment++) {
            double t0 = segment / 20.0, t1 = (segment + 1) / 20.0;
            double start = .10 + t0 * (length - .10), end = .10 + t1 * (length - .10);
            double width0 = chord * (1 - .42 * t0), width1 = chord * (1 - .42 * t1);
            Vec3d a = radial.multiply(start).subtract(across.multiply(width0 / 2)).add(normal.multiply(-.025 * t0 * t0));
            Vec3d b = radial.multiply(end).subtract(across.multiply(width1 / 2)).add(normal.multiply(-.025 * t1 * t1));
            Vec3d c = b.add(across.multiply(width1)), d = a.add(across.multiply(width0));
            Vec3d down = normal.multiply(-thickness);
            int material = segment >= 18 ? STRIPE : DARK;
            flat(target, a, d, c, b, material);
            flat(target, a.add(down), b.add(down), c.add(down), d.add(down), material);
            flat(target, a, b, b.add(down), a.add(down), material);
            flat(target, d, d.add(down), c.add(down), c, material);
            if (segment == 0) flat(target, d, a, a.add(down), d.add(down), DARK);
            if (segment == 19) flat(target, b, c, c.add(down), b.add(down), STRIPE);
        }
    }

    /**
     * 功能：缓存带解析平滑法线的椭球；引用 MC API：net.minecraft.util.math.Vec3d 描述中心/半轴。
     * 端侧/线程：构造期；参数：target 为目标网格，center/radius 为格数，material 为材质槽；返回：无。
     * 副作用：追加曲面；排查：球面棱线查法线是否除以各半轴。
     */
    private static void ellipsoid(List<Face> target, Vec3d center, Vec3d radius, int material) {
        final int rings = Math.max(radius.x, Math.max(radius.y, radius.z)) < .21 ? 6 : 12, segments = rings * 2;
        for (int y = 0; y < rings; y++) for (int a = 0; a < segments; a++) {
            double u = a * Math.PI * 2 / segments, v = (a + 1) * Math.PI * 2 / segments;
            double p = -.5 * Math.PI + y * Math.PI / rings, q = -.5 * Math.PI + (y + 1) * Math.PI / rings;
            if (y == 0) polygon(target, List.of(new Vertex(center.add(0, -radius.y, 0), new Vec3d(0, -1, 0)),
                    ellipsoidVertex(center, radius, q, u), ellipsoidVertex(center, radius, q, v)), material);
            else if (y == rings - 1) polygon(target, List.of(ellipsoidVertex(center, radius, p, u),
                    new Vertex(center.add(0, radius.y, 0), new Vec3d(0, 1, 0)), ellipsoidVertex(center, radius, p, v)), material);
            else target.add(new Face(ellipsoidVertex(center, radius, p, u), ellipsoidVertex(center, radius, q, u),
                    ellipsoidVertex(center, radius, q, v), ellipsoidVertex(center, radius, p, v), material));
        }
    }

    /**
     * 功能：求椭球位置与梯度法线；引用 MC API：net.minecraft.util.math.Vec3d#normalize 归一化梯度。
     * 端侧/线程：构造期；参数：center/radius 为格数，p/u 为纬度/经度弧度；返回：顶点；副作用：无。
     * 排查：极点暗线查三角面退化，周向首尾必须使用同一解析位置。
     */
    private static Vertex ellipsoidVertex(Vec3d center, Vec3d radius, double p, double u) {
        double x = Math.cos(p) * Math.cos(u), y = Math.sin(p), z = Math.cos(p) * Math.sin(u);
        return new Vertex(center.add(x * radius.x, y * radius.y, z * radius.z),
                new Vec3d(x / radius.x, y / radius.y, z / radius.z).normalize());
    }

    /**
     * 功能：构建圆滑锥管；引用 MC API：net.minecraft.util.math.Vec3d#crossProduct 建立局部径向基。
     * 端侧/线程：构造期；参数：target/start/end 为目标与端点，r0/r1 为端半径格数，material 为槽；
     * 返回：无；副作用：追加闭合管面；排查：锥管明暗查沿轴的半径梯度。
     */
    private static void tube(List<Face> target, Vec3d start, Vec3d end, double r0, double r1, int material) {
        Vec3d axis = end.subtract(start).normalize();
        Vec3d u = axis.crossProduct(Math.abs(axis.y) < .9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0)).normalize();
        Vec3d v = axis.crossProduct(u);
        final int segments = Math.max(r0, r1) <= .05 ? 8 : 16;
        List<Vertex> startRing = new ArrayList<>(), endRing = new ArrayList<>();
        for (int a = 0; a < segments; a++) {
            double angle = a * Math.PI * 2 / segments, next = (a + 1) * Math.PI * 2 / segments;
            Vec3d radialA = u.multiply(Math.cos(angle)).add(v.multiply(Math.sin(angle)));
            Vec3d radialB = u.multiply(Math.cos(next)).add(v.multiply(Math.sin(next)));
            Vec3d na = radialA.add(axis.multiply((r0 - r1) / start.distanceTo(end))).normalize();
            Vec3d nb = radialB.add(axis.multiply((r0 - r1) / start.distanceTo(end))).normalize();
            Vec3d a0 = start.add(radialA.multiply(r0)), a1 = end.add(radialA.multiply(r1));
            Vec3d b0 = start.add(radialB.multiply(r0)), b1 = end.add(radialB.multiply(r1));
            target.add(new Face(new Vertex(a0, na), new Vertex(b0, nb), new Vertex(b1, nb), new Vertex(a1, na), material));
            startRing.addFirst(new Vertex(a0, axis.negate()));
            endRing.add(new Vertex(a1, axis));
        }
        polygon(target, startRing, material);
        polygon(target, endRing, material);
    }

    /**
     * 功能：缓存平面薄件；引用 MC API：net.minecraft.util.math.Vec3d#crossProduct 求面法线。
     * 端侧/线程：构造期；参数：target/a/b/c/d/material 为目标、顶点及槽；返回：无；副作用：追加一面。
     * 排查：桨面黑色查绕序，平板无需套用曲面法线。
     */
    private static void flat(List<Face> target, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int material) {
        Vec3d normal = surfaceNormal(b.subtract(a), c.subtract(a));
        target.add(new Face(new Vertex(a, normal), new Vertex(b, normal), new Vertex(c, normal), new Vertex(d, normal), material));
    }

    /**
     * 功能：为毫米级曲面计算真正的单位法线；引用 MC API：net.minecraft.util.math.Vec3d#crossProduct 求面积向量。
     * 端侧：构造期；参数：first/second 为不共线切向量；返回：单位外法线；副作用：无。
     * 排查：Vec3d.normalize 会把长度小于 1e-4 的向量直接置零；有限差分叉积及细小板缝必须直接除以长度。
     */
    private static Vec3d surfaceNormal(Vec3d first, Vec3d second) {
        Vec3d cross = first.crossProduct(second);
        return cross.multiply(1 / Math.sqrt(cross.lengthSquared()));
    }

    /**
     * 功能：绘制指定静态部件；引用 MC API：net.minecraft.client.render.VertexConsumer 提交缓存顶点。
     * 端侧/线程：渲染线程；参数：part 为静态部件名称，paint 为同步涂装，wreck 为残骸；
     * 返回：无；副作用：提交顶点；排查：透明度查玻璃颜色，残骸只压暗 RGB 而保留 alpha。
     */
    public void render(String part, MatrixStack matrices, VertexConsumer buffer, int light, int paint, boolean wreck) {
        renderFaces(part, matrices, buffer, light, paint, wreck, -1);
    }

    /**
     * 功能：首次上传 GPU 时写入局部坐标；引用 net.minecraft.client.render.VertexConsumer 填充静态顶点。
     * 端侧：渲染线程；参数：blockLight 为内饰灯光分组，-1 表示全部；返回：无；不在常规逐帧路径调用。
     * 排查：分组保留原 8–14 级舱灯距离衰减，不能将所有内饰固定为满亮。
     */
    void bake(String part, VertexConsumer buffer, int paint, boolean wreck, int blockLight) {
        renderFaces(part, new MatrixStack(), buffer, 0, paint, wreck, blockLight);
    }

    /** 功能：共用缓存上传与描边兼容路径的面色规则；仅客户端；blockLightFilter 按原舱灯分组筛选，无世界修改。 */
    private void renderFaces(String part, MatrixStack matrices, VertexConsumer buffer, int light,
            int paint, boolean wreck, int blockLightFilter) {
        List<Face> faces = doors.containsKey(part) ? doors.get(part) : switch (part) {
            case "glass" -> glass; case "gear" -> gear; case "rotor" -> rotor; case "tail" -> tailRotor;
            case "number" -> number; case "wordmark" -> wordmark; case "camouflage" -> camouflage;
            case "interior" -> interior; case "lamp" -> lamp; case "strut" -> strut;
            case "searchlight" -> searchlight; case "searchlight_lens" -> searchlightLens;
            case "navigation_lights" -> navigationLights; case "cyclic" -> cyclic; default -> shell;
        };
        boolean decal = part.endsWith("number") || part.endsWith("wordmark"), camo = part.endsWith("camouflage");
        boolean body = part.endsWith("shell"), undercarriage = part.equals("gear") || part.equals("strut");
        boolean cabin = part.endsWith("interior"), emitting = part.equals("lamp") && !wreck;
        for (Face face : faces) {
            if (blockLightFilter >= 0 && face.blockLight != blockLightFilter << 4) continue;
            // 迷彩喷漆面只由独立材质提交一次；玻璃、金属与内饰继续走既有图层。
            if (body && paint < 3 && paintedMaterial(face.material)) continue;
            int faceLight = light;
            if (cabin && !wreck) {
                // 只增强舱内局部块光，保留天空光；不照亮机外地形或制造全局动态灯。
                faceLight = light & 0xffff0000 | Math.max(light & 0xffff, face.blockLight);
            }
            int color = switch (face.material) {
                case PAINT, BELT -> PAINT_COLORS[paint]; case STRIPE -> paint == 5 ? 0xffffcc57 : 0xffedf1e9;
                case DARK -> 0xff18212c; case METAL -> 0xffbdc9d4; case SEAT -> 0xff26313c;
                case LINER -> 0xff4b5662; case SEAL -> 0xff0d151c; case SEAM -> 0xff687681;
                case GLASS -> GLASS_COLOR; default -> paint == 5 ? PAINT_COLORS[paint] : 0xfff4f7fc;
            };
            if (camo) color = 0xffffffff;
            else if (paint < 6 && (body || undercarriage) && paintedMaterial(face.material))
                color = liveryColor(paint, face.material, undercarriage);
            if (wreck) color = color & 0xff000000 | (color >> 2 & 0x003f3f3f);
            if (decal) color = 0xffffffff;
            if (emitting) color = 0xffffefc4;
            if(part.equals("searchlight_lens"))color=wreck?0xff302c24:0xfffff0cd;
            if(part.equals("navigation_lights"))color=0xff351010;
            surfaceVertex(matrices, buffer, face.a, color, faceLight);
            surfaceVertex(matrices, buffer, face.b, color, faceLight);
            surfaceVertex(matrices, buffer, face.c, color, faceLight);
            surfaceVertex(matrices, buffer, face.d, color, faceLight);
        }
    }

    /**
     * 功能：提交实体格式顶点，供实体仪表共用；引用 MC API：net.minecraft.client.render.VertexConsumer
     * 写入纹理、覆盖层、环境光与 net.minecraft.client.util.math.MatrixStack 变换后的法线。
     * 端侧/线程：渲染线程；参数：point/normal/color/light/u/v 为坐标、法线、ARGB、光照及 UV；返回：无。
     * 副作用：提交一个顶点；排查：光影暗面查 UV 展开与法线，禁止缺失 overlay/light 属性。
     */
    public static void vertex(MatrixStack matrices, VertexConsumer buffer, Vec3d point, Vec3d normal,
            int color, int light, float u, float v) {
        // SpriteTexturedVertexConsumer 的方法会返回底层消费者，分步调用以保留玻璃图集的 UV 变换。
        buffer.vertex(matrices.peek(), (float) point.x, (float) point.y, (float) point.z);
        buffer.color(color);
        buffer.texture(u, v);
        buffer.overlay(OverlayTexture.DEFAULT_UV);
        buffer.light(light);
        buffer.normal(matrices.peek(), (float) normal.x, (float) normal.y, (float) normal.z);
    }
}
