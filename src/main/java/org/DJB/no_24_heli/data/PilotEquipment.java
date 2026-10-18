/** 文件：专用头部装备槽定义与外观同步；端侧：COMMON；网络：pilot_equipment。 */
package org.DJB.no_24_heli.data;

import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.EnchantmentEffectComponentTypes;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.DJB.no_24_heli.item.PilotEquipmentItem;
import org.DJB.no_24_heli.network.PilotEquipmentPayload;
import org.DJB.no_24_heli.registry.ModItems;

/**
 * 职责：定义两个原版背包扩展槽，向本人及追踪者发送穿戴外观，不改变原版护甲槽。
 * 引用：Fabric EntityTrackingEvents 处理新进入视野；ServerTickEvents 检查装备变化；
 * net.minecraft.entity.player.PlayerInventory 承担存档、复制、死亡掉落与清空。
 * 生命周期：ModItems 注册一次；逻辑服务端线程；弱键快照不延长已退出玩家生命周期。
 * 排查：远端不显示查追踪事件与 S2C；丢物查背包 Mixin；不能让客户端回传任意槽内容。
 */
public final class PilotEquipment {
    /** 原版 0–40 后追加；屏幕原有 0–45 后追加，不移动既有编号。 */
    public static final int HEADSET = 41, GOGGLES = 42, FIRST_SCREEN_SLOT = 46;
    /** 玩家存档 Inventory 列表的独立编号，避开原版 0–35、100–103、150。 */
    public static final int NBT_SLOT = 200;
    /** 最近已发的两个槽快照，ItemStack 必须复制以发现原位组件修改。 */
    private static final Map<ServerPlayerEntity, PilotEquipmentPayload> SENT = new WeakHashMap<>();

    /** 仅静态工具，不创建实例。 */
    private PilotEquipment() {}

    /** net.minecraft.item.ItemStack 类型与数量检查，空槽可清空；用于存档及创造包边界。 */
    public static boolean accepts(int slot, ItemStack stack) {
        return stack.isEmpty() || stack.getCount() == 1 && stack.getItem() instanceof PilotEquipmentItem item
                && item.inventorySlot() == slot;
    }

    /** 与原版护甲槽相同的绑定诅咒取下规则；不改动其他药水或附魔。 */
    public static boolean canRemove(PlayerEntity player, ItemStack stack) {
        return player.isCreative() || !EnchantmentHelper.hasAnyEnchantmentsWith(stack,
                EnchantmentEffectComponentTypes.PREVENT_ARMOR_CHANGE);
    }

    /** 专用目镜槽和兼容的原头槽均可启用视觉效果，耳麦自身没有目镜效果。 */
    public static boolean hasGoggles(PlayerEntity player) {
        return player.getInventory().getStack(GOGGLES).isOf(ModItems.PILOT_GOGGLES)
                || player.getEquippedStack(EquipmentSlot.HEAD).isOf(ModItems.PILOT_GOGGLES);
    }

    /** 从 net.minecraft.entity.player.PlayerInventory 复制两件装备，供本次同步使用。 */
    private static PilotEquipmentPayload snapshot(PlayerEntity player) {
        return new PilotEquipmentPayload(player.getUuid(), player.getInventory().getStack(HEADSET).copy(),
                player.getInventory().getStack(GOGGLES).copy());
    }

    /**
     * 功能：注册 S2C 类型及穿戴变化监听，所有物品变更仍走原版容器/使用包。
     * 端侧：逻辑服务端；副作用：仅装备变化时发送快照，开始追踪时立即发送当前值。
     */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(PilotEquipmentPayload.ID, PilotEquipmentPayload.CODEC);
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (entity instanceof PlayerEntity player) ServerPlayNetworking.send(viewer, snapshot(player));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                PilotEquipmentPayload current = snapshot(player), previous = SENT.get(player);
                if (previous != null && ItemStack.areEqual(previous.headset(), current.headset())
                        && ItemStack.areEqual(previous.goggles(), current.goggles())) continue;
                SENT.put(player, current);
                ServerPlayNetworking.send(player, current);
                for (ServerPlayerEntity viewer : PlayerLookup.tracking(player))
                    ServerPlayNetworking.send(viewer, current);
            }
        });
    }
}
