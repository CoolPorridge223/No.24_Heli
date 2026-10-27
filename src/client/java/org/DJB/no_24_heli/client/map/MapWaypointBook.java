package org.DJB.no_24_heli.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.data.HelipadState;
import org.DJB.no_24_heli.network.HelipadMapPayload;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.text.Text;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 职责：仅缓存服务端统一的停机坪目录，不记录普通导航点，不修改导航协议和目标高度。
 * 引用 net.minecraft.text.Text 生成本地化默认名称；停机坪来自服务端固定目录。
 * 生命周期：地图打开时读取旧缓存，在客户端主线程按服务端快照同步并移除旧普通导航点。
 * 排查：串档检查 ExploredMap 的来源/维度键；保存失败检查日志及配置目录权限。
 */
@Environment(EnvType.CLIENT)
public final class MapWaypointBook {
    /** 名称上限，单位为字符；与 net.minecraft.client.gui.widget.TextFieldWidget 共用。 */
    public static final int NAME_LIMIT = HelipadState.NAME_LIMIT;
    /** JSON 编解码器；仅处理本机配置，不参与网络同步。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 当前世界/维度的记录文件；不与世界权威存档混写。 */
    private final Path file;
    /** 读取损坏文件后禁止覆盖原件，玩家会收到保存失败提示。 */
    private boolean loadFailed;
    /** 本地仅缓存停机坪；名称和默认编号始终以服务端快照为准。 */
    private Stored stored = new Stored();

    /** 坐标以世界格为单位；保留旧 helipad 类型字段，用于识别并清理历史普通导航点。 */
    @Environment(EnvType.CLIENT)
    public record Entry(String id, int number, String name, double x, Integer y, double z, boolean helipad) {
        /** 自定义名称留空时使用稳定编号；net.minecraft.text.Text 负责随语言切换。 */
        public Text label() {
            return name.isBlank() ? Text.translatable("screen.no_24_heli.waypoint_default", number) : Text.literal(name);
        }
    }

    /** JSON 根对象沿用旧格式，停机坪编号由服务端分配，不再分配本机导航点编号。 */
    @Environment(EnvType.CLIENT)
    private static final class Stored {
        /** 同一来源和维度内的停机坪缓存，旧普通导航点在同步时清理。 */
        private List<Entry> points = new ArrayList<>();
    }

    /** 从隔离目录读取并验证玩家可编辑的 JSON；读取失败记日志，禁止静默覆盖。 */
    public MapWaypointBook(Path directory) {
        file = directory.resolve("waypoints.json");
        if (!Files.exists(file)) return;
        try {
            Stored loaded = GSON.fromJson(Files.readString(file), Stored.class);
            if (loaded == null || loaded.points == null) throw new IllegalArgumentException("导航点根记录为空");
            Set<String> ids = new HashSet<>();
            for (Entry entry : loaded.points) {
                if (entry == null || entry.id == null || entry.name == null || entry.number < 1
                        || entry.number == Integer.MAX_VALUE || entry.name.length() > NAME_LIMIT
                        || entry.helipad && entry.y == null
                        || !Double.isFinite(entry.x) || !Double.isFinite(entry.z)
                        || Math.abs(entry.x) > 29999984 || Math.abs(entry.z) > 29999984
                        || !ids.add(entry.id)) throw new IllegalArgumentException("导航点记录无效或重复");
            }
            stored = loaded;
        } catch (IOException | RuntimeException error) {
            loadFailed = true;
            No_24_heli.LOGGER.warn("无法读取地图导航点 {}，保留原文件", file, error);
        }
    }

    /** 仅返回停机坪的不可变列表，历史普通导航点在首次服务端同步前也不能出现在清单中。 */
    public List<Entry> entries() { return stored.points.stream().filter(Entry::helipad).toList(); }

    /** 以服务端快照覆盖停机坪名称和编号，移除旧普通导航点及已拆除停机坪并写回缓存。 */
    public boolean syncPads(List<HelipadMapPayload.Entry> pads) {
        Set<String> current = new HashSet<>();
        boolean changed = false;
        for (var pad : pads) {
            String id = "pad:" + pad.pos().asLong();
            current.add(id);
            Entry old = find(id);
            Entry entry = new Entry(id, pad.number(), pad.name(), pad.pos().getX() + .5,
                    pad.pos().getY(), pad.pos().getZ() + .5, true);
            if (!entry.equals(old)) {
                if (old == null) stored.points.add(entry);
                else stored.points.set(stored.points.indexOf(old), entry);
                changed = true;
            }
        }
        changed |= stored.points.removeIf(entry -> !entry.helipad || !current.contains(entry.id));
        return !changed || save();
    }

    /** 按服务端中心坐标生成的稳定标识查重，区块重载不会重复登记停机坪。 */
    private Entry find(String id) {
        return stored.points.stream().filter(entry -> entry.id.equals(id)).findFirst().orElse(null);
    }

    /** 同目录临时文件写完后替换正式 JSON，避免正常写入中途留下半份记录。 */
    private boolean save() {
        if (loadFailed) return false;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("waypoints.json.tmp");
            Files.writeString(temporary, GSON.toJson(stored));
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException error) {
            No_24_heli.LOGGER.warn("无法保存地图导航点 {}", file, error);
            return false;
        }
    }
}
