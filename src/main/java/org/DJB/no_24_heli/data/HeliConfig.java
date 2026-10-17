package org.DJB.no_24_heli.data;

import org.DJB.no_24_heli.No_24_heli;

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
    /** 平面规划单元边长，单位格，默认 2；合法范围 0.25～64，重启后生效。 */
    public double gridCellSize = 2D;
    /** 禁飞圆外附加安全余量，单位格，默认 2，范围 0～1024；机体规划半径另行加入。 */
    public double pathSafetyMargin = 2D;
    /** 包含起终点及全部扩张圆的规划矩形外余量，单位格，默认 16，范围 1～4096。 */
    public double pathBoundaryMargin = 16D;
    /** 单次规划允许分配的网格总数，默认一百万，超过时明确报告资源限制。 */
    public int pathMaxCells = 1_000_000;
    /** 单次 A* 允许访问的节点数，默认一百万，超过时明确报告资源限制。 */
    public int pathMaxNodes = 1_000_000;
    /** 自动驾驶巡航目标，km/h；默认75，可配置20～80，按20TPS换算。 */
    public double autopilotCruiseKmh = 75;
    /** 动态前视范围，单位格；默认8～30，仅服务端使用。 */
    public double autopilotLookMin = 8, autopilotLookMax = 30;
    /** 网格和搜索预算的配置硬上限，防止配置文件要求分配失控；不限制禁飞圆数量。 */
    private static final int MAX_PATH_RESOURCES = 2_000_000;
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
    /** 飞控取得本机驾驶员的模式副本；天气、燃油、爆炸和规划资源配置仍由服务器管理。 */
    public static HeliConfig forMode(Difficulty mode){
        var config=new HeliConfig();config.difficulty=mode;
        config.explosionBlockDamage=INSTANCE.explosionBlockDamage;config.wind=INSTANCE.wind;
        config.rotorPreSpin=INSTANCE.rotorPreSpin;config.fuelCapacity=INSTANCE.fuelCapacity;
        config.explosionPower=INSTANCE.explosionPower;
        config.gridCellSize=INSTANCE.gridCellSize;config.pathSafetyMargin=INSTANCE.pathSafetyMargin;
        config.pathBoundaryMargin=INSTANCE.pathBoundaryMargin;config.pathMaxCells=INSTANCE.pathMaxCells;
        config.pathMaxNodes=INSTANCE.pathMaxNodes;
        config.autopilotCruiseKmh=INSTANCE.autopilotCruiseKmh;
        config.autopilotLookMin=INSTANCE.autopilotLookMin;config.autopilotLookMax=INSTANCE.autopilotLookMax;
        return config;
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
            boolean invalidPathConfig = !Double.isFinite(INSTANCE.gridCellSize)
                    || INSTANCE.gridCellSize < 0.25D || INSTANCE.gridCellSize > 64D
                    || !Double.isFinite(INSTANCE.pathSafetyMargin)
                    || INSTANCE.pathSafetyMargin < 0D || INSTANCE.pathSafetyMargin > 1024D
                    || !Double.isFinite(INSTANCE.pathBoundaryMargin)
                    || INSTANCE.pathBoundaryMargin < 1D || INSTANCE.pathBoundaryMargin > 4096D
                    || INSTANCE.pathMaxCells < 1 || INSTANCE.pathMaxCells > MAX_PATH_RESOURCES
                    || INSTANCE.pathMaxNodes < 1 || INSTANCE.pathMaxNodes > MAX_PATH_RESOURCES;
            if (!Double.isFinite(INSTANCE.gridCellSize)) INSTANCE.gridCellSize = 2D;
            if (!Double.isFinite(INSTANCE.pathSafetyMargin)) INSTANCE.pathSafetyMargin = 2D;
            if (!Double.isFinite(INSTANCE.pathBoundaryMargin)) INSTANCE.pathBoundaryMargin = 16D;
            INSTANCE.gridCellSize = Math.clamp(INSTANCE.gridCellSize, 0.25D, 64D);
            INSTANCE.pathSafetyMargin = Math.clamp(INSTANCE.pathSafetyMargin, 0D, 1024D);
            INSTANCE.pathBoundaryMargin = Math.clamp(INSTANCE.pathBoundaryMargin, 1D, 4096D);
            INSTANCE.pathMaxCells = Math.clamp(INSTANCE.pathMaxCells, 1, MAX_PATH_RESOURCES);
            INSTANCE.pathMaxNodes = Math.clamp(INSTANCE.pathMaxNodes, 1, MAX_PATH_RESOURCES);
            if (invalidPathConfig) No_24_heli.LOGGER.warn(
                    "规划配置已修正：格长={}，安全余量={}，边界余量={}，网格预算={}，节点预算={}",
                    INSTANCE.gridCellSize, INSTANCE.pathSafetyMargin, INSTANCE.pathBoundaryMargin,
                    INSTANCE.pathMaxCells, INSTANCE.pathMaxNodes);
            boolean invalidFlight = !Double.isFinite(INSTANCE.autopilotCruiseKmh)
                    || INSTANCE.autopilotCruiseKmh < 20 || INSTANCE.autopilotCruiseKmh > 80
                    || !Double.isFinite(INSTANCE.autopilotLookMin) || INSTANCE.autopilotLookMin < 4
                    || INSTANCE.autopilotLookMin > 16 || !Double.isFinite(INSTANCE.autopilotLookMax)
                    || INSTANCE.autopilotLookMax < INSTANCE.autopilotLookMin || INSTANCE.autopilotLookMax > 48;
            if (!Double.isFinite(INSTANCE.autopilotCruiseKmh)) INSTANCE.autopilotCruiseKmh = 75;
            if (!Double.isFinite(INSTANCE.autopilotLookMin)) INSTANCE.autopilotLookMin = 8;
            if (!Double.isFinite(INSTANCE.autopilotLookMax)) INSTANCE.autopilotLookMax = 30;
            INSTANCE.autopilotCruiseKmh = Math.clamp(INSTANCE.autopilotCruiseKmh, 20, 80);
            INSTANCE.autopilotLookMin = Math.clamp(INSTANCE.autopilotLookMin, 4, 16);
            INSTANCE.autopilotLookMax = Math.clamp(INSTANCE.autopilotLookMax, INSTANCE.autopilotLookMin, 48);
            if (invalidFlight) No_24_heli.LOGGER.warn("自动驾驶巡航/前视配置已恢复到有效范围");
            Files.writeString(path, gson.toJson(INSTANCE));
        } catch (Exception e) { No_24_heli.LOGGER.error("直升机配置读取失败，采用有效默认值", e); }
    }
}
