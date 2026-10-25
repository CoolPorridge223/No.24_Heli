/** 文件：飞行员目镜中的个人三维航路显示；端侧：CLIENT；通道：no_24_heli:navigation_path。 */
package org.DJB.no_24_heli.client.render;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.math.Vec3d;
import org.DJB.no_24_heli.client.HeliClientConfig;
import org.DJB.no_24_heli.client.camera.HeliView;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.network.NavigationPathPayload;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 职责：绘制水平灰心白边导航箭头和范围内的实际终点，仅本机乘员佩戴目镜并开启个人开关时可见。
 * 引用 net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents 在世界透明物之后绘制；
 * net.minecraft.client.render.RenderLayer 管理混合、双面和只写颜色，保留地形及机体深度遮挡。
 * 生命周期：初始化注册；包接收、缓存清理在客户端主线程，网格仅渲染线程生成。
 * 排查：无显示先查乘机、目镜和个人开关，再查航段是否就绪；跨机残线查乘骑引用与包编号。
 */
@Environment(EnvType.CLIENT)
public final class NavigationPathRenderer {
    /** 箭头含描边宽 1/3 米、长 0.6 米、间隔一米；白边厚两厘米，中心 RGB 为 0.9。 */
    private static final double HALF_WIDTH = 1.0 / 6, ARROW_LENGTH = .6, SPACING = 1, BORDER = .02;
    /** 接回原轨迹前七米段末端；近距渐显仍为五米，两者独立，起点离实际机鼻两米。 */
    private static final double ORIGINAL_START = 1, JOIN_DISTANCE = 7, NOSE_GAP = 2, LENGTH = 64, FADE = 5;
    /** 接回曲线每十厘米以内采样，至少十二段；控制柄不超过接回范围的一半，避免偏航时大幅绕圈。 */
    private static final double CONNECTION_STEP = .1, MAX_HANDLE = JOIN_DISTANCE / 2;
    private static final int MIN_CONNECTION_STEPS = 12;
    /** 箭头保留白边，填充 RGB 为 (.9,.9,.9)；终点圆盘和标点共用金色 #FFD700 与浅金描边。 */
    private static final Vector3f WHITE = new Vector3f(1), ARROW_COLOR = new Vector3f(.9f),
            GOLD = new Vector3f(1, 215 / 255f, 0), GOLD_BORDER = new Vector3f(1, .94f, .55f);
    /** 对应 HelicopterModel.BODY_PROFILE 的最前端截面中心；必须随机鼻几何一起维护，单位格。 */
    private static final Vec3d LOCAL_NOSE = new Vec3d(0, .86, 1.97);
    /** 无柄箭头的尖端、左翼、内凹尾端和右翼；局部 X 横向、Y 前向，不能绕相机旋转。 */
    private static final double[][] ARROW = {{0, ARROW_LENGTH / 2}, {-HALF_WIDTH, -ARROW_LENGTH / 2},
            {0, -ARROW_LENGTH / 2 + .18}, {HALF_WIDTH, -ARROW_LENGTH / 2}};
    private static final double[][] ARROW_INNER = inset(ARROW, BORDER);
    /** 实际终点水平圆面半径半米；标点宽一米、高一点五米，尖端抬高三十五厘米。 */
    private static final double DESTINATION_RADIUS = .5, PIN_RADIUS = .5, PIN_HEAD = 1, PIN_LIFT = .35,
            PIN_HOLE = .16;
    /** 图标沿世界竖直方向作正弦浮动，振幅十厘米、周期六十 tick（三秒）；圆盘保持固定。 */
    private static final double PIN_BOB_AMPLITUDE = .1;
    private static final int PIN_BOB_PERIOD_TICKS = 60;
    /** 飞机进入实际终点两米范围后锁定隐藏，防止自动驾驶轻微越点修正时终点标记再次显现。 */
    private static final double DESTINATION_REACHED_RADIUS = 2;
    /** 圆面和标点圆弧使用三十二段；仅初始化一次二维轮廓，渲染帧不再生成轮廓表。 */
    private static final int CIRCLE_SEGMENTS = 32;
    private static final double[][] PIN = pinOutline(), PIN_INNER = inset(PIN, BORDER);
    /** 每米固定采样，只在预览头部十二米寻找飞机投影，防止近距回环跳段；一秒未收包则失效。 */
    private static final int PROJECTION_SEGMENTS = 12, STALE_TICKS = 20;
    /** 独立无纹理三角形层；不受环境亮度影响，不写深度，灰色填充与白色描边不重叠混合。 */
    private static final RenderLayer LAYER = RenderLayer.of("no_24_heli_navigation_path", VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.TRIANGLES, RenderLayer.DEFAULT_BUFFER_SIZE, false, true,
            RenderLayer.MultiPhaseParameters.builder().program(RenderPhase.COLOR_PROGRAM)
                    .transparency(RenderPhase.TRANSLUCENT_TRANSPARENCY).cull(RenderPhase.DISABLE_CULLING)
                    .depthTest(RenderPhase.LEQUAL_DEPTH_TEST).writeMaskState(RenderPhase.COLOR_MASK).build(false));
    /** 只缓存当前乘坐飞机；实体引用还区分断线重连、跨维度与编号复用。 */
    private static HelicopterEntity helicopter;
    private static List<Vec3d> points = List.of();
    /** 服务端提供已确认的实际目的地三维坐标；空值表示最终航段尚未规划，不以预览尾点代替。 */
    private static Vec3d destination;
    /** 本次导航已接近的目标；按 X/Z 识别同一导航点，终点高度重规划不会重新显示。 */
    private static Vec3d reachedDestination;
    private static long receivedAt;

