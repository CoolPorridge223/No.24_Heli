package org.DJB.no_24_heli.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.*;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.DJB.no_24_heli.*;
import org.joml.Quaternionf;

/** 客户端按服务器十六段节点绘制弯曲绳形；各段矩阵独立还原，乘员由原版渲染。 */
@Environment(EnvType.CLIENT)
public class RopeRenderer extends EntityRenderer<RopeSeatEntity>{
    private final ModelPart rope;
    public RopeRenderer(EntityRendererFactory.Context context){
        super(context);ModelData data=new ModelData();
        data.getRoot().addChild("rope",ModelPartBuilder.create().uv(128,128).cuboid(-.3f,0,-.3f,.6f,16,.6f),ModelTransform.NONE);
        rope=TexturedModelData.of(data,256,256).createModel().getChild("rope");
    }
    @Override public boolean shouldRender(RopeSeatEntity e,Frustum f,double x,double y,double z){
        var h=e.helicopter();return h!=null&&h.flight.rope&&f.isVisible(e.ropeBounds().union(h.getBoundingBox()));
    }
    @Override public void render(RopeSeatEntity e,float yaw,float delta,MatrixStack m,VertexConsumerProvider consumers,int light){
        var h=e.helicopter();if(h==null||!h.flight.rope)return;
        Vec3d origin=e.getLerpedPos(delta);
        var buffer=consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(e)));
        for(int i=0;i<RopeSeatEntity.SEGMENTS;i++){
            Vec3d start=e.ropePoint(i+1,delta),d=e.ropePoint(i,delta).subtract(start);
            float length=(float)d.length();if(length<.001)continue;
            m.push();m.translate(start.x-origin.x,start.y-origin.y,start.z-origin.z);
            m.multiply(new Quaternionf().rotationTo(0,1,0,(float)(d.x/length),(float)(d.y/length),(float)(d.z/length)));
            m.scale(1,length,1);rope.render(m,buffer,light,OverlayTexture.DEFAULT_UV);m.pop();
        }
    }
    @Override public Identifier getTexture(RopeSeatEntity e){return No_24_heli.id("textures/entity/heli_0.png");}
}
