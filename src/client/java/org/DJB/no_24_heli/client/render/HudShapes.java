/** 文件：第三人称仪表的连续几何与像素覆盖抗锯齿；端侧：CLIENT；关联 no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.math.RotationAxis;

/**
 * 职责：用浮点几何替代逐像素圆盘、斜线和位图仪表数字，复用机内七段数字的字形。
 * 引用：net.minecraft.client.gui.DrawContext 提供矩阵及 GUI 顶点批次，RenderLayer#getGui 保留原版混合与裁剪。
 * 生命周期：HUD 每帧调用，不创建帧缓冲或修改全局纹理过滤，不保存跨世界资源。
 * 端侧/线程：仅客户端渲染线程；仅读取 GUI 和窗口比例。
 * 排查：边缘粗细查 feather 的物理像素换算；数字裁剪仍由调用者的 DrawContext#enableScissor 管理。
 */
@Environment(EnvType.CLIENT)
public final class HudShapes {
    /** 与机内数字相同的九像素字高、六像素字距，支持符号与小数点。 */
    private static final double DIGIT_PITCH = 6;

    /** 纯绘制工具，无实例或渲染资源。 */
    private HudShapes() {}

    /** 当前矩阵下一个物理屏幕像素对应的局部宽度；net.minecraft.client.util.Window 提供 GUI 比例。 */
    private static double feather(DrawContext ctx) {
        var matrix = ctx.getMatrices().peek().getPositionMatrix();
        return 1 / (Math.hypot(matrix.m00(), matrix.m01()) * MinecraftClient.getInstance().getWindow().getScaleFactor());
    }

    /** 按覆盖率缩放原 ARGB 的透明度；保留原颜色，避免半透明背景边缘发黑。 */
    private static int alpha(int color, double coverage) {
        return (int) Math.round((color >>> 24) * coverage) << 24 | color & 0x00ffffff;
    }

    /**
     * 功能：向原版 GUI 批次提交带独立角点颜色的四边形；引用 net.minecraft.client.render.VertexConsumer。
     * 端侧：渲染线程；参数：四顶点坐标及 ARGB；返回：无；副作用：追加顶点。
     * 排查：所有坐标保留浮点精度，不能在缩放前取整；绘制顺序、深度与 ctx.fill 一致。
     */
    private static void quad(DrawContext ctx, double ax, double ay, double bx, double by,
            double cx, double cy, double dx, double dy, int a, int b, int c, int d) {
        var buffer = ctx.getVertexConsumers().getBuffer(RenderLayer.getGui());
        var matrix = ctx.getMatrices().peek().getPositionMatrix();
        // 原版 GUI 默认开启背面剔除；圆环与三角形也必须按 ctx.fill 的屏幕绕序提交。
        boolean reverse = (bx-ax)*(cy-ay)-(by-ay)*(cx-ax)+(cx-ax)*(dy-ay)-(cy-ay)*(dx-ax)>0;
        buffer.vertex(matrix, (float) ax, (float) ay, 0).color(a);
        buffer.vertex(matrix, (float) (reverse ? dx : bx), (float) (reverse ? dy : by), 0).color(reverse ? d : b);
        buffer.vertex(matrix, (float) cx, (float) cy, 0).color(c);
        buffer.vertex(matrix, (float) (reverse ? bx : dx), (float) (reverse ? by : dy), 0).color(reverse ? b : d);
    }

    /**
     * 功能：绘制四周一物理像素覆盖渐变的矩形；引用 net.minecraft.client.gui.DrawContext 的当前矩阵。
     * 端侧：渲染线程；参数：x/y/w/h 为局部位置与尺寸，color 为 ARGB；返回：无；副作用：提交九块几何。
     * 排查：细于一屏幕像素的笔画降低覆盖率，不扩大成实心粗线；旋转由调用者矩阵完成。
     */
    public static void rect(DrawContext ctx, double x, double y, double w, double h, int color) {
        double aa = feather(ctx), half = aa / 2;
        double ix = Math.min(w / 2, half), iy = Math.min(h / 2, half);
        int solid = alpha(color, Math.min(1, w / aa) * Math.min(1, h / aa)), clear = alpha(color, 0);
        for (int row = 0; row < 3; row++) for (int col = 0; col < 3; col++) {
            double left = col == 0 ? x - half : col == 1 ? x + ix : x + w - ix;
            double right = col == 0 ? x + ix : col == 1 ? x + w - ix : x + w + half;
            double top = row == 0 ? y - half : row == 1 ? y + iy : y + h - iy;
            double bottom = row == 0 ? y + iy : row == 1 ? y + h - iy : y + h + half;
            if (right <= left || bottom <= top) continue;
            quad(ctx, left, top, left, bottom, right, bottom, right, top,
                    row == 0 || col == 0 ? clear : solid, row == 2 || col == 0 ? clear : solid,
                    row == 2 || col == 2 ? clear : solid, row == 0 || col == 2 ? clear : solid);
        }
    }

