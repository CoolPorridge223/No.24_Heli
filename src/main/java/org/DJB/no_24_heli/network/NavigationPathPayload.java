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
 * 职责：传送已验证航路的有界预览，不包含个人显示设置。
 * 引用 net.minecraft.network.RegistryByteBuf 编解码、net.minecraft.util.math.Vec3d 保存世界坐标。
 * 生命周期：服务端每两 tick 只发给本机乘员；客户端网络线程解码后交主线程。
 * 排查：空列表表示清除；远处精度检查双精度原点及相对浮点偏移，不能直接发送世界浮点坐标。
 */
public record NavigationPathPayload(int helicopterId, List<Vec3d> points, Vec3d destination) implements CustomPayload {
    /** 后方四格、前方七十二格，每米固定采样；上限兼容已有预算，末点仅用于裁剪，不画箭头。 */
    public static final int MAX_POINTS = 160;
    /** 压缩坐标最大相对距离八十格，覆盖完整七十六格弧长并留浮点量化余量。 */
    private static final double MAX_OFFSET = 80;
    /** 通道只注册 S2C，不允许客户端提交或更改服务端轨迹。 */
    public static final Id<NavigationPathPayload> ID = new Id<>(No_24_heli.id("navigation_path"));
    /** 原点三个 double，后续点三个相对 float；可选实际终点独立用三个 double，单位为格。 */
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
            return new NavigationPathPayload(id, points, destination);
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
