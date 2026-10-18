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
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.entity.player.PlayerEntity;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.DJB.no_24_heli.network.PilotEquipmentPayload;

/**
 * 职责：接收装备外观，仅在本地玩家第一人称提供夜间夜视强度，不写药水状态或游戏亮度选项。
 * 引用 Fabric 客户端网络与实体加载事件解决追踪包先于实体生成的顺序；MinecraftClient 判断当前视角。
 * 生命周期：客户端初始化、收到快照与每帧渲染；只在客户端主线程运行。
 * 排查：夜视残留查装备和透视条件；跨服外观查断线清空 pending。
 */
@Environment(EnvType.CLIENT)
public final class PilotEquipmentClient {
    /** 尚未生成对应玩家实体时暂存一次快照，断线清空。 */
    private static final Map<UUID, PilotEquipmentPayload> PENDING = new HashMap<>();

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

    /** 本地第一人称下日落/日出各用 1000 tick 平滑过渡，无天空光维度保持夜视；其他视角不启用目镜效果。 */
    public static float nightStrength(PlayerEntity player) {
        var client = MinecraftClient.getInstance();
        if (player != client.player || client.getCameraEntity() != player
                || !client.options.getPerspective().isFirstPerson()
                || !PilotEquipment.hasGoggles(player) || player.isSpectator()) return 0;
        var world = player.getWorld();
        if (!world.getDimension().hasSkyLight()) return 1;
        long time = Math.floorMod(world.getTimeOfDay(), 24000);
        return time < 12000 ? 0 : time < 13000 ? (time - 12000) / 1000f
                : time <= 23000 ? 1 : (24000 - time) / 1000f;
    }

}
