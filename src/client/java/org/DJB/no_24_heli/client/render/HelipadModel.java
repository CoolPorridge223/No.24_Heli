/** 文件：停机坪静态区块模型与灯芯网格缓存；端侧：CLIENT；方块：no_24_heli:helipad、helipad_surface。 */
package org.DJB.no_24_heli.client.render;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.block.HelipadBlock;

/**
 * 职责：资源加载时烘焙圆盘、标记、圆周与灯座，交给区块缓存；逐帧仅保留灯芯。
 * 引用：net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin 替换方块模型；
 * net.minecraft.client.render.model.BakedQuad 使用原版方块顶点格式，兼容普通与第三方区块渲染器。
 * 生命周期：客户端初始化注册，每次资源重载重新创建；构造完毕后网格只读，可供区块工作线程读取。
 * 端侧/线程：仅客户端，构造位于模型加载线程；不读写世界或权威状态。
 * 排查：圆盘缺失查 MODEL 渲染类型；默认 FabricBakedModel 几何入口必须使用本模型，不能转发原立方体；
 * 颜色查图集 UV 与 ABGR；重载后灯芯错纹理查缓存替换。
 */
@Environment(EnvType.CLIENT)
public final class HelipadModel implements BakedModel {
    /** 原资源只提供粒子与物品变换，几何入口全部保留在本模型，禁止回退到立方体。 */
    private final BakedModel original;
    /** 偏移有 11×11 种，H 有两个轴向；缓存容量固定，不随世界内停机坪数量增长。 */
    private static final int CELL_WIDTH=11,CELL_COUNT=CELL_WIDTH*CELL_WIDTH;
    /** net.minecraft.client.render.VertexFormats.BLOCK 每顶点八个整数，每面四顶点；法线按有符号字节打包。 */
    private static final int VERTEX_STRIDE=8,QUAD_VERTICES=4,NORMAL_SCALE=127;
    /** 两倍三角形面积阈值，单位：平方格；只剔除裁切产生的零面积碎片。 */
    private static final float MIN_TRIANGLE_AREA=1e-8f;
    /** 裁切点均以圆盘中心为原点，单位：格。 */
    private record Point(float x,float z){}
    /** 顶面分区互不覆盖，wall 仅标记圆盘外轮廓。 */
    private record Face(List<Point> points,int color,boolean wall){}
    /** 灯座和灯芯轮廓分别缓存，跨格部分仍由所在方块负责。 */
    private record Lamp(List<Point> base,List<Point> lens){}
    /** 烘焙期顶点使用局部方块坐标、ARGB 颜色、非零面积 UV 和单位法线。 */
    private record Vertex(float x,float y,float z,int color,float u,float v,float nx,float ny,float nz){}
    /** 完整模型按轴向和偏移索引，资源加载完成后不再修改。 */
    private final List<List<BakedQuad>> surfaces;
    /** 当前图集对应的灯芯；资源重载一次性替换，避免跨帧使用旧图集坐标。 */
    private static volatile List<List<BakedQuad>> lenses;
    /** 黄环、灯具尺寸和颜色沿用既有圆盘；所有长度单位为格。 */
    private static final double RING_INNER_RADIUS=4.62,RING_OUTER_RADIUS=4.85;
    private static final int LAMP_COUNT=16,LAMP_SEGMENTS=8;
    private static final double LAMP_RADIUS=.085,LENS_RADIUS=.06;
    private static final float LAMP_BOTTOM=.252f,LAMP_TOP=.277f,LENS_TOP=.289f;
    private static final int LAMP_COLOR=0xff353a40;
    /** 裁切误差小于十万分之一格时视为同点或内部接缝。 */
    private static final float CLIP_EPSILON=.00001f;
    /** H 的两根竖笔和横笔，东西向在构造期旋转九十度。 */
    private static final List<List<Point>> MARKING=List.of(
            rectangle(-1.8f,-2.1f,-1.15f,2.1f),rectangle(1.15f,-2.1f,1.8f,2.1f),
            rectangle(-1.15f,-.33f,1.15f,.33f));

    /**
     * 功能：只替换停机坪方块资源模型；引用 API：net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin 在资源重载时重新烘焙。
     * 端侧：客户端初始化；参数/返回：无；副作用：登记模型回调；排查：物品模型使用独立资源，不替换。
     */
    public static void register(){
        ModelLoadingPlugin.register(plugin->plugin.modifyModelAfterBake().register((model,context)->
                No_24_heli.id("block/helipad").equals(context.resourceId())?new HelipadModel(model):model));
    }

