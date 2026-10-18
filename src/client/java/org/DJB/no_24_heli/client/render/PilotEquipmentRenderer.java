/** 文件：玩家耳麦和目镜附加头部特征；端侧：CLIENT；物品：pilot_headset、pilot_goggles。 */
package org.DJB.no_24_heli.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.model.json.ModelTransformationMode;
import net.minecraft.client.util.math.MatrixStack;
import org.DJB.no_24_heli.data.PilotEquipment;

/**
 * 职责：把两个专用槽的物品附着到同一个头骨，保留皮肤、头盔和原版姿态。
 * 引用 net.minecraft.client.render.entity.feature.HeadFeatureRenderer#translate 保持 JSON head 坐标一致。
 * 生命周期：Fabric 玩家特征注册；客户端渲染线程；排查：错位查 head.rotate 和模型 head 变换。
 */
@Environment(EnvType.CLIENT)
public final class PilotEquipmentRenderer extends FeatureRenderer<AbstractClientPlayerEntity,
        PlayerEntityModel<AbstractClientPlayerEntity>> {
    /** net.minecraft.client.render.entity.PlayerEntityRenderer 提供当前玩家头部骨骼。 */
    public PilotEquipmentRenderer(PlayerEntityRenderer context) { super(context); }

    /** 只读取同步的真实装备，隐身玩家不暴露附加头部特征；矩阵 push/pop 成对。 */
    @Override public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light,
            AbstractClientPlayerEntity player, float limbAngle, float limbDistance, float delta,
            float animation, float headYaw, float headPitch) {
        if (player.isInvisible()) return;
        matrices.push();
        getContextModel().head.rotate(matrices);
        HeadFeatureRenderer.translate(matrices, false);
        for (int index = PilotEquipment.HEADSET; index <= PilotEquipment.GOGGLES; index++) {
            var stack = player.getInventory().getStack(index);
            if (!stack.isEmpty()) MinecraftClient.getInstance().getItemRenderer().renderItem(stack,
                    ModelTransformationMode.HEAD, light, OverlayTexture.DEFAULT_UV, matrices, consumers,
                    player.getWorld(), player.getId());
        }
        matrices.pop();
    }
}