    /**
     * 功能：浮点端点生成连续斜线；引用 net.minecraft.client.util.math.MatrixStack 隔离旋转，恢复后不影响文字。
     * 端侧：渲染线程；参数：两端、笔画宽及 ARGB；返回：无；副作用：追加抗锯齿矩形；排查：不逐点取整。
     */
    public static void line(DrawContext ctx, double x, double y, double endX, double endY, double width, int color) {
        double dx = endX - x, dy = endY - y, length = Math.hypot(dx, dy);
        var matrices = ctx.getMatrices();
        matrices.push(); matrices.translate(x, y, 0);
        matrices.multiply(RotationAxis.POSITIVE_Z.rotation((float) Math.atan2(dy, dx)));
        rect(ctx, 0, -width / 2, length, width, color);
        matrices.pop();
    }

    /**
     * 功能：绘制圆盘或圆环，边缘覆盖带宽固定为一个物理像素；引用 net.minecraft.client.gui.DrawContext 批次。
     * 端侧：渲染线程；参数：圆心、半径、笔宽（零表示圆盘）及颜色；返回：无；副作用：追加连续环面。
     * 排查：内外边缘分别渐变，圆周细分最多 256 段，不用正余弦取整后的像素点堆叠。
     */
    public static void circle(DrawContext ctx, double cx, double cy, double radius, double width, int color) {
        double aa = feather(ctx), half = aa / 2;
        double inner = width == 0 ? 0 : Math.max(0, radius - width / 2), outer = radius + width / 2;
        int solid = width == 0 ? color : alpha(color, Math.min(1, width / aa)), clear = alpha(color, 0);
        double innerCore = width == 0 ? 0 : Math.min(radius, inner + half);
        double outerCore = width == 0 ? radius - half : Math.max(radius, outer - half);
        int segments = Math.clamp((int) Math.ceil(Math.PI * radius), 64, 256);
        for (int band = 0; band < 3; band++) {
            if (band == 0 && width == 0) continue;
            double r0 = band == 0 ? Math.max(0, inner - half) : band == 1 ? innerCore : outerCore;
            double r1 = band == 0 ? innerCore : band == 1 ? outerCore : outer + half;
            int c0 = band == 0 ? clear : solid, c1 = band == 2 ? clear : solid;
            for (int i = 0; i < segments; i++) {
                double a = i * Math.PI * 2 / segments, b = (i + 1) * Math.PI * 2 / segments;
                double ax = Math.cos(a), ay = Math.sin(a), bx = Math.cos(b), by = Math.sin(b);
                quad(ctx, cx + ax * r0, cy + ay * r0, cx + ax * r1, cy + ay * r1,
                        cx + bx * r1, cy + by * r1, cx + bx * r0, cy + by * r0, c0, c1, c1, c0);
            }
        }
    }

    /**
     * 功能：连续三角指针及外侧覆盖渐变；引用 MC API：无额外 API，复用 GUI 四边形批次。
     * 端侧：渲染线程；参数：三个顶点及颜色；返回：无；副作用：提交三角形和三条边缘。
     * 排查：按顶点绕序计算外法线，不能用逐行像素台阶近似斜边。
     */
    public static void triangle(DrawContext ctx, double ax, double ay, double bx, double by, double cx, double cy, int color) {
        quad(ctx, ax, ay, bx, by, cx, cy, cx, cy, color, color, color, color);
        double aa = feather(ctx), direction = Math.signum((bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
        double[] xs = {ax, bx, cx}, ys = {ay, by, cy};
        int clear = alpha(color, 0);
        for (int i = 0; i < 3; i++) {
            int next = (i + 1) % 3;
            double dx = xs[next] - xs[i], dy = ys[next] - ys[i], length = Math.hypot(dx, dy);
            double nx = dy / length * aa * direction, ny = -dx / length * aa * direction;
            quad(ctx, xs[i], ys[i], xs[i] + nx, ys[i] + ny, xs[next] + nx, ys[next] + ny,
                    xs[next], ys[next], color, clear, clear, color);
        }
    }

    /** 七段字宽；数值居中使用几何字距，不能再用 net.minecraft.client.font.TextRenderer 的位图字宽。 */
    public static double numberWidth(String value) { return Math.max(0, value.length() * DIGIT_PITCH - 1); }

    /**
     * 功能：使用机内同一组七段笔画绘制 HUD 数字，分数滚动位置一直保留到顶点提交。
     * 引用 MC API：无额外 API，复用 rect；端侧：渲染线程；参数：数字串、左上角、颜色；返回：无。
     * 副作用：提交数码管笔画；排查：空格占位但不绘制，符号与小数点独立，不改读数或单位。
     */
    public static void number(DrawContext ctx, String value, double x, double y, int color) {
        for (int column = 0; column < value.length(); column++) {
            char character = value.charAt(column);
            double left = x + column * DIGIT_PITCH;
            if (character >= '0' && character <= '9') {
                int mask = CockpitTapeRenderer.DIGITS[character - '0'];
                for (int bar = 0; bar < CockpitTapeRenderer.BARS.length; bar++) if ((mask & 1 << bar) != 0) {
                    double[] bounds = CockpitTapeRenderer.BARS[bar];
                    rect(ctx, left + bounds[0], y + bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1], color);
                }
            } else if (character == '-' || character == '+') {
                rect(ctx, left + 1, y + 4, 3, .8, color);
                if (character == '+') rect(ctx, left + 2, y + 2.7, .8, 3.4, color);
            } else if (character == '.') rect(ctx, left + 2, y + 8, 1, 1, color);
        }
    }
}