    /**
     * 功能：为两种 H 朝向与全部碎片构建只读网格；引用 MC API：net.minecraft.client.render.model.BakedModel#getParticleSprite 取得本次图集。
     * 端侧：客户端模型加载线程；参数：original 为原资源模型；副作用：替换灯芯缓存；排查：不缓存世界光照。
     */
    private HelipadModel(BakedModel original){
        this.original=original;
        Sprite sprite=original.getParticleSprite();
        List<List<BakedQuad>> body=new ArrayList<>(CELL_COUNT*2),lightMeshes=new ArrayList<>(CELL_COUNT);
        for(Direction.Axis axis:List.of(Direction.Axis.Z,Direction.Axis.X)){
            for(int x=-5;x<=5;x++)for(int z=-5;z<=5;z++){
                List<BakedQuad> quads=new ArrayList<>(),lightQuads=new ArrayList<>();
                if(HelipadBlock.covers(x,z)){
                    bakeSurface(quads,sprite,x,z,axis);
                    for(Lamp lamp:lampMesh(x,z)){
                        bakeLamp(quads,sprite,lamp.base,x,z,LAMP_BOTTOM,LAMP_TOP,LAMP_COLOR);
                        if(axis==Direction.Axis.Z)bakeLamp(lightQuads,sprite,lamp.lens,x,z,LAMP_TOP,LENS_TOP,0xffffffff);
                    }
                }
                body.add(List.copyOf(quads));
                if(axis==Direction.Axis.Z)lightMeshes.add(List.copyOf(lightQuads));
            }
        }
        surfaces=List.copyOf(body);lenses=List.copyOf(lightMeshes);
    }

    /**
     * 功能：区块编译时返回当前碎片的缓存面；引用 MC API：net.minecraft.block.BlockState 读取持久偏移与 H 轴向。
     * 端侧：客户端区块工作线程；参数：state/face/random 为方块状态、裁剪面及随机源；返回：只读网格。
     * 副作用：无；排查：全部面归入未裁剪组，避免相邻方块错误剔除薄圆盘或灯座。
     */
    @Override public List<BakedQuad> getQuads(BlockState state,Direction face,Random random){
        if(face!=null||state==null)return List.of();
        int key=state.get(HelipadBlock.OFFSET_X)*CELL_WIDTH+state.get(HelipadBlock.OFFSET_Z);
        return surfaces.get(key+(state.get(HelipadBlock.MARKING_AXIS)==Direction.Axis.X?CELL_COUNT:0));
    }

    /** 功能：使用原版 BakedQuad 适配路径；端侧：客户端渲染线程；返回：true；无副作用，避免转发回原立方体。 */
    @Override public boolean isVanillaAdapter(){return true;}
    /** 功能：薄顶面使用原版平面采光；端侧：客户端区块线程；返回：false；避免细小分区的环境遮蔽接缝。 */
    @Override public boolean useAmbientOcclusion(){return false;}

    /** 功能：声明三维方块模型；端侧：客户端；返回：true；不调用原资源几何。 */
    @Override public boolean hasDepth(){return true;}
    /** 功能：保留方块面朝向采光；端侧：客户端；返回：true；不强制自发光。 */
    @Override public boolean isSideLit(){return true;}
    /** 功能：使用区块模型而非内置物品绘制；端侧：客户端；返回：false；无副作用。 */
    @Override public boolean isBuiltin(){return false;}
    /** 功能：复用原资源粒子；引用 MC API：net.minecraft.client.texture.Sprite；端侧：资源加载或客户端渲染线程。 */
    @Override public Sprite getParticleSprite(){return original.getParticleSprite();}
    /** 功能：复用原资源展示变换；引用 MC API：net.minecraft.client.render.model.json.ModelTransformation；客户端调用。 */
    @Override public ModelTransformation getTransformation(){return original.getTransformation();}
    /** 功能：复用原资源覆盖规则；引用 MC API：net.minecraft.client.render.model.json.ModelOverrideList；客户端调用。 */
    @Override public ModelOverrideList getOverrides(){return original.getOverrides();}

    /** 功能：返回当前图集的灯芯缓存；引用 MC API：net.minecraft.block.BlockState 提供偏移；端侧：客户端渲染线程；无副作用。 */
    public static List<BakedQuad> lenses(BlockState state){
        return lenses.get(state.get(HelipadBlock.OFFSET_X)*CELL_WIDTH+state.get(HelipadBlock.OFFSET_Z));
    }

