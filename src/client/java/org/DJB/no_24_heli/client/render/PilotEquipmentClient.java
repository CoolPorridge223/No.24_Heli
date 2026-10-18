/** 文件：专用头槽客户端同步及目镜画面；端侧：CLIENT；网络：pilot_equipment。 */
package org.DJB.no_24_heli.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.DJB.no_24_heli.network.PilotEquipmentPayload;

/**
 * 职责：接收装备外观、绘制日间减光并为原版光照计算提供夜视强度，不写药水状态或游戏亮度选项。
 * 引用 Fabric 客户端网络与实体加载事件解决追踪包先于实体生成的顺序；DrawContext 绘制减光。
 * 生命周期：客户端初始化、收到快照与每帧渲染；只在客户端主线程运行。
 * 排查：夜视残留查 hasGoggles；洞穴发黑查天空光权重；跨服外观查断线清空 pending。
 */
@Environment(EnvType.CLIENT)
public final class PilotEquipmentClient {
    /** 尚未生成对应玩家实体时暂存一次快照，断线清空。 */
    private static final Map<UUID, PilotEquipmentPayload> PENDING = new HashMap<>();
    /** 日间室外最多减光 18%，不遮挡 HUD，不修改用户亮度设置。 */
    private static final float DAY_DIM = .18f;

    /** 仅静态渲染工具。 */
    private PilotEquipmentClient() {}

    /** Fabric 网络调度到客户端主线程；玩家模型仅通过特征事件添加，不替换皮肤渲染器。 */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(PilotEquipmentPayload.ID, (packet, context) ->
                context.client().execute(() -> {
                    PENDING.put(packet.player(), packet);
                    if (context.client().world != null) {
                        PlayerEntity player = context.client().world.getPlayerByUuid(packet.player());
                        if (player != null) apply(player);
                    }
                }));
        ClientEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (entity instanceof PlayerEntity player) apply(player);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> PENDING.clear());
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer player) helper.register(new PilotEquipmentRenderer(player));
        });
    }

    /** 服务端快照边界检查槽类型，未生成的玩家保留到 ENTITY_LOAD；应用后删除临时缓存。 */
    private static void apply(PlayerEntity player) {
        PilotEquipmentPayload packet = PENDING.remove(player.getUuid());
        if (packet == null) return;
        if (!PilotEquipment.accepts(PilotEquipment.HEADSET, packet.headset())
                || !PilotEquipment.accepts(PilotEquipment.GOGGLES, packet.goggles()))
            throw new IllegalArgumentException("服务端发送了非法飞行装备快照");
        player.getInventory().setStack(PilotEquipment.HEADSET, packet.headset());
        player.getInventory().setStack(PilotEquipment.GOGGLES, packet.goggles());
    }

    /** 日落/日出各用 1000 tick 平滑过渡，无天空光维度保持夜视；读取时间而非客户端缓存 isNight。 */
    public static float nightStrength(PlayerEntity player) {
        if (!PilotEquipment.hasGoggles(player) || player.isSpectator()) return 0;
        var world = player.getWorld();
        if (!world.getDimension().hasSkyLight()) return 1;
        long time = Math.floorMod(world.getTimeOfDay(), 24000);
        return time < 12000 ? 0 : time < 13000 ? (time - 12000) / 1000f
                : time <= 23000 ? 1 : (24000 - time) / 1000f;
    }

    /** HUD 绘制之前压暗世界画面；天空光为零时不压暗，背包及 HUD 仍按原亮度显示。 */
    public static void drawDayFilter(DrawContext context) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null || !PilotEquipment.hasGoggles(client.player)
                || client.player.isSpectator()) return;
        float sky = client.world.getLightLevel(LightType.SKY, client.gameRenderer.getCamera().getBlockPos()) / 15f;
        float rain = 1 - client.world.getRainGradient(1) * .65f;
        int alpha = MathHelper.clamp(Math.round(255 * DAY_DIM * (1 - nightStrength(client.player)) * sky * rain), 0, 255);
        if (alpha > 0) context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), alpha << 24);
    }
}
