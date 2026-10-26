/** 文件：机内垂速与高度的方框滚动仪表；端侧：CLIENT；实体：no_24_heli:helicopter。 */
package org.DJB.no_24_heli.client.render;

import java.util.Locale;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

/**
 * 职责：在实体面板重现 HUD 方框、左向指针和高低位数字鼓，保持世界空间遮挡。
 * 引用：net.minecraft.client.util.math.MatrixStack 将逻辑仪表像素映射到机体曲面；
 * net.minecraft.client.render.VertexConsumer 提交经局部裁切的七段数字和刻线。
 * 生命周期：CockpitDashboardRenderer 每帧传入 FlightInstruments 的共用读数后绘制两表。
 * 端侧/线程：客户端渲染线程，只读取同步飞行状态。
 * 排查：滚动方向查 value-mark；进位查高低位是否共用相邻数字；越界查局部 Clip，不能使用屏幕裁剪。
 */
@Environment(EnvType.CLIENT)
public final class CockpitTapeRenderer {
    /** 与屏幕 HUD 共用 58×114 外框、14 像素单位栏和 12 像素刻度间隔。 */
    private static final int WIDTH = 58, HEIGHT = 114, HEADER = 14, SPACING = 12;
    /** 七段数码管的 a/b/c/d/e/f/g 位；机内与第三人称 HUD 的刻度和读数共用字形。 */
    static final int[] DIGITS = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};
    /** 七段笔画只创建一次，避免每架飞机每帧为所有数字重复分配二维数组。 */
    static final double[][] BARS = {{.7, 0, 4.3, .7}, {4.3, .7, 5, 4.2}, {4.3, 4.8, 5, 8.3},
            {.7, 8.3, 4.3, 9}, {0, 4.8, .7, 8.3}, {0, .7, .7, 4.2}, {.7, 4.15, 4.3, 4.85}};
    /** 中央读数缩为原来的 72%，保留数字鼓行距与四周留白。 */
    private static final double READOUT_SCALE = .90;
    /** 数字鼓字高 9、行距 11、字距 6，正数垂速带加号；世界高度只给负值加减号。 */
    private static final double DIGIT_PITCH = 6, ROLL_PITCH = 11;
    private static final int INK = 0xffe6f4ee, BORDER = 0xff557d77, BACKGROUND = 0xff101c1f;
    private static final Vec3d NORMAL = new Vec3d(0, 0, 1);
    /** 裁剪发生在仪表本地平面，模型转头、俯仰和横滚后仍随面板正确遮挡。 */
    private record Clip(double left, double top, double right, double bottom) {}

    /**
     * 功能：绘制单个实体方框仪表；引用 MC API：net.minecraft.client.util.math.MatrixStack 隔离仪表缩放。
     * 端侧：渲染线程；参数：m/b/x/top/width/height/speed/value/light 为环境、尺寸、表类型、平滑读数和光照；返回：无。
     * 副作用：提交世界几何；排查：正值增加时刻度与数字均向下滚动，标题和单位由面板入口绘制。
     */
    public void draw(MatrixStack m, VertexConsumer b, double x, double top, double width, double height,
            boolean speed, double value, int light) {
        m.push(); m.translate(x, top, CockpitDashboardRenderer.FACE_Z + .0005); m.scale((float) (width / WIDTH), (float) (-height / HEIGHT), 1);
        Clip all = new Clip(0, 0, WIDTH, HEIGHT);
        rect(m, b, 0, 0, WIDTH, HEIGHT, 0, BORDER, all, light);
        rect(m, b, 1, 1, WIDTH - 1, HEIGHT - 1, .0002, BACKGROUND, all, light);
        rect(m, b, 0, HEADER, WIDTH, HEADER + 1, .0003, BORDER, all, light);
        Clip tape = new Clip(1, HEADER + 1, WIDTH - 1, HEIGHT - 1);
        int step = speed ? 5 : 50, center = (int) Math.floor(value / step);
        double middle = (HEADER + HEIGHT) / 2.0;
        for (int i = center - 6; i <= center + 6; i++) {
            double y = middle + (value - i * step) / step * SPACING;
            boolean major = i % 2 == 0;
            int brightness = (int) (255 * (1 - .5 * Math.clamp(Math.abs(y - middle) / 50, 0, 1)));
            int color = 0xff000000 | brightness << 16 | brightness << 8 | brightness;
            rect(m, b, 1, y, major ? 11 : 6, y + .7, .0005, color, tape, light);
            if (major) number(m, b, (speed && i > 0 ? "+" : "") + i * step,
                    14, y - 3.5, .70, .0006, color, tape, light);
        }
        readout(m, b, value, speed, middle, light);
        m.pop();
    }

    /**
     * 功能：绘制左向凸字形窗口与连续数字鼓；引用 MC API：无，全部为可局部裁切的平面几何。
     * 端侧：渲染线程；参数：m/b/value/speed/mid/light 为环境、真实读数和中线；返回：无；副作用：提交数字鼓。
     * 排查：高度末两位以 20 米步长滚动；高位只能在矮框中央替换，不能露出多行。
     */
    private static void readout(MatrixStack m, VertexConsumer b, double value, boolean speed, double mid, int light) {
        int step = speed ? 1 : 20, tailWidth = speed ? 1 : 2;
        long lower = (long) Math.floor(value / step) * step;
        long largest = Math.max(Math.abs(lower - step), Math.abs(lower + 2L * step));
        int digits = Math.max(speed ? 3 : 4, Long.toString(largest).length());
        double scale = Math.min(READOUT_SCALE, 39.0 / ((digits + 1) * DIGIT_PITCH));
        double start = 53 - (digits + 1) * DIGIT_PITCH * scale;
        double tailLeft = Math.floor(start + (digits - tailWidth + 1) * DIGIT_PITCH * scale) - 2;
        Clip all = new Clip(0, 0, WIDTH, HEIGHT);
        rect(m, b, 7, mid - 9, 56, mid + 9, .001, INK, all, light);
        rect(m, b, tailLeft, mid - 21, 56, mid + 21, .001, INK, all, light);
        rect(m, b, 8, mid - 8, 55, mid + 8, .0012, 0xff080e13, all, light);
        rect(m, b, tailLeft + 1, mid - 20, 55, mid + 20, .0012, 0xff080e13, all, light);
        for (int row = -6; row <= 6; row++) {
            double edge = Math.abs(row);
            rect(m, b, edge, mid + row, 8, mid + row + 1, .0013, INK, all, light);
            if (edge < 6) rect(m, b, edge + 1, mid + row, 9, mid + row + 1, .0014, 0xff080e13, all, light);
        }
        long centered = Math.round(value / step) * step;
        String sign = centered == 0 ? "" : centered < 0 ? "-" : speed ? "+" : "";
        number(m, b, sign, 10, mid - 4.5 * scale, scale, .0016, INK, all, light);
        String[] rows = new String[4];
        for (int row = 0; row < rows.length; row++)
            rows[row] = String.format(Locale.ROOT, "%" + digits + "d", Math.abs(lower + (row - 1L) * step));
        // 台阶边框比低位字起点提前两像素，不能用它裁高位，否则百位 1 的右侧两笔会被全部切掉。
        Clip high = new Clip(start + DIGIT_PITCH * scale, mid - 7,
                start + (digits - tailWidth + 1) * DIGIT_PITCH * scale, mid + 7);
        for (int column = 0; column < digits - tailWidth; column++) {
            double x = start + (column + 1) * DIGIT_PITCH * scale;
            if (rows[1].charAt(column) == rows[2].charAt(column)) {
                number(m, b, rows[1].substring(column, column + 1), x, mid - 4.5 * scale, scale, .0016, INK, high, light);
            } else for (int row = 1; row <= 2; row++) {
                double y = mid + (value - (lower + (row - 1L) * step)) / step * ROLL_PITCH - 4.5 * scale;
                number(m, b, rows[row].substring(column, column + 1), x, y, scale, .0016, INK, high, light);
            }
        }
        Clip low = new Clip(tailLeft + 1, mid - 20, 55, mid + 20);
        for (int row = 0; row < rows.length; row++) {
            long mark = lower + (row - 1L) * step;
            double y = mid + (value - mark) / step * ROLL_PITCH - 4.5 * scale;
            String tail = speed ? rows[row].substring(digits - 1)
                    : mark == 0 ? " 0" : String.format(Locale.ROOT, "%02d", Math.abs(mark) % 100);
            number(m, b, tail, start + (digits - tailWidth + 1) * DIGIT_PITCH * scale,
                    y, scale, .0016, INK, low, light);
        }
    }

    /**
     * 功能：逐字符绘制可裁切数码管；引用 MC API：无。
     * 端侧：渲染线程；参数：m/b/value/x/y/scale/z/color/clip/light 为环境及数字尺寸；返回：无；副作用：提交数字。
     * 排查：符号独立绘制，空格不生成几何，字距与低位窗口计算须保持一致。
     */
    private static void number(MatrixStack m, VertexConsumer b, String value, double x, double y, double scale,
            double z, int color, Clip clip, int light) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            double left = x + i * DIGIT_PITCH * scale;
            if (character >= '0' && character <= '9') digit(m, b, DIGITS[character - '0'], left, y, scale, z, color, clip, light);
            else if (character == '-' || character == '+') {
                rect(m, b, left + scale, y + 4 * scale, left + 4 * scale, y + 4.8 * scale, z, color, clip, light);
                if (character == '+') rect(m, b, left + 2 * scale, y + 2.7 * scale,
                        left + 2.8 * scale, y + 6.1 * scale, z, color, clip, light);
            }
        }
    }

    /**
     * 功能：绘制单个七段数字；引用 MC API：无。
     * 端侧：渲染线程；参数：m/b/mask/x/y/s/z/color/clip/light 为环境及段掩码；返回：无；副作用：提交最多七段。
     * 排查：数字宽 5、高 9、笔画 0.7，禁止靠整数字符行替代连续几何滚动。
     */
    private static void digit(MatrixStack m, VertexConsumer b, int mask, double x, double y, double s,
            double z, int color, Clip clip, int light) {
        for (int i = 0; i < BARS.length; i++) if ((mask & 1 << i) != 0)
            rect(m, b, x + BARS[i][0] * s, y + BARS[i][1] * s,
                    x + BARS[i][2] * s, y + BARS[i][3] * s, z, color, clip, light);
    }

    /**
     * 功能：在仪表局部平面裁切矩形后提交；引用 MC API：net.minecraft.client.render.VertexConsumer 使用实体顶点属性。
     * 端侧：渲染线程；参数：m/b/x0/y0/x1/y1/z/color/clip/light 为环境、矩形和裁剪边界；返回：无。
     * 副作用：提交有效四边形；排查：先裁切再变换，转头后不能对屏幕轴做裁剪。
     */
    private static void rect(MatrixStack m, VertexConsumer b, double x0, double y0, double x1, double y1,
            double z, int color, Clip clip, int light) {
        double left = Math.max(x0, clip.left), top = Math.max(y0, clip.top);
        double right = Math.min(x1, clip.right), bottom = Math.min(y1, clip.bottom);
        if (left >= right || top >= bottom) return;
        HelicopterModel.vertex(m, b, new Vec3d(left, top, z), NORMAL, color, light, 0, 0);
        HelicopterModel.vertex(m, b, new Vec3d(right, top, z), NORMAL, color, light, 1, 0);
        HelicopterModel.vertex(m, b, new Vec3d(right, bottom, z), NORMAL, color, light, 1, 1);
        HelicopterModel.vertex(m, b, new Vec3d(left, bottom, z), NORMAL, color, light, 0, 1);
    }
}