    /** 将凸多边形裁切到一个方格内，各碎片共用精确圆周而非方形贴图。 */
    private static List<Point> clip(List<Point> input,int axis,float bound,boolean greater){
        List<Point> output=new ArrayList<>();if(input.isEmpty())return output;
        Point a=input.getLast();float av=(axis==0?a.x:a.z)-bound;
        for(Point b:input){
            float bv=(axis==0?b.x:b.z)-bound;boolean ai=greater?av>=0:av<=0,bi=greater?bv>=0:bv<=0;
            if(ai!=bi){float t=av/(av-bv);output.add(new Point(a.x+(b.x-a.x)*t,a.z+(b.z-a.z)*t));}
            if(bi)output.add(b);a=b;av=bv;
        }
        return output;
    }
    /** 功能：裁切并清除方格边界重复点；端侧：模型加载线程；参数为轮廓与偏移；返回：新轮廓；无世界访问。 */
    private static List<Point> cell(List<Point> input,int x,int z){
        var p=clip(input,0,x-.5f,true);p=clip(p,0,x+.5f,false);p=clip(p,1,z-.5f,true);p=clip(p,1,z+.5f,false);
        // 精确落在方格角点时裁切可能重复顶点；去重后再交给四边形着色器计算切线。
        List<Point> clean=new ArrayList<>();
        for(Point point:p)if(clean.isEmpty()||!samePoint(clean.getLast(),point))clean.add(point);
        if(clean.size()>1&&samePoint(clean.getFirst(),clean.getLast()))clean.removeLast();
        return clean;
    }
    /** 裁切点的平面距离比较；误差小于十万分之一格时视为同一点。 */
    private static boolean samePoint(Point a,Point b){return Math.abs(a.x-b.x)<CLIP_EPSILON&&Math.abs(a.z-b.z)<CLIP_EPSILON;}
    /** 功能：按弧度和半径建立圆周点；端侧：模型加载线程；返回：圆盘局部点；无副作用与世界访问。 */
    private static Point polar(double angle,double radius){return new Point((float)(Math.cos(angle)*radius),(float)(Math.sin(angle)*radius));}
    /** 功能：建立顺时针矩形轮廓；端侧：模型加载线程；参数为边界格数；返回：只读轮廓；无副作用。 */
    private static List<Point> rectangle(float x0,float z0,float x1,float z1){return List.of(new Point(x0,z0),new Point(x0,z1),new Point(x1,z1),new Point(x1,z0));}
    /** 网格按固定轴向和碎片偏移缓存，最多两组各一百二十一格；相机移动不重算网格或改变 H。 */
    private static List<Face> mesh(int x,int z,Direction.Axis axis){
        List<Face> result=new ArrayList<>();List<Point> disk=new ArrayList<>(),inner=new ArrayList<>();
        for(int i=0;i<64;i++){disk.add(polar(-i*Math.PI/32,5));inner.add(polar(-i*Math.PI/32,RING_INNER_RADIUS));}
        result.add(new Face(cell(disk,x,z),0xff454d56,true));
        List<List<Point>> grey=new ArrayList<>();grey.add(cell(inner,x,z));
        for(var marking:MARKING){
            var oriented=axis==Direction.Axis.X?marking.stream().map(point->new Point(-point.z,point.x)).toList():marking;
            List<List<Point>> remainder=new ArrayList<>();
            for(var polygon:grey)subtractMarking(polygon,oriented,remainder);
            grey=remainder;result.add(new Face(cell(oriented,x,z),0xfff5f4e9,false));
        }
        for(var polygon:grey)result.add(new Face(polygon,0xff454d56,false));
        for(int i=0;i<64;i++){
            double a=-i*Math.PI/32,b=-(i+1)*Math.PI/32;
            result.add(new Face(cell(List.of(polar(a,RING_OUTER_RADIUS),polar(b,RING_OUTER_RADIUS),polar(b,RING_INNER_RADIUS),polar(a,RING_INNER_RADIUS)),x,z),0xffedc95d,false));
            result.add(new Face(cell(List.of(polar(a,5),polar(b,5),polar(b,RING_OUTER_RADIUS),polar(a,RING_OUTER_RADIUS)),x,z),0xff454d56,false));
        }
        return List.copyOf(result);
    }
    /**
     * 从灰色面减去轴对齐的 H 笔画，逐边取外侧并继续裁内侧，输出分区互不重叠。
     * 纯几何缓存计算，不调用 MC API；消除原来相差 0.001 格的叠面与光影深度争夺。
     */
    private static void subtractMarking(List<Point> polygon,List<Point> marking,List<List<Point>> output){
        float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minZ=minX,maxZ=maxX;
        for(Point point:marking){minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);minZ=Math.min(minZ,point.z);maxZ=Math.max(maxZ,point.z);}
        var remainder=polygon;
        float[] bounds={minX,maxX,minZ,maxZ};
        for(int edge=0;edge<4;edge++){
            int axis=edge/2;boolean inside=edge%2==0;
            var outside=clip(remainder,axis,bounds[edge],!inside);
            if(outside.size()>=3)output.add(outside);
            remainder=clip(remainder,axis,bounds[edge],inside);
        }
    }
    /** 将灯具轮廓按现有方格裁切，确保中心不在本格时仍绘制跨格的可见部分。 */
    private static List<Lamp> lampMesh(int x,int z){
        List<Lamp> result=new ArrayList<>();
        for(int i=0;i<LAMP_COUNT;i++){
            Point center=polar(-i*Math.PI*2/LAMP_COUNT,(RING_INNER_RADIUS+RING_OUTER_RADIUS)/2);
            List<Point> base=cell(lampOutline(center,LAMP_RADIUS),x,z);
            if(base.size()>=3)result.add(new Lamp(base,cell(lampOutline(center,LENS_RADIUS),x,z)));
        }
        return List.copyOf(result);
    }
    /** 灯座和灯芯使用同心八边形；最外轮廓距圆心最多 0.085 格，完整落在黄环内。 */
    private static List<Point> lampOutline(Point center,double radius){
        List<Point> result=new ArrayList<>();
        for(int i=0;i<LAMP_SEGMENTS;i++){
            Point offset=polar(-i*Math.PI*2/LAMP_SEGMENTS,radius);
            result.add(new Point(center.x+offset.x,center.z+offset.z));
        }
        return result;
    }

    /**
     * 功能：把顶面与真实圆周外壁写入区块模型；引用 MC API：net.minecraft.client.render.model.BakedQuad 缓存原版受光方块面。
     * 端侧：模型加载线程；参数：target/sprite/x/z/axis 为输出、图集、碎片偏移及轴向；无世界访问。
     * 排查：内部裁切边不生成侧墙，H 和黄环与底色使用同一高度且互不覆盖。
     */
    private static void bakeSurface(List<BakedQuad> target,Sprite sprite,int x,int z,Direction.Axis axis){
        for(Face face:mesh(x,z,axis)){
            List<Point> points=face.points;if(points.size()<3)continue;
            if(!face.wall){bakeTop(target,sprite,points,x,z,.25f,face.color);continue;}
            for(int i=0;i<points.size();i++){
                Point a=points.get(i),b=points.get((i+1)%points.size());
                float dx=b.x-a.x,dz=b.z-a.z,length=(float)Math.hypot(dx,dz);
                if(length==0||isClipEdge(a,b,x,z))continue;
                float nx=-dz/length,nz=dx/length;
                quad(target,sprite,vertex(a,x,z,0,0xff282e36,nx,0,nz),vertex(b,x,z,0,0xff282e36,nx,0,nz),
                        vertex(b,x,z,.25f,0xff343c46,nx,0,nz),vertex(a,x,z,.25f,0xff343c46,nx,0,nz));
            }
        }
    }

    /** 功能：挤出灯具顶面与侧壁；端侧：模型加载线程；参数为输出、纹理、轮廓、偏移、高度和颜色；无世界访问。 */
    private static void bakeLamp(List<BakedQuad> target,Sprite sprite,List<Point> points,int x,int z,float bottom,float top,int color){
        if(points.size()<3)return;
        bakeTop(target,sprite,points,x,z,top,color);
        for(int i=0;i<points.size();i++){
            Point a=points.get(i),b=points.get((i+1)%points.size());
            float dx=b.x-a.x,dz=b.z-a.z,length=(float)Math.hypot(dx,dz);
            if(length==0||isClipEdge(a,b,x,z))continue;
            float nx=-dz/length,nz=dx/length;
            quad(target,sprite,vertex(a,x,z,bottom,color,nx,0,nz),vertex(b,x,z,bottom,color,nx,0,nz),
                    vertex(b,x,z,top,color,nx,0,nz),vertex(a,x,z,top,color,nx,0,nz));
        }
    }

    /**
     * 功能：预先将凸多边形拆为非退化四边形；端侧：模型加载线程；参数为输出、图集、轮廓、偏移、高度和颜色。
     * 不调用世界 API；四顶点直接提交，剩余三角形再拆三面，避免重复顶点破坏光影切线。
     */
    private static void bakeTop(List<BakedQuad> target,Sprite sprite,List<Point> points,int x,int z,float y,int color){
        if(points.size()==4&&triangleArea(points.get(0),points.get(1),points.get(2))>=MIN_TRIANGLE_AREA
                &&triangleArea(points.get(0),points.get(2),points.get(3))>=MIN_TRIANGLE_AREA){
            quad(target,sprite,vertex(points.get(0),x,z,y,color,0,1,0),vertex(points.get(1),x,z,y,color,0,1,0),
                    vertex(points.get(2),x,z,y,color,0,1,0),vertex(points.get(3),x,z,y,color,0,1,0));
            return;
        }
        Point a=points.getFirst();
        for(int i=1;i<points.size()-1;i++){
            Point b=points.get(i),c=points.get(i+1);
            if(triangleArea(a,b,c)<MIN_TRIANGLE_AREA)continue;
            Point center=new Point((a.x+b.x+c.x)/3,(a.z+b.z+c.z)/3);
            Point[] corners={a,b,c};
            for(int corner=0;corner<3;corner++){
                Point current=corners[corner],next=corners[(corner+1)%3],previous=corners[(corner+2)%3];
                quad(target,sprite,vertex(current,x,z,y,color,0,1,0),
                        vertex(new Point((current.x+next.x)/2,(current.z+next.z)/2),x,z,y,color,0,1,0),
                        vertex(center,x,z,y,color,0,1,0),
                        vertex(new Point((current.x+previous.x)/2,(current.z+previous.z)/2),x,z,y,color,0,1,0));
            }
        }
    }

    /** 功能：计算三角形两倍面积；端侧：模型加载线程；参数为三个局部点；返回：平方格；无副作用。 */
    private static float triangleArea(Point a,Point b,Point c){
        return Math.abs((b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x));
    }

    /**
     * 在客户端渲染线程识别方格裁切边，避免把连续圆盘和跨格灯体的内部接缝挤出为侧墙。
     * 不调用 MC API；真实圆周边保留，排查格子状黑线时检查裁切误差而非改变模型颜色。
     */
    private static boolean isClipEdge(Point a,Point b,int x,int z){
        return Math.abs(a.x-b.x)<CLIP_EPSILON&&Math.abs(Math.abs(a.x-x)-.5f)<CLIP_EPSILON
                ||Math.abs(a.z-b.z)<CLIP_EPSILON&&Math.abs(Math.abs(a.z-z)-.5f)<CLIP_EPSILON;
    }

    /** 功能：在图集白色 H 内展开局部 UV；端侧：模型加载线程；参数为几何及材质；返回：缓存顶点；无副作用。 */
    private static Vertex vertex(Point p,int x,int z,float y,int color,float nx,float ny,float nz){
        float u=ny!=0?p.x-x:(p.x-x)*nz-(p.z-z)*nx,v=ny!=0?p.z-z:y;
        return new Vertex(p.x-x+.5f,y,p.z-z+.5f,color,.5f+u*.01f,.5f+v*.01f,nx,ny,nz);
    }

    /**
     * 功能：编码 net.minecraft.client.render.model.BakedQuad 的原版 BLOCK 顶点；每顶点为八个 32 位值。
     * 端侧：模型加载线程；参数：输出、图集与四顶点；副作用：追加不可变面；排查：颜色由 ARGB 转 ABGR。
     * 光照留给区块编译读取世界，不将某次放置地点、天气或昼夜烘焙进共享模型。
     */
    private static void quad(List<BakedQuad> target,Sprite sprite,Vertex a,Vertex b,Vertex c,Vertex d){
        int[] data=new int[VERTEX_STRIDE*QUAD_VERTICES];Vertex[] vertices={a,b,c,d};
        for(int i=0;i<QUAD_VERTICES;i++){
            Vertex v=vertices[i];int offset=i*VERTEX_STRIDE;
            data[offset]=Float.floatToRawIntBits(v.x);data[offset+1]=Float.floatToRawIntBits(v.y);data[offset+2]=Float.floatToRawIntBits(v.z);
            data[offset+3]=v.color&0xff00ff00|(v.color>>16&255)|(v.color&255)<<16;
            data[offset+4]=Float.floatToRawIntBits(sprite.getFrameU(v.u));data[offset+5]=Float.floatToRawIntBits(sprite.getFrameV(v.v));
            data[offset+7]=(Math.round(v.nx*NORMAL_SCALE)&255)|(Math.round(v.ny*NORMAL_SCALE)&255)<<8
                    |(Math.round(v.nz*NORMAL_SCALE)&255)<<16;
        }
        target.add(new BakedQuad(data,-1,Direction.getFacing(a.nx,a.ny,a.nz),sprite,true));
    }
}
