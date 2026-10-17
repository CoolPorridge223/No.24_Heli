/** 文件：胸槽伞包与肩带特征；端侧：CLIENT；物品：no_24_heli:parachute。 */
package org.DJB.no_24_heli.client.render;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.registry.ModParachutes;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EquipmentSlot;

/**
 * 职责：原版玩家模型上显示伞包，站立、蹲下、乘伞时跟随身体骨骼。
 * 引用：net.minecraft.client.render.entity.feature.FeatureRenderer 保留原版玩家动画和皮肤。
 * 生命周期：Fabric 特征注册回调创建；端侧仅渲染线程；未穿戴或隐身不绘制。
 * 排查：包错位查 body.rotate，黑纹查 pack.png；矩阵必须 push/pop 成对。
 */
@Environment(EnvType.CLIENT)
public final class ParachutePackRenderer extends FeatureRenderer<AbstractClientPlayerEntity,
        PlayerEntityModel<AbstractClientPlayerEntity>> {
    /** 以原版模型像素为单位，包在背部，前方两条安全带穿过胸部。 */
    private final ModelPart pack;

    /** net.minecraft.client.model.ModelPart 只建立几何，不读取服务端状态。 */
    public ParachutePackRenderer(PlayerEntityRenderer context) {
        super(context);
        ModelData data = new ModelData();
        data.getRoot().addChild("pack", ModelPartBuilder.create().uv(0, 0)
                .cuboid(-3.5f, .5f, 2.2f, 7, 10, 3.5f)
                .uv(0, 18).cuboid(-3.2f, -.3f, -2.6f, 1.1f, 11, .7f)
                .cuboid(2.1f, -.3f, -2.6f, 1.1f, 11, .7f)
                .cuboid(-4.2f, 9, -2.7f, 8.4f, 1.2f, 5.4f), ModelTransform.NONE);
        pack = TexturedModelData.of(data, 32, 32).createModel().getChild("pack");
    }

    /** 仅胸槽装备伞包时绘制，net.minecraft.client.model.ModelPart#rotate 跟随身体姿态。 */
    @Override public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light,
            AbstractClientPlayerEntity player, float limbAngle, float limbDistance, float delta,
            float animation, float headYaw, float headPitch) {
        if (player.isInvisible() || !player.getEquippedStack(EquipmentSlot.CHEST).isOf(ModParachutes.PARACHUTE)) return;
        matrices.push(); getContextModel().body.rotate(matrices);
        pack.render(matrices, consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(
                No_24_heli.id("textures/entity/parachute_pack.png"))), light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }
}
