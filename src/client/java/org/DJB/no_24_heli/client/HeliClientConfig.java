/** 文件：直升机个人显示偏好，仅保存到本机；端侧：CLIENT；不注册网络通道。 */
package org.DJB.no_24_heli.client;

import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：持久化个人轨迹开关，与服务端 HeliConfig 分开，集成服务器也不共享此开关。
 * 引用 net.fabricmc.loader.api.FabricLoader 定位本机配置目录，不访问世界或玩家存档。
 * 生命周期：客户端初始化加载，设置面板点击后立即保存；仅客户端主线程。
 * 排查：重启未保留查文件写入日志；多人互相影响时检查是否错误接入 SettingsPayload。
 */
@Environment(EnvType.CLIENT)
public final class HeliClientConfig {
    /** 本机偏好默认开启；仅本机渲染读取，佩戴目镜和乘机条件仍须单独满足。 */
    public boolean navigationPathVisible = true;
    /** 客户端唯一配置副本，不参与 NBT、服务端配置或网络同步。 */
    public static HeliClientConfig INSTANCE = new HeliClientConfig();

    /** 功能：返回本机专用文件；FabricLoader 配置目录不随服务器或维度改变，无写入副作用。 */
    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("no_24_heli_client.json");
    }

    /** 功能：启动时读取个人偏好，坏文件记录错误并保留默认；仅客户端初始化调用。 */
    public static void load() {
        try {
            if (!Files.exists(path())) return;
            var loaded = new GsonBuilder().create().fromJson(Files.readString(path()), HeliClientConfig.class);
            if (loaded == null) throw new IllegalArgumentException("个人显示配置为空");
            INSTANCE = loaded;
        } catch (Exception exception) {
            No_24_heli.LOGGER.error("直升机个人显示配置读取失败，保留默认设置", exception);
        }
    }

    /** 功能：立即保存本机偏好；失败返回 false，由面板恢复旧值；不调用任何网络 API。 */
    public static boolean save() {
        try {
            Files.writeString(path(), new GsonBuilder().setPrettyPrinting().create().toJson(INSTANCE));
            return true;
        } catch (Exception exception) {
            No_24_heli.LOGGER.error("直升机个人显示配置保存失败", exception);
            return false;
        }
    }
}
