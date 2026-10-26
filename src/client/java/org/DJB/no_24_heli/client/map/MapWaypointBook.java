package org.DJB.no_24_heli.client.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.DJB.no_24_heli.No_24_heli;
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
 * 职责：保存本机地图导航点及停机坪别名，不修改服务端导航协议和目标高度。
 * 引用 net.minecraft.text.Text 生成本地化默认名称；停机坪来自服务端固定目录。
 * 生命周期：由地图界面打开时读取；只在客户端主线程添加、改名和保存。
 * 排查：串档检查 ExploredMap 的来源/维度键；保存失败检查日志及配置目录权限。
 */
@Environment(EnvType.CLIENT)
public final class MapWaypointBook {
    /** 名称上限，单位为字符；与 net.minecraft.client.gui.widget.TextFieldWidget 共用。 */
    public static final int NAME_LIMIT = 40;
    /** JSON 编解码器；仅处理本机配置，不参与网络同步。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 当前世界/维度的记录文件；不与世界权威存档混写。 */
    private final Path file;
    /** 读取损坏文件后禁止覆盖原件，玩家会收到保存失败提示。 */
    private boolean loadFailed;
    /** 本地记录按首次登记顺序排列，默认编号不会因改名或重进地图改变。 */
    private Stored stored = new Stored();

    /** 坐标以世界格为单位；未知区块没有可核实的地面 Y，使用 null 明确表示未知。 */
    @Environment(EnvType.CLIENT)
    public record Entry(String id, int number, String name, double x, Integer y, double z, boolean helipad) {
        /** 自定义名称留空时使用稳定编号；net.minecraft.text.Text 负责随语言切换。 */
        public Text label() {
            return name.isBlank() ? Text.translatable("screen.no_24_heli.waypoint_default", number) : Text.literal(name);
        }
    }

    /** JSON 根对象；编号只递增，重命名不重新排序或复用旧编号。 */
    @Environment(EnvType.CLIENT)
    private static final class Stored {
        /** 下一个未使用的系统编号，默认从 1 开始，只保存在本机。 */
        private int nextNumber = 1;
        /** 同一来源和维度内的登记顺序，包含当前停机坪及手工点。 */
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
                loaded.nextNumber = Math.max(loaded.nextNumber, entry.number + 1);
            }
            if (loaded.nextNumber < 1) throw new IllegalArgumentException("导航点编号无效");
            stored = loaded;
        } catch (IOException | RuntimeException error) {
            loadFailed = true;
            No_24_heli.LOGGER.warn("无法读取地图导航点 {}，保留原文件", file, error);
        }
    }

    /** 当前显示列表是不可变快照，界面滚动或服务端目录变化不会在遍历中修改它。 */
    public List<Entry> entries() { return List.copyOf(stored.points); }

    /** 服务端停机坪增删同步到本机列表；现存位置保留名称，卸载区块不会删目录。 */
    public boolean syncPads(List<HelipadMapPayload.Entry> pads) {
        Set<String> current = new HashSet<>();
        boolean changed = false;
        for (var pad : pads) {
            String id = "pad:" + pad.pos().asLong();
            current.add(id);
            if (find(id) == null) {
                stored.points.add(new Entry(id, stored.nextNumber++, "", pad.pos().getX() + .5,
                        pad.pos().getY(), pad.pos().getZ() + .5, true));
                changed = true;
            }
        }
        changed |= stored.points.removeIf(entry -> entry.helipad && !current.contains(entry.id));
        return !changed || save();
    }

    /** 同一格 X/Z 再次设点只更新坐标，保留名称与编号；不发送网络包。 */
    public boolean remember(double x, Integer y, double z) {
        String id = "point:" + (int) Math.floor(x) + ":" + (int) Math.floor(z);
        Entry old = find(id);
        Entry entry = new Entry(id, old == null ? stored.nextNumber++ : old.number,
                old == null ? "" : old.name, x, y == null && old != null ? old.y : y, z, false);
        if (old == null) stored.points.add(entry);
        else stored.points.set(stored.points.indexOf(old), entry);
        return save();
    }

    /** 保存用户名称；空白名称恢复默认编号，写盘失败恢复旧名以免显示假保存状态。 */
    public boolean rename(Entry entry, String name) {
        String trimmed = name.strip();
        if (trimmed.length() > NAME_LIMIT) return false;
        int index = stored.points.indexOf(entry);
        if (index < 0) return false;
        stored.points.set(index, new Entry(entry.id, entry.number, trimmed, entry.x, entry.y, entry.z, entry.helipad));
        if (save()) return true;
        stored.points.set(index, entry);
        return false;
    }

    /** 按稳定标识查重，停机坪和手工导航点使用独立前缀。 */
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
