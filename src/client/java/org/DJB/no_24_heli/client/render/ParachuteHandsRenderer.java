/** 文件：第一人称趴姿飞行及开伞时的世界身体与手脚；端侧：CLIENT；实体：no_24_heli:parachute。 */
package org.DJB.no_24_heli.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.TexturedModelData;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerModelPart;
import org.DJB.no_24_heli.entity.ParachuteEntity;

/**
 * 职责：补绘第一人称被原版隐藏的躯干与四肢，跳机、趴姿和握绳与第三人称共用姿态。
 * 引用：net.minecraft.client.render.entity.model.PlayerEntityModel 复用原版手臂和袖层 UV，
 * net.minecraft.client.MinecraftClient 只读当前相机及视角，不改变玩家、相机或服务端物理。
 * 生命周期：伞实体在未旋转的局部原点调用；标准与纤细模型各缓存一份，仅客户端渲染线程使用。
 * 排查：手绳分离查 ParachutePose 的握点和轴转换；重复手臂查第一人称条件与手持覆盖层取消。
 */
@Environment(EnvType.CLIENT)
public final class ParachuteHandsRenderer {
    /** 与原版玩家同尺寸的手臂及外套层，只构建一次，不依赖上一帧人物模型状态。 */
    private static final PlayerEntityModel<AbstractClientPlayerEntity> REGULAR = createModel(false), SLIM = createModel(true);

    /** 无实例，缓存仅在客户端渲染线程使用。 */
    private ParachuteHandsRenderer() {}

    /** net.minecraft.client.render.entity.model.PlayerEntityModel 提供标准/纤细骨骼和 64×64 皮肤映射。 */
    private static PlayerEntityModel<AbstractClientPlayerEntity> createModel(boolean slim) {
        return new PlayerEntityModel<>(TexturedModelData.of(
                PlayerEntityModel.getTexturedModelData(Dilation.NONE, slim), 64, 64).createModel(), slim);
    }

    /**
     * 仅为当前第一人称乘员绘制不含头部的世界身体；转头不会让手掌脱离吊绳，第三人称由人物模型负责。
     * net.minecraft.client.model.ModelPart#render 接收原版皮肤与环境光，按玩家设置绘制左右袖层。
     */
    public static void render(ParachuteEntity canopy, float delta, MatrixStack matrices,
            VertexConsumerProvider consumers, int light) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!(canopy.getFirstPassenger() instanceof AbstractClientPlayerEntity player)
                || client.getCameraEntity() != player || !client.options.getPerspective().isFirstPerson()
                || !player.isAlive() || player.isInvisible()) return;
        PlayerEntityModel<AbstractClientPlayerEntity> model = player.getSkinTextures().model() == SkinTextures.Model.SLIM
                ? SLIM : REGULAR;
        ParachutePose.poseRider(model, canopy, delta);
        matrices.push();
        var offset = canopy.entryOffset(delta); matrices.translate(offset.x, offset.y, offset.z);
        double pivot = ParachutePose.bodyPivot(canopy, delta);
        matrices.translate(0, pivot, 0); matrices.multiply(ParachutePose.bodyRotation(canopy, delta));
        float scale = ParachutePose.bodyScale(canopy, delta); matrices.scale(scale, scale, scale);
        matrices.translate(0, -pivot, 0); ParachutePose.applyPlayerModel(matrices, player);
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(player.getSkinTextures().texture()));
        model.rightArm.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        model.leftArm.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        model.body.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        model.rightLeg.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        model.leftLeg.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        if (player.isPartVisible(PlayerModelPart.RIGHT_SLEEVE))
            model.rightSleeve.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        if (player.isPartVisible(PlayerModelPart.LEFT_SLEEVE))
            model.leftSleeve.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        if (player.isPartVisible(PlayerModelPart.JACKET))
            model.jacket.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        if (player.isPartVisible(PlayerModelPart.RIGHT_PANTS_LEG))
            model.rightPants.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        if (player.isPartVisible(PlayerModelPart.LEFT_PANTS_LEG))
            model.leftPants.render(matrices, buffer, light, OverlayTexture.DEFAULT_UV);
        matrices.pop();
    }
}
