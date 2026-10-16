package org.DJB.no_24_heli.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.DJB.no_24_heli.HelipadBlock;
import org.DJB.no_24_heli.HelipadBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端渲染线程绘制按世界方格裁切缓存的六十四边停机坪。
 * net.minecraft.client.render.RenderLayer 的普通实体实心层接收环境亮度和法线，夜间仅灯芯使用自发光层；
 * 主体异常发亮时检查顶点 light 与图层，灯不亮时检查世界昼夜，缺面时检查纹理与面朝向。
 */
@Environment(EnvType.CLIENT)
public final class HelipadRenderer implements BlockEntityRenderer<HelipadBlockEntity> {
    private record Point(float x,float z){}
    private record Face(List<Point> points,float y,int color){}
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
    /** 原版纯白漫反射纹理仅承载网格颜色，不使用自发光图层或发光贴图。 */
    private static final Identifier TEXTURE=Identifier.of("minecraft","textures/misc/white.png");
    public HelipadRenderer(BlockEntityRendererFactory.Context context){}
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
        var p=clip(input,0,x-.5f,true);p=clip(p,0,x+.5f,false);p=clip(p,1,z-.5f,true);return clip(p,1,z+.5f,false);
    }
    private static Point polar(double angle,double radius){return new Point((float)(Math.cos(angle)*radius),(float)(Math.sin(angle)*radius));}
    private static List<Point> rectangle(float x0,float z0,float x1,float z1){return List.of(new Point(x0,z0),new Point(x0,z1),new Point(x1,z1),new Point(x1,z0));}
    /** 网格只在首次遇到该偏移时构建，渲染帧不重新计算圆周裁切。 */
    private static List<Face> mesh(int x,int z){
        int key=(x+5)*11+z+5;return MESHES.computeIfAbsent(key,k->{
            List<Face> result=new ArrayList<>();List<Point> disk=new ArrayList<>();
            for(int i=0;i<64;i++)disk.add(polar(-i*Math.PI/32,5));
            result.add(new Face(cell(disk,x,z),.25f,0xff454d56));
            for(int i=0;i<64;i++){
                double a=-i*Math.PI/32,b=-(i+1)*Math.PI/32;
                result.add(new Face(cell(List.of(polar(a,RING_OUTER_RADIUS),polar(b,RING_OUTER_RADIUS),polar(b,RING_INNER_RADIUS),polar(a,RING_INNER_RADIUS)),x,z),.251f,0xffedc95d));
            }
            for(var h:List.of(rectangle(-1.8f,-2.1f,-1.15f,2.1f),rectangle(1.15f,-2.1f,1.8f,2.1f),rectangle(-1.15f,-.33f,1.15f,.33f)))
                result.add(new Face(cell(h,x,z),.252f,0xfff5f4e9));
            return List.copyOf(result);
        });
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
     * 使用 net.minecraft.client.render.RenderLayer#getEntitySolid 的受光实心层绘制彩色网格，
     * 将方块实体渲染入口提供的环境亮度与覆盖坐标传给每个面；标记层保留原高度以避免共面闪烁。
     * 仅客户端渲染线程调用，不改变停机坪方块、碰撞或服务端状态。
     */
    @Override public void render(HelipadBlockEntity entity,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light,int overlay){
        var state=entity.getCachedState();int x=state.get(HelipadBlock.OFFSET_X)-5,z=state.get(HelipadBlock.OFFSET_Z)-5;
        var buffer=consumers.getBuffer(RenderLayer.getEntitySolid(TEXTURE));var entry=matrices.peek();
        for(var face:mesh(x,z)){
            var p=face.points;if(p.size()<3)continue;Point origin=p.getFirst();
            for(int i=1;i<p.size()-1;i++){
                vertex(buffer,entry,origin,x,z,face.y,face.color,light,overlay,0,1,0);vertex(buffer,entry,p.get(i),x,z,face.y,face.color,light,overlay,0,1,0);
                vertex(buffer,entry,p.get(i+1),x,z,face.y,face.color,light,overlay,0,1,0);vertex(buffer,entry,origin,x,z,face.y,face.color,light,overlay,0,1,0);
            }
            if(face.y==.25f)for(int i=0;i<p.size();i++){
                Point a=p.get(i),b=p.get((i+1)%p.size());
                float dx=b.x-a.x,dz=b.z-a.z,length=(float)Math.hypot(dx,dz);
                // 裁切可在方格角点生成重复顶点，零长度边没有侧面；顺时针边的外法线为 (-dz,0,dx)。
                if(length==0)continue;
                float nx=-dz/length,nz=dx/length;
                vertex(buffer,entry,a,x,z,0,0xff282e36,light,overlay,nx,0,nz);vertex(buffer,entry,b,x,z,0,0xff282e36,light,overlay,nx,0,nz);
                vertex(buffer,entry,b,x,z,.25f,0xff343c46,light,overlay,nx,0,nz);vertex(buffer,entry,a,x,z,.25f,0xff343c46,light,overlay,nx,0,nz);
            }
        }
        boolean lampsOn=lampsOn(entity.getWorld(),delta);
        List<Lamp> lamps=lampMesh(x,z);
        for(Lamp lamp:lamps){
            renderLampPart(buffer,entry,lamp.base,x,z,LAMP_BOTTOM,LAMP_TOP,LAMP_COLOR,light,overlay);
            if(!lampsOn)renderLampPart(buffer,entry,lamp.lens,x,z,LAMP_TOP,LENS_TOP,LENS_OFF_COLOR,light,overlay);
        }
        if(lampsOn&&!lamps.isEmpty()){
            // 切换图层可能提交前一个缓冲；普通灯座全部画完后才获取自发光缓冲，避免混层。
            var lensBuffer=consumers.getBuffer(RenderLayer.getEntityTranslucentEmissive(TEXTURE));
            for(Lamp lamp:lamps)renderLampPart(lensBuffer,entry,lamp.lens,x,z,LAMP_TOP,LENS_TOP,
                    LENS_ON_COLOR,LightmapTextureManager.MAX_LIGHT_COORDINATE,overlay);
        }
    }
    /**
     * 在客户端渲染线程将裁切后的凸轮廓挤出为低矮灯体，顶面和侧面均提交正确法线。
     * net.minecraft.client.render.VertexConsumer 使用调用方指定的普通或自发光层；
     * 满亮度只传给开启的灯芯，不影响灯座、黄环、H 或周围世界的方块光照。
     */
    private static void renderLampPart(VertexConsumer buffer,MatrixStack.Entry entry,List<Point> points,
                                       int x,int z,float bottom,float top,int color,int light,int overlay){
        if(points.size()<3)return;
        Point origin=points.getFirst();
        for(int i=1;i<points.size()-1;i++){
            vertex(buffer,entry,origin,x,z,top,color,light,overlay,0,1,0);
            vertex(buffer,entry,points.get(i),x,z,top,color,light,overlay,0,1,0);
            vertex(buffer,entry,points.get(i+1),x,z,top,color,light,overlay,0,1,0);
            vertex(buffer,entry,origin,x,z,top,color,light,overlay,0,1,0);
        }
        for(int i=0;i<points.size();i++){
            Point a=points.get(i),b=points.get((i+1)%points.size());
            float dx=b.x-a.x,dz=b.z-a.z,length=(float)Math.hypot(dx,dz);
            if(length==0)continue;
            float nx=-dz/length,nz=dx/length;
            vertex(buffer,entry,a,x,z,bottom,color,light,overlay,nx,0,nz);
            vertex(buffer,entry,b,x,z,bottom,color,light,overlay,nx,0,nz);
            vertex(buffer,entry,b,x,z,top,color,light,overlay,nx,0,nz);
            vertex(buffer,entry,a,x,z,top,color,light,overlay,nx,0,nz);
        }
    }
    /**
     * 向 net.minecraft.client.render.VertexConsumer 写入完整受光顶点：白色纹理保留原配色，
     * light 使用入口的天空光与方块光，normal 经 net.minecraft.client.util.math.MatrixStack.Entry 变换。
     * 主体、灯座和熄灭的灯芯随环境变暗；仅开启的灯芯由调用方提供满亮度。
     */
    private static void vertex(VertexConsumer buffer,MatrixStack.Entry entry,Point p,int x,int z,float y,int color,int light,int overlay,float nx,float ny,float nz){
        buffer.vertex(entry.getPositionMatrix(),p.x-x+.5f,y,p.z-z+.5f).color(color)
                .texture(.5f,.5f).overlay(overlay).light(light).normal(entry,nx,ny,nz);
    }
}