    /** 完整显示航路及连续弧长坐标；原航路段沿用服务端每米采样基准，reference 锚定首个固定航点。 */
    private record Preview(List<Vec3d> points, double[] distances, double reference) {}

    /** 路径确定后统一生成的箭头位置与水平航向；渲染阶段只提交几何，不再区分连接段和原航路。 */
    private record ArrowPose(Vec3d center, Vec3d heading) {}

    /** 仅静态客户端渲染工具，不创建实例。 */
    private NavigationPathRenderer() {}

    /**
     * 功能：注册 S2C 接收、断线和乘骑清理、世界绘制；仅客户端初始化调用一次。
     * 引用 ClientPlayNetworking 回调切回客户端主线程；迟到的其他飞机快照直接丢弃。
     */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NavigationPathPayload.ID, (packet, context) ->
                context.client().execute(() -> {
                    var client = context.client();
                    if (client.world == null || client.player == null
                            || !(client.player.getVehicle() instanceof HelicopterEntity mounted)
                            || mounted.getId() != packet.helicopterId()) return;
                    helicopter = mounted; points = packet.points(); destination = packet.destination();
                    receivedAt = client.world.getTime();
                }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.world == null || client.player == null || client.player.getVehicle() != helicopter
                    || client.world.getTime() - receivedAt > STALE_TICKS) clear();
            else if (helicopter != null) {
                if (!helicopter.flight.autopilot) reachedDestination = null;
                else if (destination != null) hasReachedDestination(helicopter.getPos());
            }
        });
        WorldRenderEvents.LAST.register(NavigationPathRenderer::render);
    }

    /** 功能：释放当前乘骑快照；无世界修改或网络副作用，断线及换机立即调用。 */
    private static void clear() { helicopter = null; points = List.of(); destination = null; reachedDestination = null; }

    /**
     * 功能：在当前乘员本人视角绘制水平箭头及实际终点；引用 MinecraftClient 检查个人状态、
     * PilotEquipment.hasGoggles 兼容专用目镜槽和原头槽；渲染线程，只读取权威飞行状态。
     * 矩阵使用 WorldRenderContext 的世界旋转，并先减去相机双精度坐标，避免远坐标抖动。
     */
    private static void render(WorldRenderContext context) {
        var client = MinecraftClient.getInstance();
        if (client.player == null || helicopter == null || client.player.getVehicle() != helicopter
                || client.getCameraEntity() != client.player || !HeliClientConfig.INSTANCE.navigationPathVisible
                || !PilotEquipment.hasGoggles(client.player) || !helicopter.flight.autopilot
                || helicopter.flight.wreck || HelicopterMesh.isShadowPass()) return;
        float delta = context.tickCounter().getTickDelta(false);
        Vec3d position = helicopter.getLerpedPos(delta);
        Vector3f offset = new Vector3f((float) LOCAL_NOSE.x, (float) LOCAL_NOSE.y, (float) LOCAL_NOSE.z)
                .rotate(HeliView.bodyRotation(helicopter, delta));
        Vec3d nose = position.add(offset.x, offset.y, offset.z);
        double yaw = Math.toRadians(helicopter.getYaw(delta));
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
        var builder = Tessellator.getInstance().begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        Matrix4f matrix = context.matrixStack().peek().getPositionMatrix();
        Vec3d camera = context.camera().getPos();
        if (points.size() > 1) {
            List<Vec3d> tail = clip(projection(position) + ORIGINAL_START + JOIN_DISTANCE);
            if (tail.isEmpty()) tail.add(points.getLast());
            Preview preview = connection(tail, nose.add(forward.multiply(NOSE_GAP)), forward);
            for (ArrowPose pose : layout(preview))
                arrow(builder, matrix, pose.center(), pose.heading(), nose, camera);
        }
        // 目的地按飞机到真实三维终点的直线距离裁剪；六十四米预览末端不会生成假标点。
        if (destination != null && position.squaredDistanceTo(destination) <= LENGTH * LENGTH
                && !hasReachedDestination(position))
            destination(builder, matrix, context, nose, camera);
        var buffer = builder.endNullable();
        if (buffer == null) return;
        LAYER.startDrawing();
        try { BufferRenderer.drawWithGlobalProgram(buffer); }
        finally { LAYER.endDrawing(); }
    }

    /** 功能：将插值后的飞机原点投影至快照头部，避免十赫兹收包使起点沿航线跳动；无状态写入。 */
    private static double projection(Vec3d position) {
        double best = Double.POSITIVE_INFINITY, selected = 0, along = 0;
        for (int i = 0; i + 1 < points.size() && i < PROJECTION_SEGMENTS; i++) {
            Vec3d a = points.get(i), delta = points.get(i + 1).subtract(a);
            double square = delta.lengthSquared(), length = Math.sqrt(square);
            if (length < 1e-6) continue;
            double fraction = Math.clamp(position.subtract(a).dotProduct(delta) / square, 0, 1);
            double deviation = position.squaredDistanceTo(a.add(delta.multiply(fraction)));
            if (deviation < best) { best = deviation; selected = along + length * fraction; }
            along += length;
        }
        return selected;
    }

    /** 功能：保留原轨迹前七米段末端之后的固定航点，先供 connection 构造完整平滑航路。 */
    private static List<Vec3d> clip(double start) {
        List<Vec3d> result = new ArrayList<>();
        double along = 0;
        for (int i = 1; i < points.size(); i++) {
            Vec3d a = points.get(i - 1), b = points.get(i);
            double length = a.distanceTo(b), next = along + length;
            if (length > 1e-6 && next > start) {
                double from = Math.max(start, along);
                if (result.isEmpty()) result.add(a.lerp(b, (from - along) / length));
                if (result.getLast().squaredDistanceTo(b) > 1e-12) result.add(b);
            }
            along = next;
        }
        return result;
    }

    /**
     * 功能：先构造机鼻至原航路的三次 Bezier 曲线，再追加原航路，得到完整、切向连续的显示路径。
     * net.minecraft.util.math.Vec3d 仅计算表现坐标；起始切线朝机头，结束切线沿接回点之后的航段。
     * 曲线与原航路共用一个接回点；每米采样基准锚定原航路，不因曲线弧长变化让后方箭头滑动。
     */
    private static Preview connection(List<Vec3d> tail, Vec3d anchor, Vec3d forward) {
        Vec3d join = tail.getFirst();
        double distance = anchor.distanceTo(join), handle = Math.min(distance / 3, MAX_HANDLE);
        Vec3d endDirection = tail.size() > 1 ? tail.get(1).subtract(join) : join.subtract(anchor);
        Vec3d controlA = anchor.add(forward.multiply(handle));
        Vec3d controlB = join.subtract(endDirection.normalize().multiply(handle));
        int steps = Math.max(MIN_CONNECTION_STEPS, (int) Math.ceil(
                (anchor.distanceTo(controlA) + controlA.distanceTo(controlB) + controlB.distanceTo(join)) / CONNECTION_STEP));
        List<Vec3d> path = new ArrayList<>();
        path.add(anchor);
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Vec3d a = anchor.lerp(controlA, t), b = controlA.lerp(controlB, t), c = controlB.lerp(join, t);
            Vec3d point = a.lerp(b, t).lerp(b.lerp(c, t), t);
            if (path.getLast().squaredDistanceTo(point) > 1e-12) path.add(point);
        }
        if (path.getLast().squaredDistanceTo(join) > 1e-12) path.add(join);
        int firstFixed = tail.size() > 1 ? path.size() : path.size() - 1;
        path.addAll(tail.subList(1, tail.size()));
        double[] distances = new double[path.size()];
        for (int i = 1; i < path.size(); i++) {
            // 后方采样点按真实航路弧长恰好相隔一米，不能用曲线短弦长度重新排布固定箭头。
            double span = i > firstFixed && i + 1 < path.size() ? SPACING : path.get(i - 1).distanceTo(path.get(i));
            distances[i] = distances[i - 1] + span;
        }
        return new Preview(List.copyOf(path), distances, distances[firstFixed]);
    }

    /**
     * 功能：在完整显示航路上统一按每米生成箭头，跨接回点不裁掉箭头，也不重新开始计数。
     * 前后各取半个箭头长度确定水平朝向，使越过曲线接缝时的旋转连续；无渲染或世界副作用。
     */
    private static List<ArrowPose> layout(Preview preview) {
        double half = ARROW_LENGTH / 2;
        double start = preview.reference() - Math.floor((preview.reference() - half) / SPACING) * SPACING;
        double end = Math.min(LENGTH, preview.distances()[preview.distances().length - 1]) - half;
        List<ArrowPose> result = new ArrayList<>();
        for (double distance = start; distance <= end; distance += SPACING) {
            Vec3d center = pointAt(preview, distance);
            Vec3d tangent = pointAt(preview, distance + half).subtract(pointAt(preview, distance - half));
            Vec3d horizontal = new Vec3d(tangent.x, 0, tangent.z);
            Vec3d heading = horizontal.lengthSquared() > 1e-8 ? horizontal.normalize() : new Vec3d(0, 0, 1);
            result.add(new ArrowPose(center, heading));
        }
        return result;
    }

    /** 功能：沿已经确定的完整路径插值，连接曲线与原航路使用同一查询；不按箭头所属区段分支。 */
    private static Vec3d pointAt(Preview preview, double distance) {
        int end = 1;
        while (end < preview.distances().length && preview.distances()[end] < distance) end++;
        if (end == preview.distances().length) return preview.points().getLast();
        double span = preview.distances()[end] - preview.distances()[end - 1];
        return preview.points().get(end - 1).lerp(preview.points().get(end),
                Math.clamp((distance - preview.distances()[end - 1]) / span, 0, 1));
    }

    /** 功能：在指定世界位置绘制一个水平无柄箭头；位置和方向由航路提供，不读取相机朝向。 */
    private static void arrow(BufferBuilder builder, Matrix4f matrix, Vec3d center, Vec3d forward,
                              Vec3d nose, Vec3d camera) {
        float alpha = opacity(center, nose);
        if (alpha <= 0) return;
        Vec3d origin = center.subtract(camera), right = new Vec3d(forward.z, 0, -forward.x);
        outline(builder, matrix, origin, right, forward, ARROW, ARROW_INNER, WHITE, alpha);
        Vec3d tip = plane(origin, right, forward, ARROW_INNER[0]);
        Vec3d left = plane(origin, right, forward, ARROW_INNER[1]);
        Vec3d notch = plane(origin, right, forward, ARROW_INNER[2]);
        Vec3d wing = plane(origin, right, forward, ARROW_INNER[3]);
        triangle(builder, matrix, tip, left, notch, ARROW_COLOR, alpha);
        triangle(builder, matrix, tip, notch, wing, ARROW_COLOR, alpha);
    }

    /** 功能：距机鼻外锚点越近越透明，向外五米内线性渐显；世界距离不随观察角度改变。 */
    private static float opacity(Vec3d point, Vec3d nose) {
        return (float) Math.clamp((point.distanceTo(nose) - NOSE_GAP) / FADE, 0, 1);
    }

    /**
     * 功能：记住本次导航已进入的终点邻域，轻微越点后仍隐藏圆盘和图标，不改变服务端到达判定。
     * net.minecraft.util.math.Vec3d 比较飞机原点到实际终点的三维距离；tick 与渲染插值均可更新。
     * 取消导航或清理乘骑缓存后重置；新目标按 X/Z 区分，佩戴和显示开关不影响到达记忆。
     */
    private static boolean hasReachedDestination(Vec3d position) {
        if (position.squaredDistanceTo(destination) <= DESTINATION_REACHED_RADIUS * DESTINATION_REACHED_RADIUS)
            reachedDestination = destination;
        return reachedDestination != null && reachedDestination.x == destination.x && reachedDestination.z == destination.z;
    }

    /**
     * 功能：绘制固定的终点圆盘，以及沿世界竖直方向正弦浮动的地图标点；仅标点跟随相机旋转。
     * net.minecraft.client.render.Camera#getRotation 提供标点的屏幕右、上方向，不参与箭头或圆面朝向。
     */
    private static void destination(BufferBuilder builder, Matrix4f matrix, WorldRenderContext context,
                                    Vec3d nose, Vec3d camera) {
        Vec3d center = destination.subtract(camera);
        float alpha = opacity(destination, nose);
        if (alpha <= 0) return;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            double a = Math.PI * 2 * i / CIRCLE_SEGMENTS, b = Math.PI * 2 * (i + 1) / CIRCLE_SEGMENTS;
            Vec3d outerA = center.add(Math.cos(a) * DESTINATION_RADIUS, 0, Math.sin(a) * DESTINATION_RADIUS);
            Vec3d outerB = center.add(Math.cos(b) * DESTINATION_RADIUS, 0, Math.sin(b) * DESTINATION_RADIUS);
            Vec3d innerA = center.add(Math.cos(a) * (DESTINATION_RADIUS - BORDER), 0, Math.sin(a) * (DESTINATION_RADIUS - BORDER));
            Vec3d innerB = center.add(Math.cos(b) * (DESTINATION_RADIUS - BORDER), 0, Math.sin(b) * (DESTINATION_RADIUS - BORDER));
            quad(builder, matrix, outerA, outerB, innerB, innerA, GOLD_BORDER, alpha);
            triangle(builder, matrix, center, innerA, innerB, GOLD, alpha);
        }
        Vector3f cameraRight = new Vector3f(1, 0, 0).rotate(context.camera().getRotation());
        Vector3f cameraUp = new Vector3f(0, 1, 0).rotate(context.camera().getRotation());
        Vec3d right = new Vec3d(cameraRight.x, cameraRight.y, cameraRight.z);
        Vec3d up = new Vec3d(cameraUp.x, cameraUp.y, cameraUp.z);
        // 使用世界 tick 与帧内插值，暂停时停住；浮动不参与距离渐隐或到达判断，避免透明度随动画跳动。
        double phase = (context.world().getTime() % PIN_BOB_PERIOD_TICKS + context.tickCounter().getTickDelta(false))
                * Math.PI * 2 / PIN_BOB_PERIOD_TICKS;
        Vec3d origin = center.add(0, PIN_LIFT + Math.sin(phase) * PIN_BOB_AMPLITUDE, 0);
        outline(builder, matrix, origin, right, up, PIN, PIN_INNER, GOLD_BORDER, alpha);
        // 标点中心镂空；金色主体与浅金色孔沿拼成相邻环，避免半透明时叠画改变颜色。
        for (int i = 0; i < PIN_INNER.length; i++) {
            int next = (i + 1) % PIN_INNER.length;
            Vec3d a = plane(origin, right, up, PIN_INNER[i]);
            Vec3d b = plane(origin, right, up, PIN_INNER[next]);
            Vec3d head = origin.add(up.multiply(PIN_HEAD));
            Vec3d rayA = a.subtract(head).normalize(), rayB = b.subtract(head).normalize();
            Vec3d holeA = head.add(rayA.multiply(PIN_HOLE + BORDER)), holeB = head.add(rayB.multiply(PIN_HOLE + BORDER));
            quad(builder, matrix, a, b, holeB, holeA, GOLD, alpha);
            quad(builder, matrix, holeA, holeB, head.add(rayB.multiply(PIN_HOLE)), head.add(rayA.multiply(PIN_HOLE)), GOLD_BORDER, alpha);
        }
    }

    /** 功能：初始化标准尖底圆头地图标点的逆时针轮廓；纯几何计算，不访问 MC 或图形状态。 */
    private static double[][] pinOutline() {
        double[][] result = new double[CIRCLE_SEGMENTS + 2][2];
        double start = -Math.asin(PIN_RADIUS / PIN_HEAD), sweep = Math.PI - 2 * start;
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = start + sweep * i / CIRCLE_SEGMENTS;
            result[i + 1] = new double[]{Math.cos(angle) * PIN_RADIUS, PIN_HEAD + Math.sin(angle) * PIN_RADIUS};
        }
        return result;
    }

    /** 功能：沿逆时针轮廓的相邻边内缩固定米制距离，保留箭头尾部凹口和标点尖端的白色描边。 */
    private static double[][] inset(double[][] polygon, double width) {
        double[][] result = new double[polygon.length][2];
        for (int i = 0; i < polygon.length; i++) {
            double[] previous = polygon[(i + polygon.length - 1) % polygon.length], point = polygon[i],
                    next = polygon[(i + 1) % polygon.length];
            double ax = point[0] - previous[0], ay = point[1] - previous[1], a = Math.hypot(ax, ay);
            double bx = next[0] - point[0], by = next[1] - point[1], b = Math.hypot(bx, by);
            double nx = -ay / a, ny = ax / a, mx = -by / b, my = bx / b;
            double scale = width / (1 + nx * mx + ny * my);
            result[i][0] = point[0] + (nx + mx) * scale;
            result[i][1] = point[1] + (ny + my) * scale;
        }
        return result;
    }

    /** 功能：连接外轮廓和内轮廓形成指定颜色的描边；与填充共用边界，不使用叠画放大透明度。 */
    private static void outline(BufferBuilder builder, Matrix4f matrix, Vec3d origin, Vec3d x, Vec3d y,
                                double[][] outer, double[][] inner, Vector3f color, float alpha) {
        for (int i = 0; i < outer.length; i++) {
            int next = (i + 1) % outer.length;
            quad(builder, matrix, plane(origin, x, y, outer[i]), plane(origin, x, y, outer[next]),
                    plane(origin, x, y, inner[next]), plane(origin, x, y, inner[i]), color, alpha);
        }
    }

    /** 功能：把二维顶点映射到指定平面；箭头使用两个水平轴，只有目的地图标使用相机轴。 */
    private static Vec3d plane(Vec3d origin, Vec3d x, Vec3d y, double[] point) {
        return origin.add(x.multiply(point[0])).add(y.multiply(point[1]));
    }

    /** 功能：用两个三角形提交共面四边形；POSITION_COLOR 不包含法线或光照属性。 */
    private static void quad(BufferBuilder builder, Matrix4f matrix, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                             Vector3f color, float alpha) {
        triangle(builder, matrix, a, b, c, color, alpha);
        triangle(builder, matrix, a, c, d, color, alpha);
    }

    /** 功能：向 net.minecraft.client.render.BufferBuilder 提交指定 RGB 颜色的三角面及统一透明度。 */
    private static void triangle(BufferBuilder builder, Matrix4f matrix, Vec3d a, Vec3d b, Vec3d c,
                                 Vector3f color, float alpha) {
        vertex(builder, matrix, a, color, alpha);
        vertex(builder, matrix, b, color, alpha);
        vertex(builder, matrix, c, color, alpha);
    }

    /** 功能：以相机相对坐标提交 POSITION_COLOR 顶点；单位为格，RGB 和透明度范围 0..1。 */
    private static void vertex(BufferBuilder builder, Matrix4f matrix, Vec3d point, Vector3f color, float alpha) {
        builder.vertex(matrix, (float) point.x, (float) point.y, (float) point.z).color(color.x, color.y, color.z, alpha);
    }
}
