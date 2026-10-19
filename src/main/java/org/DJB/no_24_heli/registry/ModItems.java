/** 文件：通用装备物品注册；端侧：COMMON；注册名：no_24_heli:pilot_headset。 */
package org.DJB.no_24_heli.registry;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.item.PilotEquipmentItem;
import org.DJB.no_24_heli.data.PilotEquipment;

/**
 * 职责：集中注册通用装备，已有直升机和伞包注册保持各自原入口。
 * 引用：net.minecraft.registry.Registry 将物品加入 net.minecraft.registry.Registries.ITEM。
 * 生命周期：主入口初始化时调用一次 register；COMMON，模组加载线程。
 * 排查：物品缺失先查入口调用与注册名；重复注册查是否调用两次；创造栏另由主入口列出。
 */
public final class ModItems {
    /** 头部槽位耳麦与一体化墨镜，单件堆叠，不增加防御值。 */
    public static final Item PILOT_HEADSET = new PilotEquipmentItem(PilotEquipment.HEADSET);
    /** 与耳麦独立的飞行员目镜，第一及第三人称夜视仅影响佩戴者画面，不改变日间亮度。 */
    public static final Item PILOT_GOGGLES = new PilotEquipmentItem(PilotEquipment.GOGGLES);

    /** 注册表工具不需要实例。 */
    private ModItems() {}

    /** 功能：在注册表冻结前调用 net.minecraft.registry.Registry#register，完成物品 ID 绑定。 */
    public static void register() {
        Registry.register(Registries.ITEM, No_24_heli.id("pilot_headset"), PILOT_HEADSET);
        Registry.register(Registries.ITEM, No_24_heli.id("pilot_goggles"), PILOT_GOGGLES);
        PilotEquipment.register();
    }
}
