/** 文件：乘员专用三维导航预览快照；端侧：COMMON；通道：no_24_heli:navigation_path（S2C）。 */
package org.DJB.no_24_heli.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.No_24_heli;

/**
 * 职责：传送目镜短程三维预览及地图完整剩余航段的水平快照，不包含个人显示设置。
 * 引用 net.minecraft.network.RegistryByteBuf 编解码、net.minecraft.util.math.Vec3d 保存世界坐标。
 * 生命周期：服务端每两 tick 只发给本机乘员；客户端网络线程解码后交主线程。
 * 排查：空列表表示清除，缺省地图字段表示保留旧快照；远坐标使用双精度原点与相对浮点偏移。
 */
public record NavigationPathPayload(int helicopterId, List<Vec3d> points, Vec3d destination, Chart chart) implements CustomPayload {
    /** 后方四格、前方七十二格，每米固定采样；上限兼容已有预算，末点仅用于裁剪，不画箭头。 */
    public static final int MAX_POINTS = 160;
    /** 地图只同步已确认航段的水平投影，最多五百一十二点；目标单独保留双精度，单位格。 */
    public static final int MAX_CHART_POINTS = 512;
    /** 地图快照含真实导航目标；chart 为 null 表示本包不更新，空点集表示清除缓存。 */
    public record Chart(double goalX, double goalZ, List<Vec3d> points) {
        /** 功能：隔离跨线程列表并校验网络边界；高度不用于地图，解码后统一为零。 */
        public Chart {
            if (!Double.isFinite(goalX) || !Double.isFinite(goalZ) || points.size() > MAX_CHART_POINTS
                    || points.stream().anyMatch(p -> !Double.isFinite(p.x) || !Double.isFinite(p.z)))
                throw new IllegalArgumentException("地图航路坐标或数量无效");
            points = List.copyOf(points);
        }
    }
    /** 压缩坐标最大相对距离八十格，覆盖完整七十六格弧长并留浮点量化余量。 */
    private static final double MAX_OFFSET = 80;
    /** 通道只注册 S2C，不允许客户端提交或更改服务端轨迹。 */
    public static final Id<NavigationPathPayload> ID = new Id<>(No_24_heli.id("navigation_path"));
    /** 三维预览采用 XYZ 压缩，实际终点独立双精度；可选地图快照仅压缩 X/Z，单位格。 */
    public static final PacketCodec<RegistryByteBuf, NavigationPathPayload> CODEC = new PacketCodec<>() {
        /** 功能：检查数量、剩余字节与有限坐标，再生成不可变快照；仅网络解码线程。 */
        @Override public NavigationPathPayload decode(RegistryByteBuf buffer) {
            int id = buffer.readVarInt(), count = buffer.readVarInt();
            if (count < 0 || count > MAX_POINTS || count > 0 && buffer.readableBytes() < 24 + (count - 1) * 12)
                throw new IllegalArgumentException("导航预览点数量无效");
            List<Vec3d> points = new ArrayList<>(count);
            if (count > 0) {
                Vec3d origin = new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
                for (int i = 0; i < count; i++) {
                    Vec3d point = i == 0 ? origin : origin.add(buffer.readFloat(), buffer.readFloat(), buffer.readFloat());
                    if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                            || point.squaredDistanceTo(origin) > MAX_OFFSET * MAX_OFFSET)
                        throw new IllegalArgumentException("导航预览坐标无效");
                    points.add(point);
                }
            }
            Vec3d destination = buffer.readBoolean()
                    ? new Vec3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()) : null;
            if (destination != null && (!Double.isFinite(destination.x) || !Double.isFinite(destination.y)
                    || !Double.isFinite(destination.z))) throw new IllegalArgumentException("导航终点坐标无效");
            Chart chart = null;
            if (buffer.readBoolean()) {
                double goalX = buffer.readDouble(), goalZ = buffer.readDouble();
                int chartCount = buffer.readVarInt();
                if (chartCount < 0 || chartCount > MAX_CHART_POINTS
                        || chartCount > 0 && buffer.readableBytes() < 16 + (chartCount - 1) * 8)
                    throw new IllegalArgumentException("地图航路点数量无效");
                List<Vec3d> chartPoints = new ArrayList<>(chartCount);
                if (chartCount > 0) {
                    Vec3d origin = new Vec3d(buffer.readDouble(), 0, buffer.readDouble());
                    for (int i = 0; i < chartCount; i++)
                        chartPoints.add(i == 0 ? origin : origin.add(buffer.readFloat(), 0, buffer.readFloat()));
                }
                chart = new Chart(goalX, goalZ, chartPoints);
            }
            return new NavigationPathPayload(id, points, destination, chart);
        }

        /** 功能：以首点为双精度原点压缩预览；net.minecraft.network.RegistryByteBuf 仅写本包。 */
        @Override public void encode(RegistryByteBuf buffer, NavigationPathPayload packet) {
            buffer.writeVarInt(packet.helicopterId());
            buffer.writeVarInt(packet.points().size());
            if (!packet.points().isEmpty()) {
                Vec3d origin = packet.points().getFirst();
                buffer.writeDouble(origin.x); buffer.writeDouble(origin.y); buffer.writeDouble(origin.z);
                for (int i = 1; i < packet.points().size(); i++) {
                    Vec3d offset = packet.points().get(i).subtract(origin);
                    buffer.writeFloat((float) offset.x); buffer.writeFloat((float) offset.y); buffer.writeFloat((float) offset.z);
                }
            }
            buffer.writeBoolean(packet.destination() != null);
            if (packet.destination() != null) {
                buffer.writeDouble(packet.destination().x);
                buffer.writeDouble(packet.destination().y);
                buffer.writeDouble(packet.destination().z);
            }
            buffer.writeBoolean(packet.chart() != null);
            if (packet.chart() != null) {
                Chart chart = packet.chart();
                buffer.writeDouble(chart.goalX()); buffer.writeDouble(chart.goalZ());
                buffer.writeVarInt(chart.points().size());
                if (!chart.points().isEmpty()) {
                    Vec3d origin = chart.points().getFirst();
                    buffer.writeDouble(origin.x); buffer.writeDouble(origin.z);
                    for (int i = 1; i < chart.points().size(); i++) {
                        Vec3d offset = chart.points().get(i).subtract(origin);
                        buffer.writeFloat((float) offset.x); buffer.writeFloat((float) offset.z);
                    }
                }
            }
        }
    };

    /** 功能：隔离调用方列表，跨线程后不能修改；超预算属于服务端生成错误，立即报告。 */
    public NavigationPathPayload {
        if (points.size() > MAX_POINTS) throw new IllegalArgumentException("导航预览超过点数预算");
        points = List.copyOf(points);
    }

    /** 功能：返回 net.minecraft.network.packet.CustomPayload 的 S2C 通道标识；无副作用。 */
    @Override public Id<? extends CustomPayload> getId() { return ID; }
}
