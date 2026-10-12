package org.DJB.no_24_heli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

/** 服务端飞控配置；界面切换立即生效，手动修改文件需重启。 */
public final class HeliConfig {
    public enum Difficulty { CASUAL, MAMBA, ZOBAYAN }
    public Difficulty difficulty = Difficulty.CASUAL;
    public boolean explosionBlockDamage = true;
    public boolean wind = true;
    public boolean rotorPreSpin = true;
    public double fuelCapacity = 1200;
    public double explosionPower = 5;
    public static HeliConfig INSTANCE = new HeliConfig();
    public boolean hardcore() { return difficulty != Difficulty.CASUAL; }
    /** 佐巴扬模式沿用曼巴物理，但额外强制关闭全部辅助并施加持续飞行磨损。 */
    public boolean unassisted() { return difficulty == Difficulty.ZOBAYAN; }
    public int spoolTicks() { return rotorPreSpin ? (hardcore() ? 240 : 100) : 1; }
    /** 设置界面写入成功后才确认；服务端控制调用权限。 */
    public static boolean save() {
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("no_24_heli.json"),new GsonBuilder().setPrettyPrinting().create().toJson(INSTANCE));
            return true;
        } catch(Exception e) { No_24_heli.LOGGER.error("直升机配置保存失败",e); return false; }
    }
    /** 飞控取得本机驾驶员的模式副本，共享天气、燃油和爆炸配置仍由服务器管理。 */
    public static HeliConfig forMode(Difficulty mode){
        var config=new HeliConfig();config.difficulty=mode;
        config.explosionBlockDamage=INSTANCE.explosionBlockDamage;config.wind=INSTANCE.wind;
        config.rotorPreSpin=INSTANCE.rotorPreSpin;config.fuelCapacity=INSTANCE.fuelCapacity;
        config.explosionPower=INSTANCE.explosionPower;return config;
    }
    /** 配置非法时使用安全默认值，避免 NaN 进入物理积分。 */
    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("no_24_heli.json");
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(path)) {
                HeliConfig loaded = gson.fromJson(Files.readString(path), HeliConfig.class);
                if (loaded != null) INSTANCE = loaded;
            }
            if (INSTANCE.difficulty == null) INSTANCE.difficulty = Difficulty.CASUAL;
            if (!Double.isFinite(INSTANCE.fuelCapacity)) INSTANCE.fuelCapacity = 1200;
            if (!Double.isFinite(INSTANCE.explosionPower)) INSTANCE.explosionPower = 5;
            INSTANCE.fuelCapacity = Math.clamp(INSTANCE.fuelCapacity, 100, 30000);
            INSTANCE.explosionPower = Math.clamp(INSTANCE.explosionPower, 0, 12);
            Files.writeString(path, gson.toJson(INSTANCE));
        } catch (Exception e) { No_24_heli.LOGGER.error("直升机配置读取失败，采用有效默认值", e); }
    }
}
