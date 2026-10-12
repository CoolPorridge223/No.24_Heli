package org.DJB.no_24_heli.client;

import net.minecraft.client.model.*;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.DJB.no_24_heli.*;
import org.joml.Quaternionf;

/** 绳索独立按绳端到机腹的包围盒裁剪，悬挂摆动时绘制实际连接线。 */
public class RopeRenderer extends EntityRenderer<RopeSeatEntity>{
    private final ModelPart rope;
    public RopeRenderer(EntityRendererFactory.Context context){
        super(context);ModelData data=new ModelData();
        data.getRoot().addChild("rope",ModelPartBuilder.create().uv(128,128).cuboid(-.3f,0,-.3f,.6f,16,.6f),ModelTransform.NONE);
        rope=TexturedModelData.of(data,256,256).createModel().getChild("rope");
    }
    @Override public boolean shouldRender(RopeSeatEntity e,Frustum f,double x,double y,double z){
        var h=e.helicopter();return h!=null&&h.flight.rope&&f.isVisible(e.getBoundingBox().union(h.getBoundingBox()).expand(1));
    }
    @Override public void render(RopeSeatEntity e,float yaw,float delta,MatrixStack m,VertexConsumerProvider consumers,int light){
        var h=e.helicopter();if(h==null||!h.flight.rope)return;
        Vec3d d=h.getLerpedPos(delta).subtract(e.getLerpedPos(delta));float length=(float)d.length();if(length<.01)return;
        m.push();m.multiply(new Quaternionf().rotationTo(0,1,0,(float)(d.x/length),(float)(d.y/length),(float)(d.z/length)));
        m.scale(1,length,1);rope.render(m,consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(e))),light,OverlayTexture.DEFAULT_UV);m.pop();
    }
    @Override public Identifier getTexture(RopeSeatEntity e){return No_24_heli.id("textures/entity/heli_0.png");}
}
