package org.DJB.no_24_heli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.*;

/** 服务端飞控配置；界面切换立即生效，手动修改文件需重启。 */
public final class HeliConfig {
    public enum Difficulty { CASUAL, MAMBA }
    public Difficulty difficulty = Difficulty.CASUAL;
    public boolean explosionBlockDamage = true;
    public boolean wind = true;
    public boolean rotorPreSpin = true;
    public double fuelCapacity = 1200;
    public double explosionPower = 5;
    public static HeliConfig INSTANCE = new HeliConfig();
    public boolean hardcore() { return difficulty == Difficulty.MAMBA; }
    public int spoolTicks() { return rotorPreSpin ? (hardcore() ? 240 : 100) : 1; }
    /** 设置界面写入成功后才确认；服务端控制调用权限。 */
    public static boolean save() {
        try {
            Files.writeString(FabricLoader.getInstance().getConfigDir().resolve("no_24_heli.json"),new GsonBuilder().setPrettyPrinting().create().toJson(INSTANCE));
            return true;
        } catch(Exception e) { No_24_heli.LOGGER.error("直升机配置保存失败",e); return false; }
    }
    /** 保存成功后更新已加载载具；未加载载具在下次 tick 根据保存的模式补应用。 */
    public static boolean changeDifficulty(net.minecraft.server.MinecraftServer server,boolean mamba){
        Difficulty old=INSTANCE.difficulty;
        INSTANCE.difficulty=mamba?Difficulty.MAMBA:Difficulty.CASUAL;
        if(!save()){INSTANCE.difficulty=old;return false;}
        if(old!=INSTANCE.difficulty)for(var world:server.getWorlds())for(var entity:world.iterateEntities())
            if(entity instanceof HelicopterEntity heli)heli.applyDifficulty(mamba);
        return true;
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
