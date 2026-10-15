package org.DJB.no_24_heli.client;

import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.*;
import net.minecraft.client.util.math.MatrixStack;
import org.DJB.no_24_heli.*;
import java.util.*;

/** 六十四边圆盘按世界方格裁切缓存，厚底座、黄色边环和中央白色大 H 使用真实网格。 */
public final class HelipadRenderer implements BlockEntityRenderer<HelipadBlockEntity> {
    private record Point(float x,float z){}
    private record Face(List<Point> points,float y,int color){}
    private static final Map<Integer,List<Face>> MESHES=new HashMap<>();
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
                result.add(new Face(cell(List.of(polar(a,4.85),polar(b,4.85),polar(b,4.62),polar(a,4.62)),x,z),.251f,0xffedc95d));
            }
            for(var h:List.of(rectangle(-1.8f,-2.1f,-1.15f,2.1f),rectangle(1.15f,-2.1f,1.8f,2.1f),rectangle(-1.15f,-.33f,1.15f,.33f)))
                result.add(new Face(cell(h,x,z),.252f,0xfff5f4e9));
            return List.copyOf(result);
        });
    }
    /** 使用无贴图彩色网格；上下表面与侧面错开标记层，避免共面闪烁。 */
    @Override public void render(HelipadBlockEntity entity,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light,int overlay){
        var state=entity.getCachedState();int x=state.get(HelipadBlock.OFFSET_X)-5,z=state.get(HelipadBlock.OFFSET_Z)-5;
        var buffer=consumers.getBuffer(RenderLayer.getDebugQuads());var entry=matrices.peek();
        for(var face:mesh(x,z)){
            var p=face.points;if(p.size()<3)continue;Point origin=p.getFirst();
            for(int i=1;i<p.size()-1;i++){
                vertex(buffer,entry,origin,x,z,face.y,face.color);vertex(buffer,entry,p.get(i),x,z,face.y,face.color);
                vertex(buffer,entry,p.get(i+1),x,z,face.y,face.color);vertex(buffer,entry,origin,x,z,face.y,face.color);
            }
            if(face.y==.25f)for(int i=0;i<p.size();i++){
                Point a=p.get(i),b=p.get((i+1)%p.size());
                vertex(buffer,entry,a,x,z,0,0xff282e36);vertex(buffer,entry,b,x,z,0,0xff282e36);
                vertex(buffer,entry,b,x,z,.25f,0xff343c46);vertex(buffer,entry,a,x,z,.25f,0xff343c46);
            }
        }
    }
    private static void vertex(VertexConsumer buffer,MatrixStack.Entry entry,Point p,int x,int z,float y,int color){
        buffer.vertex(entry.getPositionMatrix(),p.x-x+.5f,y,p.z-z+.5f).color(color);
    }
}
