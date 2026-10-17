/** 文件：成就图标物品注册与服务端解锁入口；端侧：COMMON；命名空间：no_24_heli。 */
package org.DJB.no_24_heli.registry;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：注册两个专用成就图标，并把已确认的游戏事件交给原版进度系统持久化。
 * 引用：net.minecraft.registry.Registry 注册图标；ServerPlayerEntity#getAdvancementTracker
 * 保存每个玩家首次完成状态，数据包使用 minecraft:impossible 防止库存误解锁。
 * 生命周期：主入口初始化图标，事故/成功涂装/成功挂伞的服务端事件调用 grant。
 * 端侧/线程：只在逻辑服务端主线程授予；客户端只接收原版进度和 toast 数据。
 * 排查：不解锁查触发事件和数据包 ID；图标紫黑查模型路径；重复事件由原版 criterion 去重。
 */
public final class ModAdvancements {
    /** 三个进度的稳定存档 ID，修改名称文字时不能修改这些 ID。 */
    public static final Identifier LEGEND_NEVER_FALLS = No_24_heli.id("legend_never_falls");
    public static final Identifier TRIBUTE_TO_LEGEND = No_24_heli.id("tribute_to_legend");
    public static final Identifier DIFFERENT_ENDING = No_24_heli.id("different_ending");

    /** 无实例，解锁依据各事件的实际玩家，不维护另一份首次记录。 */
    private ModAdvancements() {}

    /** 专用图标不加入生存配方或创造物品组，仅供原版进度显示 ItemStack。 */
    public static void initialize() {
        Registry.register(Registries.ITEM, No_24_heli.id("legend_badge"), new Item(new Item.Settings()));
        Registry.register(Registries.ITEM, No_24_heli.id("sunrise_badge"), new Item(new Item.Settings()));
    }

    /**
     * net.minecraft.advancement.PlayerAdvancementTracker#grantCriterion 持久化唯一 first 条件。
     * 只在实际成功事件之后调用，已获得时不会重复 toast 或广播；数据包移除进度时记录原因。
     */
    public static void grant(ServerPlayerEntity player, Identifier id) {
        var entry = player.getServer().getAdvancementLoader().get(id);
        if (entry == null) {
            No_24_heli.LOGGER.warn("无法解锁进度，服务器数据包中缺少 {}", id);
            return;
        }
        player.getAdvancementTracker().grantCriterion(entry, "first");
    }
}
