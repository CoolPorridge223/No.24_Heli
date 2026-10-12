package org.DJB.no_24_heli.client;

import net.minecraft.client.model.*;
import net.minecraft.client.render.*;
import net.minecraft.client.render.entity.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import org.DJB.no_24_heli.*;

/** 原生立方体机模：座舱、尾梁、主旋翼、尾桨、可收放起落架均独立建模。 */
public class HelicopterRenderer extends EntityRenderer<HelicopterEntity>{
    private final ModelPart model,rotor,tail,gear,rope,number;
    public HelicopterRenderer(EntityRendererFactory.Context context){
        super(context);shadowRadius=1.7f;
        ModelData data=new ModelData();ModelPartData root=data.getRoot();
        // 主体底部只保留一个壳面，去掉与机腹相交的重复长方体，消除底边闪烁。
        // 1.2.1：箱体自动UV宽度为2×(宽+深)。旧34×38箱体占144像素，越过128像素
        // 的机身色区而采到玻璃区。沿Z拆成两个无外露重叠面的19深箱体，UV宽度降为106。
        root.addChild("body",ModelPartBuilder.create().uv(0,0).cuboid(-17,-28,-18,34,21,19).uv(0,0).cuboid(-17,-28,1,34,21,19).uv(0,56).cuboid(-14,-33,-14,28,5,28).uv(128,0).cuboid(-16,-27,20,32,17,7),ModelTransform.NONE);
        root.addChild("windows",ModelPartBuilder.create().uv(128,0).cuboid(-17.08f,-26,1,.08f,12,18).cuboid(17,-26,1,.08f,12,18),ModelTransform.NONE);
        root.addChild("boom",ModelPartBuilder.create().uv(0,0).cuboid(-4,-24,-52,8,7,34).cuboid(-2,-39,-53,4,20,8).cuboid(-15,-23,-44,30,2,8),ModelTransform.NONE);
        root.addChild("engine",ModelPartBuilder.create().uv(128,128).cuboid(-8,-36,-15,16,7,20).cuboid(-2,-42,-1,4,7,4),ModelTransform.NONE);
        ModelPartData main=root.addChild("rotor",ModelPartBuilder.create().uv(128,128).cuboid(-55,-1,-2,110,1,4).cuboid(-2,-1,-55,4,1,110).cuboid(-4,-3,-4,8,3,8),ModelTransform.pivot(0,-42,0));
        root.addChild("tail",ModelPartBuilder.create().uv(128,128).cuboid(-.5f,-13,-1,1,26,2).cuboid(-.5f,-1,-13,1,2,26),ModelTransform.pivot(4,-32,-50));
        root.addChild("gear",ModelPartBuilder.create().uv(128,128).cuboid(-21,-4,-20,3,3,49).cuboid(18,-4,-20,3,3,49).cuboid(-20,-12,-10,2,8,2).cuboid(18,-12,-10,2,8,2).cuboid(-20,-12,16,2,8,2).cuboid(18,-12,16,2,8,2),ModelTransform.NONE);
        root.addChild("rope",ModelPartBuilder.create().uv(128,128).cuboid(-.3f,0,-.3f,.6f,16,.6f),ModelTransform.NONE);
        // 紫金编号先铺纯紫底牌遮住旧金色条纹，再在外侧绘制金色数字；各层相隔0.08像素，避免共面闪烁。
        root.addChild("number_plate",ModelPartBuilder.create().uv(0,0).cuboid(-17.20f,-25,-17,.12f,10,13).cuboid(17.08f,-25,-17,.12f,10,13),ModelTransform.NONE);
        ModelPartBuilder digits=ModelPartBuilder.create().uv(192,192);
        String[] rows={"1110101","0010101","1110111","1000001","1110001"};
        for(int row=0;row<5;row++)for(int col=0;col<7;col++)if(rows[row].charAt(col)=='1'){
            digits.cuboid(-17.32f,-24.5f+row*1.7f,-16+col*1.7f,.12f,1.7f,1.7f);
            digits.cuboid(17.20f,-24.5f+row*1.7f,-5.8f-col*1.7f,.12f,1.7f,1.7f);
        }
        root.addChild("number",digits,ModelTransform.NONE);
        model=TexturedModelData.of(data,256,256).createModel();number=model.getChild("number");rotor=model.getChild("rotor");tail=model.getChild("tail");gear=model.getChild("gear");rope=model.getChild("rope");
    }
    /** 绳索与旋翼计入视锥包围盒，机身离开屏幕时仍绘制可见绳段。 */
    @Override public boolean shouldRender(HelicopterEntity h,Frustum frustum,double x,double y,double z){
        return h.shouldRender(x,y,z)&&frustum.isVisible(h.getBoundingBox().expand(4).stretch(0,-h.ropeLength,0));
    }
    @Override public void render(HelicopterEntity h,float yaw,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light){
        // 第一人称提供清晰座舱 HUD 视野，避免机身实心模型遮挡驾驶员。
        var client=net.minecraft.client.MinecraftClient.getInstance();
        if(client.player!=null&&client.player.getVehicle()==h&&client.options.getPerspective().isFirstPerson())return;
        matrices.push();matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-MathHelper.lerp(delta,h.previousVisualPitch,h.visualPitch)));matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.lerp(delta,h.previousVisualRoll,h.visualRoll)));
        if(h.flight.rotor<60&&h.flight.rpm>.2)matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float)Math.sin((h.age+delta)*2)*(1-(float)h.flight.rotor/100)*2));
        matrices.scale(1,-1,1);
        rotor.yaw=(float)Math.toRadians(MathHelper.lerp(delta,h.previousRotorAngle,h.rotorAngle));tail.pitch=rotor.yaw*2;
        gear.pivotY=-9*(1-MathHelper.lerp(delta,h.previousGearExtension,h.gearExtension));rotor.visible=!h.flight.wreck&&h.flight.rotor>0;tail.visible=!h.flight.wreck&&h.flight.tail>0;rope.visible=false;
        number.visible=h.flight.paint==5;model.getChild("number_plate").visible=number.visible;
        var buffer=consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(h)));
        model.render(matrices,buffer,light,OverlayTexture.DEFAULT_UV,h.flight.wreck?0xff545454:0xffffffff);matrices.pop();
        if(h.flight.rope&&h.ropeLength>0&&!h.hasRopeCarrier()){matrices.push();matrices.scale(1,-h.ropeLength,1);rope.visible=true;rope.render(matrices,buffer,light,OverlayTexture.DEFAULT_UV);rope.visible=false;matrices.pop();}
        super.render(h,yaw,delta,matrices,consumers,light);
    }
    @Override public Identifier getTexture(HelicopterEntity h){return No_24_heli.id("textures/entity/heli_"+h.flight.paint+".png");}
}
