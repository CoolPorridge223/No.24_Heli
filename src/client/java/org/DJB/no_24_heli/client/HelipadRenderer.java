package org.DJB.no_24_heli.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.DJB.no_24_heli.HelipadBlock;
import org.DJB.no_24_heli.HelipadBlockEntity;
import org.DJB.no_24_heli.No_24_heli;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端渲染线程绘制按世界方格裁切缓存的六十四边停机坪，H 使用放置时保存的东西或南北轴向。
 * net.minecraft.client.render.RenderLayer 的原版方块实心层接收表面环境亮度和方块面明暗，夜间仅灯芯使用自发光层；
 * 主体异常发黑时检查表面采光位置和方块图层，灯不亮时检查世界昼夜，缺面时检查纹理与面朝向。
 */
@Environment(EnvType.CLIENT)
public final class HelipadRenderer implements BlockEntityRenderer<HelipadBlockEntity> {
    private record Point(float x,float z){}
    /** 顶面互不覆盖；圆周外壁单独缓存，不从内部颜色分区生成侧墙。 */
    private record Face(List<Point> points,int color,boolean wall){}
    /** 每格只保存与本格相交的灯座和灯芯轮廓，跨格灯具随各自方块实体裁切。 */
    private record Lamp(List<Point> base,List<Point> lens){}
    private static final Map<Integer,List<Face>> MESHES=new HashMap<>();
    /** 黄环内外半径，单位：格；灯具沿两者中间的圆周均匀分布。 */
    private static final double RING_INNER_RADIUS=4.62,RING_OUTER_RADIUS=4.85;
    /** 每圈十六盏、间隔二十二点五度；每盏以八边形近似圆形。 */
    private static final int LAMP_COUNT=16,LAMP_SEGMENTS=8;
    /** 灯座直径 0.17 格、灯芯直径 0.12 格，均小于黄环 0.23 格宽度。 */
    private static final double LAMP_RADIUS=.085,LENS_RADIUS=.06;
    /** 灯座位于黄环上方，灯芯略高于灯座；单位：相对方块底面的格数。 */
    private static final float LAMP_BOTTOM=.252f,LAMP_TOP=.277f,LENS_TOP=.289f;
    /** 灯座保持深灰漫反射，灯芯白天暗琥珀色、夜间暖黄色。 */
    private static final int LAMP_COLOR=0xff353a40,LENS_OFF_COLOR=0xff71613a,LENS_ON_COLOR=0xffffd477;
    /** 与圆盘一样按方格缓存几何，逐帧只切换灯芯的颜色、光照和图层。 */
    private static final Map<Integer,List<Lamp>> LAMP_MESHES=new HashMap<>();
    /** 夜间灯芯使用原版信标光束的无光照贴图层，保持不透明深度和暖黄色。 */
    private static final Identifier TEXTURE=Identifier.of("minecraft","textures/misc/white.png");
    /** 现有方块纹理中央的 H 为纯白色，复用该像素使方块图集层保留网格原配色。 */
    private static final Identifier PAD_SPRITE=No_24_heli.id("block/helipad");
    /** 裁切结果允许的浮点误差，单位：格；只用于剔除相邻碎片之间不可见的内部侧壁。 */
    private static final float CLIP_EPSILON=.00001f;
    /** H 的两根竖笔和横笔，单位为相对圆盘中心的格数；东西向在缓存建立时旋转九十度。 */
    private static final List<List<Point>> MARKING=List.of(
            rectangle(-1.8f,-2.1f,-1.15f,2.1f),rectangle(1.15f,-2.1f,1.8f,2.1f),
            rectangle(-1.15f,-.33f,1.15f,.33f));
    public HelipadRenderer(BlockEntityRendererFactory.Context context){}
    /**
     * 取消 net.minecraft.client.render.block.entity.BlockEntityRenderer 默认的六十四格球形剔除。
     * net.minecraft.client.render.WorldRenderer 已按区块加载、地形视距和可见性筛选候选；
     * 此处不加载远方区块，也不另设一个会让已加载停机坪提前消失的距离门槛。
     */
    @Override public boolean isInRenderDistance(HelipadBlockEntity entity,Vec3d cameraPos){return true;}
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
    private static Point polar(double angle,double radius){return new Point((float)(Math.cos(angle)*radius),(float)(Math.sin(angle)*radius));}
    private static List<Point> rectangle(float x0,float z0,float x1,float z1){return List.of(new Point(x0,z0),new Point(x0,z1),new Point(x1,z1),new Point(x1,z0));}
    /** 网格按固定轴向和碎片偏移缓存，最多两组各一百二十一格；相机移动不重算网格或改变 H。 */
    private static List<Face> mesh(int x,int z,Direction.Axis axis){
        int key=(axis==Direction.Axis.X?121:0)+(x+5)*11+z+5;return MESHES.computeIfAbsent(key,k->{
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
        });
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
        int key=(x+5)*11+z+5;
        return LAMP_MESHES.computeIfAbsent(key,k->{
            List<Lamp> result=new ArrayList<>();
            for(int i=0;i<LAMP_COUNT;i++){
                Point center=polar(-i*Math.PI*2/LAMP_COUNT,(RING_INNER_RADIUS+RING_OUTER_RADIUS)/2);
                List<Point> base=cell(lampOutline(center,LAMP_RADIUS),x,z);
                if(base.size()>=3)result.add(new Lamp(base,cell(lampOutline(center,LENS_RADIUS),x,z)));
            }
            return List.copyOf(result);
        });
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
     * 功能：依据当前同步的太阳角度与天气，实时判断指示灯是否应当开启。
     * net.minecraft.world.World#isNight 读取缓存环境暗度，但
     * net.minecraft.client.world.ClientWorld 只在构造时计算该缓存，不能用于客户端动态开关。
     * 这里沿用 net.minecraft.world.World#calculateAmbientDarkness 的公式和 isNight 的四级阈值，
     * 使用 getSkyAngle、getRainGradient、getThunderGradient 的实时值；固定时间维度仍关闭。
     * 端侧/线程：仅客户端渲染线程；仅读取世界，不修改缓存暗度或其他渲染的光照。
     * 故障排查：时间命令、睡觉或自然入夜后不切换时，检查世界时间同步，不能退回缓存判定。
     */
    private static boolean lampsOn(World world,float delta){
        if(world.getDimension().hasFixedTime())return false;
        // 原版太阳光强为 0.5 + 2 × 限制到 [-0.25,0.25] 的太阳高度余弦，范围 0～1。
        double sunlight=.5+2*MathHelper.clamp((double)MathHelper.cos(
                world.getSkyAngle(delta)*(float)(Math.PI*2)),-.25,.25);
        // 原版雨、雷各使太阳光最多衰减 5/16；暗度以 0～11 表示，四级开始视为夜晚。
        double rain=1-(double)(world.getRainGradient(delta)*5)/16;
        double thunder=1-(double)(world.getThunderGradient(delta)*5)/16;
        return (int)((1-sunlight*rain*thunder)*11)>=4;
    }
    /**
     * 使用 net.minecraft.client.render.RenderLayer#getSolid 的原版方块实心层绘制彩色网格，
     * 配合 net.minecraft.client.render.WorldRenderer#getLightmapCoordinates 读取露出表面的光照，
     * 按 net.minecraft.world.BlockRenderView#getBrightness 处理面朝向，使地面与周围方块采用相同采光规则。
     * 入口 light 来自方块内部，不能直接作为薄平台顶面的亮度；颜色分区拼接为单一顶面。
     * 仅客户端渲染线程调用，不改变停机坪方块、碰撞或服务端状态。
     */
    @Override public void render(HelipadBlockEntity entity,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light,int overlay){
        var state=entity.getCachedState();int x=state.get(HelipadBlock.OFFSET_X)-5,z=state.get(HelipadBlock.OFFSET_Z)-5;
        World world=entity.getWorld();BlockPos pos=entity.getPos();
        int topLight=maximumLight(light,WorldRenderer.getLightmapCoordinates(world,pos.up()));
        var sprite=MinecraftClient.getInstance().getSpriteAtlas(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE).apply(PAD_SPRITE);
        var buffer=sprite.getTextureSpecificVertexConsumer(consumers.getBuffer(RenderLayer.getSolid()));var entry=matrices.peek();
        for(var face:mesh(x,z,state.get(HelipadBlock.MARKING_AXIS))){
            var p=face.points;if(p.size()<3)continue;
            int color=shadeColor(world,face.color,0,1,0);
            if(!face.wall)renderTop(buffer,entry,p,x,z,.25f,color,topLight,overlay);
            if(face.wall)for(int i=0;i<p.size();i++){
                Point a=p.get(i),b=p.get((i+1)%p.size());
                float dx=b.x-a.x,dz=b.z-a.z,length=(float)Math.hypot(dx,dz);
                // 裁切可在方格角点生成重复顶点，零长度边没有侧面；顺时针边的外法线为 (-dz,0,dx)。
                if(length==0||isClipEdge(a,b,x,z))continue;
                float nx=-dz/length,nz=dx/length;
                int sideLight=sideLight(world,pos,topLight,nx,nz);
                int bottomColor=shadeColor(world,0xff282e36,nx,0,nz),topColor=shadeColor(world,0xff343c46,nx,0,nz);
                vertex(buffer,entry,a,x,z,0,bottomColor,sideLight,overlay,nx,0,nz);vertex(buffer,entry,b,x,z,0,bottomColor,sideLight,overlay,nx,0,nz);
                vertex(buffer,entry,b,x,z,.25f,topColor,sideLight,overlay,nx,0,nz);vertex(buffer,entry,a,x,z,.25f,topColor,sideLight,overlay,nx,0,nz);
            }
        }
        boolean lampsOn=lampsOn(world,delta);
        List<Lamp> lamps=lampMesh(x,z);
        for(Lamp lamp:lamps){
            renderLampPart(buffer,entry,lamp.base,x,z,LAMP_BOTTOM,LAMP_TOP,LAMP_COLOR,topLight,overlay,world,pos,false);
            if(!lampsOn)renderLampPart(buffer,entry,lamp.lens,x,z,LAMP_TOP,LENS_TOP,LENS_OFF_COLOR,topLight,overlay,world,pos,false);
        }
        if(lampsOn&&!lamps.isEmpty()){
            // 切换图层可能提交前一个缓冲；普通灯座全部画完后才获取自发光缓冲，避免混层。
            var lensBuffer=consumers.getBuffer(RenderLayer.getBeaconBeam(TEXTURE,false));
            for(Lamp lamp:lamps)renderLampPart(lensBuffer,entry,lamp.lens,x,z,LAMP_TOP,LENS_TOP,
                    LENS_ON_COLOR,LightmapTextureManager.MAX_LIGHT_COORDINATE,overlay,world,pos,true);
        }
    }
    /**
     * 在客户端渲染线程将裁切后的凸轮廓挤出为低矮灯体，顶面和侧面均提交正确法线。
     * net.minecraft.client.render.VertexConsumer 使用调用方指定的普通或自发光层；
     * 满亮度只传给开启的灯芯，不影响灯座、黄环、H 或周围世界的方块光照。
     */
    private static void renderLampPart(VertexConsumer buffer,MatrixStack.Entry entry,List<Point> points,
                                       int x,int z,float bottom,float top,int color,int light,int overlay,
                                       World world,BlockPos pos,boolean emissive){
        if(points.size()<3)return;
        int topColor=emissive?color:shadeColor(world,color,0,1,0);
        renderTop(buffer,entry,points,x,z,top,topColor,light,overlay);
        for(int i=0;i<points.size();i++){
            Point a=points.get(i),b=points.get((i+1)%points.size());
            float dx=b.x-a.x,dz=b.z-a.z,length=(float)Math.hypot(dx,dz);
            if(length==0||isClipEdge(a,b,x,z))continue;
            float nx=-dz/length,nz=dx/length;
            int sideColor=emissive?color:shadeColor(world,color,nx,0,nz);
            int sideLight=emissive?light:sideLight(world,pos,light,nx,nz);
            vertex(buffer,entry,a,x,z,bottom,sideColor,sideLight,overlay,nx,0,nz);
            vertex(buffer,entry,b,x,z,bottom,sideColor,sideLight,overlay,nx,0,nz);
            vertex(buffer,entry,b,x,z,top,sideColor,sideLight,overlay,nx,0,nz);
            vertex(buffer,entry,a,x,z,top,sideColor,sideLight,overlay,nx,0,nz);
        }
    }
    /**
     * 把凸多边形扇面拆为三个真正的四边形，供 net.minecraft.client.render.VertexConsumer 提交。
     * 旧版重复一个顶点凑四边形会生成零面积三角形，第三方光影计算切线时可能产生非有限值。
     * 仅客户端渲染线程；跳过裁切出的零面积碎片，不引入重复覆盖面。
     */
    private static void renderTop(VertexConsumer buffer,MatrixStack.Entry entry,List<Point> points,
                                  int x,int z,float y,int color,int light,int overlay){
        Point a=points.getFirst();
        for(int i=1;i<points.size()-1;i++){
            Point b=points.get(i),c=points.get(i+1);
            if(Math.abs((b.x-a.x)*(c.z-a.z)-(b.z-a.z)*(c.x-a.x))<1e-8f)continue;
            Point center=new Point((a.x+b.x+c.x)/3,(a.z+b.z+c.z)/3);
            Point[] corners={a,b,c};
            for(int corner=0;corner<3;corner++){
                Point current=corners[corner],next=corners[(corner+1)%3],previous=corners[(corner+2)%3];
                vertex(buffer,entry,current,x,z,y,color,light,overlay,0,1,0);
                vertex(buffer,entry,new Point((current.x+next.x)/2,(current.z+next.z)/2),x,z,y,color,light,overlay,0,1,0);
                vertex(buffer,entry,center,x,z,y,color,light,overlay,0,1,0);
                vertex(buffer,entry,new Point((current.x+previous.x)/2,(current.z+previous.z)/2),x,z,y,color,light,overlay,0,1,0);
            }
        }
    }
    /**
     * 在客户端渲染线程识别方格裁切边，避免把连续圆盘和跨格灯体的内部接缝挤出为侧墙。
     * 不调用 MC API；真实圆周边保留，排查格子状黑线时检查裁切误差而非改变模型颜色。
     */
    private static boolean isClipEdge(Point a,Point b,int x,int z){
        return Math.abs(a.x-b.x)<CLIP_EPSILON&&Math.abs(Math.abs(a.x-x)-.5f)<CLIP_EPSILON
                ||Math.abs(a.z-b.z)<CLIP_EPSILON&&Math.abs(Math.abs(a.z-z)-.5f)<CLIP_EPSILON;
    }
    /**
     * 在客户端渲染线程按 net.minecraft.world.BlockRenderView#getBrightness 的方块规则计算面明暗；
     * 斜圆周按法线分量平方混合两个轴向亮度，维持圆边连续，不引入实体的额外定向光。
     * 只修改送入顶点的颜色，原材质配色保持不变；过暗时先查实际天空光和遮挡。
     */
    private static int shadeColor(World world,int color,float nx,float ny,float nz){
        float brightness=ny>0?world.getBrightness(Direction.UP,true):
                nx*nx*world.getBrightness(nx<0?Direction.WEST:Direction.EAST,true)
                        +nz*nz*world.getBrightness(nz<0?Direction.NORTH:Direction.SOUTH,true);
        int red=Math.round((color>>16&255)*brightness),green=Math.round((color>>8&255)*brightness),blue=Math.round((color&255)*brightness);
        return color&0xff000000|red<<16|green<<8|blue;
    }
    /**
     * 在客户端渲染线程从 net.minecraft.client.render.WorldRenderer#getLightmapCoordinates
     * 读取朝外相邻格，并分别保留该格与上表面较强的天空光和方块光，避免薄实体内部造成自遮黑。
     * 使用真实光照而非满亮度；夜间、屋顶遮挡与外部光源仍由世界光照图决定。
     */
    private static int sideLight(World world,BlockPos pos,int topLight,float nx,float nz){
        Direction outward=Math.abs(nx)>=Math.abs(nz)?(nx<0?Direction.WEST:Direction.EAST):
                (nz<0?Direction.NORTH:Direction.SOUTH);
        int outside=WorldRenderer.getLightmapCoordinates(world,pos.offset(outward));
        return maximumLight(topLight,outside);
    }
    /**
     * 合并 net.minecraft.client.render.LightmapTextureManager 使用的两个十六位光照分量，
     * 天空光与方块光分别取较强值；相邻顶格被屋顶占据时仍保留表面所在格的真实侧向入射光。
     * 仅客户端渲染线程调用，不写入世界光照，也不会将任一分量提升到来源之外的亮度。
     */
    private static int maximumLight(int first,int second){
        return Math.max(first&0xffff,second&0xffff)|Math.max(first>>>16,second>>>16)<<16;
    }
    /**
     * 向 net.minecraft.client.render.VertexConsumer 写入完整受光顶点：白色纹理保留原配色，
     * light 使用表面采样的天空光与方块光，normal 经 net.minecraft.client.util.math.MatrixStack.Entry 变换。
     * 主体、灯座和熄灭的灯芯随环境变暗；仅开启的灯芯由调用方提供满亮度。
     */
    private static void vertex(VertexConsumer buffer,MatrixStack.Entry entry,Point p,int x,int z,float y,int color,int light,int overlay,float nx,float ny,float nz){
        // net.minecraft.client.render.SpriteTexturedVertexConsumer 返回底层消费者，逐项调用才能保留图集 UV 变换。
        buffer.vertex(entry.getPositionMatrix(),p.x-x+.5f,y,p.z-z+.5f);
        // 在 H 中央白色像素内部展开非零 UV 面积，避免固定 UV 导致光影切线/视差计算除零。
        float u=ny!=0?p.x-x:(p.x-x)*nz-(p.z-z)*nx;
        float v=ny!=0?p.z-z:y;
        buffer.color(color);buffer.texture(.5f+u*.01f,.5f+v*.01f);
        buffer.overlay(overlay);buffer.light(light);buffer.normal(entry,nx,ny,nz);
    }
}
