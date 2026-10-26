/** 文件：跳伞物品、实体和网络注册；端侧：COMMON。 */
package org.DJB.no_24_heli.registry;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.ParachuteEntity;
import org.DJB.no_24_heli.item.ParachuteItem;
import org.DJB.no_24_heli.item.ParachuteRepairRecipe;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.SpecialRecipeSerializer;
import org.DJB.no_24_heli.network.ParachutePayload;
import org.DJB.no_24_heli.network.ParachuteCancelPayload;

/**
 * 职责：集中注册新跳伞系统，避免向主入口继续添加业务逻辑。
 * 引用：net.minecraft.registry.Registry 注册内容；Fabric 网络 API 只调度到服务端主线程。
 * 生命周期：No_24_heli 初始化时调用一次；端侧 COMMON，物理只在逻辑服务端。
 * 排查：物品缺失查 initialize 调用，收不到包查 C2S 注册及实体乘骑关系。
 */
public final class ModParachutes {
    /** 胸甲槽可复用伞包，无护甲属性。 */
    public static final Item PARACHUTE = Registry.register(Registries.ITEM,
            No_24_heli.id("parachute"), new ParachuteItem());
    /** 动态皮革修复配方，不会清除附魔及叠伞状态。 */
    public static final RecipeSerializer<ParachuteRepairRecipe> REPAIR_SERIALIZER = Registry.register(
            Registries.RECIPE_SERIALIZER, No_24_heli.id("parachute_repair"),
            new SpecialRecipeSerializer<>(ParachuteRepairRecipe::new));
    /** 注册尺寸作为默认身体大小；载人阶段随人物姿态旋转，伞翼不作为巨大实心碰撞箱。 */
    public static final EntityType<ParachuteEntity> CANOPY = Registry.register(Registries.ENTITY_TYPE,
            No_24_heli.id("parachute"), EntityType.Builder.<ParachuteEntity>create(
                    ParachuteEntity::new, SpawnGroup.MISC).dimensions(.6f, 1.8f)
                    .maxTrackingRange(12).trackingTickInterval(1).build());

    /** 无实例，注册入口统一由模组生命周期调用。 */
    private ModParachutes() {}

    /**
     * 注册 net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking 的输入边界。
     * 包内不含实体 ID，始终使用发包者自己的乘骑；有限数及资格由实体再次校验。
     * 仅服务端主线程改变状态；非法数值拒绝并记录，防止 NaN 污染世界坐标。
     */
    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(ParachuteCancelPayload.ID,ParachuteCancelPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ParachuteCancelPayload.ID,(packet,context)->
                context.server().execute(()->{
                    if(context.player().getVehicle() instanceof ParachuteEntity canopy)
                        canopy.cancelCreativeFlight(context.player());
                }));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var player : server.getPlayerManager().getPlayerList())
                if (player.fallDistance > 2 && !player.horizontalCollision && ParachuteEntity.canStartFreefall(player))
                    ParachuteEntity.startFreefall(player, null, -1);
        });
        PayloadTypeRegistry.playC2S().register(ParachutePayload.ID, ParachutePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ParachutePayload.ID, (packet, context) ->
                context.server().execute(() -> {
                    if (!packet.valid()) {
                        No_24_heli.LOGGER.warn("拒绝非法跳伞输入：{}", context.player().getUuid());
                        return;
                    }
                    if (context.player().getVehicle() instanceof ParachuteEntity canopy) {
                        canopy.control(context.player(), packet);
                    } else if (packet.deploy()) {
                        ParachuteEntity.deploy(context.player());
                    }
                }));
    }
}
