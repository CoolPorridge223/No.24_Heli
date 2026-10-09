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
        root.addChild("body",ModelPartBuilder.create().uv(0,0).cuboid(-14,-26,-18,28,17,34).uv(0,56).cuboid(-12,-31,-14,24,5,26).uv(128,0).cuboid(-12,-25,16,24,14,5).uv(0,0).cuboid(-14,-10,-13,28,3,30),ModelTransform.NONE);
        root.addChild("windows",ModelPartBuilder.create().uv(128,0).cuboid(-14.2f,-25,0,1,12,15).cuboid(13.2f,-25,0,1,12,15),ModelTransform.NONE);
        root.addChild("boom",ModelPartBuilder.create().uv(0,0).cuboid(-4,-24,-52,8,7,34).cuboid(-2,-39,-53,4,20,8).cuboid(-15,-23,-44,30,2,8),ModelTransform.NONE);
        root.addChild("engine",ModelPartBuilder.create().uv(128,128).cuboid(-8,-36,-15,16,7,20).cuboid(-2,-42,-1,4,7,4),ModelTransform.NONE);
        ModelPartData main=root.addChild("rotor",ModelPartBuilder.create().uv(128,128).cuboid(-55,-1,-2,110,1,4).cuboid(-2,-1,-55,4,1,110).cuboid(-4,-3,-4,8,3,8),ModelTransform.pivot(0,-42,0));
        root.addChild("tail",ModelPartBuilder.create().uv(128,128).cuboid(-.5f,-13,-1,1,26,2).cuboid(-.5f,-1,-13,1,2,26),ModelTransform.pivot(4,-32,-50));
        root.addChild("gear",ModelPartBuilder.create().uv(128,128).cuboid(-18,-4,-20,3,3,44).cuboid(15,-4,-20,3,3,44).cuboid(-17,-12,-10,2,8,2).cuboid(15,-12,-10,2,8,2).cuboid(-17,-12,13,2,8,2).cuboid(15,-12,13,2,8,2),ModelTransform.NONE);
        root.addChild("rope",ModelPartBuilder.create().uv(128,128).cuboid(-.3f,0,-.3f,.6f,16,.6f),ModelTransform.NONE);
        ModelPartBuilder digits=ModelPartBuilder.create().uv(192,192);
        String[] rows={"1110101","0010101","1110111","1000001","1110001"};
        for(int row=0;row<5;row++)for(int col=0;col<7;col++)if(rows[row].charAt(col)=='1'){
            digits.cuboid(-14.16f,-24.5f+row*1.7f,-16+col*1.7f,.12f,1.7f,1.7f);
            digits.cuboid(14.04f,-24.5f+row*1.7f,-5.8f-col*1.7f,.12f,1.7f,1.7f);
        }
        root.addChild("number",digits,ModelTransform.NONE);
        model=TexturedModelData.of(data,256,256).createModel();number=model.getChild("number");rotor=model.getChild("rotor");tail=model.getChild("tail");gear=model.getChild("gear");rope=model.getChild("rope");
    }
    @Override public void render(HelicopterEntity h,float yaw,float delta,MatrixStack matrices,VertexConsumerProvider consumers,int light){
        // 第一人称提供清晰座舱 HUD 视野，避免机身实心模型遮挡驾驶员。
        var client=net.minecraft.client.MinecraftClient.getInstance();
        if(client.player!=null&&client.player.getVehicle()==h&&client.options.getPerspective().isFirstPerson())return;
        matrices.push();matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-h.visualPitch));matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(h.visualRoll));
        if(h.flight.rotor<60&&h.flight.rpm>.2)matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees((float)Math.sin((h.age+delta)*2)*(1-(float)h.flight.rotor/100)*2));
        matrices.scale(1,-1,1);
        rotor.yaw=(float)Math.toRadians(MathHelper.lerp(delta,h.previousRotorAngle,h.rotorAngle));tail.pitch=rotor.yaw*2;
        gear.pivotY=-9*(1-h.gearExtension);rotor.visible=!h.flight.wreck&&h.flight.rotor>0;tail.visible=!h.flight.wreck&&h.flight.tail>0;rope.visible=false;
        number.visible=h.flight.paint==5;
        var buffer=consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(h)));
        model.render(matrices,buffer,light,OverlayTexture.DEFAULT_UV,h.flight.wreck?0xff545454:0xffffffff);matrices.pop();
        if(h.flight.rope&&h.ropeLength>0){matrices.push();matrices.scale(1,-h.ropeLength,1);rope.visible=true;rope.render(matrices,buffer,light,OverlayTexture.DEFAULT_UV);rope.visible=false;matrices.pop();}
        super.render(h,yaw,delta,matrices,consumers,light);
    }
    @Override public Identifier getTexture(HelicopterEntity h){return No_24_heli.id("textures/entity/heli_"+h.flight.paint+".png");}
}
