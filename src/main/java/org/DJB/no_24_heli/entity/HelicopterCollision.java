/** 文件：直升机旋转分部碰撞、连续扫掠及命中定位；端侧：COMMON，服务端权威。 */
package org.DJB.no_24_heli.entity;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 职责：以模型局部坐标定义多个有向盒，包围盒仅筛选候选，接触及射线都由分离轴判断。
 * 引用 net.minecraft.world.World#getBlockCollisions 读取原版方块碰撞形状，Box 只提供轴对齐候选范围。
 * 生命周期：实体移动、转动、攻击与客户端选取时调用；世界主线程，不创建附属实体或写客户端物理。
 * 排查：空处受撞查是否绕过 sweep/raycast；部位错位查姿态旋转顺序；低速卡地查接触容差和滑橇。
 */
public final class HelicopterCollision {
    /** 整机最远顶点距原点不足七格；扩大候选查询以覆盖跨实体区段的细长尾部。 */
    public static final double QUERY_RADIUS = 7;
    /** 接触容差，单位格；防止贴地或沿墙平移被浮点误差当成新的穿入。 */
    private static final double EPSILON = 1.0e-5;
    /** 静态净空容差大于移动接触误差，单位格；贴地停稳后的微小舍入差不能阻止起飞。 */
    private static final double CLEARANCE_EPSILON = EPSILON * 4;
    /** 降落实体净空每侧保留半格，给途中走入的实体留出余量；单位格，不参与普通碰撞。 */
    private static final double LANDING_ENTITY_MARGIN = .5;
    /** 实际机体扫掠横向留十二厘米净空，不要求旋翼和尾梁下面全部铺平地面。 */
    private static final double LANDING_CLEARANCE = .12;
    /** 两条滑橇前后承重点的地面区域，局部坐标单位格；连续小面支撑避免点采样踩空。 */
    private static final double SUPPORT_HALF_WIDTH = .20, SUPPORT_HALF_LENGTH = .24;
    private static final double[] SUPPORT_SIDES = {-1.05, 1.05}, SUPPORT_ENDS = {-1.2, 1.0};
    /** 目标低于支撑面二十厘米，使物理碰撞能确认真正接地；净空扫掠仅检查至真实触地位置。 */
    private static final double LANDING_TARGET_DEPTH = .2;
    /** 高处部件保留三度稳定误差的竖向余量，机腹只留两厘米，滑橇不膨胀到支撑面里。 */
    private static final double LANDING_TILT_CLEARANCE = .35, LANDING_BODY_CLEARANCE = .02;
    /** 机体角度每半度采样一次，尾端相邻采样位移小于六厘米，避免原地转向跳过薄障碍。 */
    private static final double ROTATION_STEP = .5;
    /** 接地查询深度，单位格；仅用于确认机体结构的真实方块支撑。 */
    private static final double SUPPORT_DEPTH = .08;
    /** 残骸重心位于主机舱，局部坐标单位格；尾梁单点支撑时重心通常在支撑范围之外。 */
    private static final Vec3d WRECK_CENTER_OF_MASS = new Vec3d(0, 1.15, -.25);
    /** 每 tick 最多三次半度倾倒，每轴最大三十度/秒；逐步检查可避免绕接触点旋转时穿过薄方块。 */
    private static final int WRECK_SETTLE_STEPS = 3;
    /** 接触求解保留一毫米净距，超过分离轴容差，避免倾倒后的浅嵌入被后续扫掠当成可离开的旧重叠。 */
    private static final double WRECK_CONTACT_CLEARANCE = .001;
    /** 原版世界轴，用于有向盒与轴对齐方块之间的十五轴分离检测。 */
    private static final Vec3d[] WORLD_AXES = {new Vec3d(1, 0, 0), new Vec3d(0, 1, 0), new Vec3d(0, 0, 1)};

    /** 部位对应现有机身、引擎、油箱、主旋翼和尾部耐久，不额外引入存档槽。 */
    public enum Part { CABIN, ENGINE, FUEL_TANK, TAIL_BOOM, TAIL_ROTOR, MAIN_ROTOR, LANDING_GEAR }

    /** 姿态单位为度；顺序与机模一致：偏航、俯仰、横滚。 */
    public record Pose(double yaw, double pitch, double roll) {
        /** net.minecraft.util.math.MathHelper 包装偏航差，避免跨 ±180° 时反向扫过整圈。 */
        public Pose interpolate(Pose target, double fraction) {
            return new Pose(yaw + MathHelper.wrapDegrees(target.yaw - yaw) * fraction,
                    pitch + (target.pitch - pitch) * fraction, roll + (target.roll - roll) * fraction);
        }
        /** 只旋转局部向量，绝不把大世界坐标转换为单精度。 */
        public Quaternionf rotation() {
            return new Quaternionf().rotationY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(-pitch)).rotateZ((float) Math.toRadians(roll));
        }
    }

    /** 射线的真实部位、世界命中点和距起点平方距离，供近战和原版投射物共用。 */
    public record Hit(Part part, Vec3d position, double distanceSquared) {}
    /** 两个实体接触的双方部位；普通生物没有机体部位，otherPart 为 null。 */
    public record Contact(Part part, Part otherPart) {}

    /** 同一实体姿态未变化时复用分部盒和分离轴；存于实体自身，卸载即释放，不建立全局世界缓存。 */
    record Cache(Pose pose, Vec3d origin, float rotorAngle, float gear, double rpm, double rotor, double tail,
                 boolean wreck, boolean doorsPresent, float leftFrontDoor, float rightFrontDoor, float leftRearDoor, float rightRearDoor,
                 List<Shape> shapes, Box bounds, Box cabinBounds) {}

    /** 一个有向盒的世界中心、半尺寸和正交基；bounds 只是宽相位候选范围。 */
    public record Shape(Part part, Vec3d center, Vec3d half, Quaternionf rotation, Vec3d[] axes, Vec3d[] collisionAxes, Box bounds) {
        /** 复用已算好的正交基平移盒，供轴向逐段移动时重用，避免重建另一架飞机的整套几何。 */
        private Shape offset(Vec3d delta) {
            return new Shape(part, center.add(delta), half, rotation, axes, collisionAxes, bounds.offset(delta));
        }
        /** 将世界点投影到本盒正交基，供 net.minecraft.util.math.Box#raycast 精确裁剪线段。 */
        private Vec3d local(Vec3d point) {
            Vec3d relative = point.subtract(center);
            return new Vec3d(relative.dotProduct(axes[0]), relative.dotProduct(axes[1]), relative.dotProduct(axes[2]));
        }
        /** 返回本盒在任意轴上的投影半径。 */
        private double radius(Vec3d axis) {
            return Math.abs(axis.dotProduct(axes[0])) * half.x + Math.abs(axis.dotProduct(axes[1])) * half.y
                    + Math.abs(axis.dotProduct(axes[2])) * half.z;
        }
        /**
         * 功能：连续扫掠本盒相对静止方块的平移，返回首次碰撞比例；1 表示整段可走。
         * 引用 net.minecraft.util.math.Box#getCenter 获取方块分解盒中心；不按离散速度采样，避免高速穿墙。
         * 已经重叠的外部传送状态允许离开；正常入侵由移动和旋转入口提前阻止。
         */
        public double sweep(Box target, Vec3d movement) {
            if (!bounds.stretch(movement).expand(EPSILON).intersects(target)) return 1;
            Vec3d relative = center.subtract(target.getCenter());
            Vec3d extent = new Vec3d(target.getLengthX() / 2, target.getLengthY() / 2, target.getLengthZ() / 2);
            double enter = Double.NEGATIVE_INFINITY, leave = Double.POSITIVE_INFINITY;
            for (Vec3d axis : collisionAxes) {
                double radius = radius(axis) + Math.abs(axis.x) * extent.x + Math.abs(axis.y) * extent.y
                        + Math.abs(axis.z) * extent.z - EPSILON;
                double distance = relative.dotProduct(axis), speed = movement.dotProduct(axis);
                if (Math.abs(speed) < EPSILON) {
                    if (Math.abs(distance) >= radius) return 1;
                } else {
                    double first = (-radius - distance) / speed, last = (radius - distance) / speed;
                    enter = Math.max(enter, Math.min(first, last)); leave = Math.min(leave, Math.max(first, last));
                    if (enter > leave) return 1;
                }
            }
            return enter < -EPSILON || leave < 0 || enter >= 1 ? 1 : Math.max(0, enter);
        }
        /** 静态十五轴相交；接触面与相交体区分，支持安全旋转及放置。 */
        public boolean intersects(Box target) {
            if (!bounds.intersects(target)) return false;
            Vec3d relative = center.subtract(target.getCenter());
            for (Vec3d axis : collisionAxes) {
                double radius = radius(axis) + Math.abs(axis.x) * target.getLengthX() / 2
                        + Math.abs(axis.y) * target.getLengthY() / 2 + Math.abs(axis.z) * target.getLengthZ() / 2;
                if (Math.abs(relative.dotProduct(axis)) >= radius - CLEARANCE_EPSILON) return false;
            }
            return true;
        }
        /** 有向盒之间也用十五轴检测，避免两架飞机的候选盒空处互相伤害。 */
        public boolean intersects(Shape target) {
            return sweep(target, Vec3d.ZERO) == 0;
        }
        /** 两个有向盒的连续平移扫掠；静态调用返回零表示有体积交叠。 */
        public double sweep(Shape target, Vec3d movement) {
            if (!bounds.stretch(movement).expand(EPSILON).intersects(target.bounds)) return 1;
            List<Vec3d> separating = new ArrayList<>(15);
            separating.addAll(List.of(axes)); separating.addAll(List.of(target.axes));
            for (Vec3d a : axes) for (Vec3d b : target.axes) {
                Vec3d cross = a.crossProduct(b);
                // HACK(1.21.1): Vec3d#normalize 的 1e-4 截断会生成零轴；其阈值统一到 EPSILON 后可移除此绕过。
                if (cross.lengthSquared() > EPSILON * EPSILON)
                    separating.add(cross.multiply(1 / Math.sqrt(cross.lengthSquared())));
            }
            Vec3d relative = center.subtract(target.center);
            double enter = Double.NEGATIVE_INFINITY, leave = Double.POSITIVE_INFINITY;
            for (Vec3d axis : separating) {
                double radius = radius(axis) + target.radius(axis) - EPSILON;
                double distance = relative.dotProduct(axis), speed = movement.dotProduct(axis);
                if (Math.abs(speed) < EPSILON) { if (Math.abs(distance) >= radius) return 1; }
                else {
                    double first = (-radius - distance) / speed, last = (radius - distance) / speed;
                    enter = Math.max(enter, Math.min(first, last)); leave = Math.min(leave, Math.max(first, last));
                    if (enter > leave) return 1;
                }
            }
            if (movement.lengthSquared() == 0) return 0;
            return enter < -EPSILON || leave < 0 || enter >= 1 ? 1 : Math.max(0, enter);
        }
    }

    /** 仅静态几何工具，不实例化。 */
    private HelicopterCollision() {}

    /** 客户端采用已有平滑姿态，服务端采用权威姿态；构造父类时 FlightState 尚未初始化则使用水平姿态。 */
    public static Pose pose(HelicopterEntity heli) {
        if (heli.flight == null) return new Pose(heli.getYaw(), 0, 0);
        return new Pose(heli.getYaw(), heli.getWorld().isClient ? heli.visualPitch : heli.flight.pitch,
                heli.getWorld().isClient ? heli.visualRoll : heli.flight.roll);
    }

    /** 把格数局部向量旋转后保留双精度世界平移，供渲染和碰撞公用。 */
    private static Vec3d rotate(Vec3d value, Quaternionf rotation) {
        Vector3f result = new Vector3f((float) value.x, (float) value.y, (float) value.z).rotate(rotation);
        return new Vec3d(result.x, result.y, result.z);
    }

    /** 构建一组当前姿态下的真实分部盒，调用者可在一轮方块查询中复用。 */
    public static List<Shape> shapes(HelicopterEntity heli) {
        Pose pose = pose(heli); Vec3d origin = heli.getPos(); Cache cache = heli.collisionCache;
        float gear = heli.flight == null ? 1 : heli.gearExtension;
        double rpm = heli.flight == null ? 0 : heli.flight.rpm, rotor = heli.flight == null ? 100 : heli.flight.rotor;
        double tail = heli.flight == null ? 100 : heli.flight.tail; boolean wreck = heli.flight != null && heli.flight.wreck;
        float leftFront=heli.cabin==null?0:heli.cabin.doorProgress(0,0),rightFront=heli.cabin==null?0:heli.cabin.doorProgress(1,0);
        float leftRear=heli.cabin==null?0:heli.cabin.doorProgress(2,0),rightRear=heli.cabin==null?0:heli.cabin.doorProgress(3,0);
        if (cache != null && cache.leftFrontDoor==leftFront && cache.rightFrontDoor==rightFront
                && cache.leftRearDoor==leftRear && cache.rightRearDoor==rightRear
                && cache.pose.equals(pose) && cache.origin.equals(origin) && cache.rotorAngle == heli.rotorAngle
                && cache.gear == gear && cache.rpm == rpm && cache.rotor == rotor && cache.tail == tail && cache.wreck == wreck
                && cache.doorsPresent == heli.hasCabinDoors())
            return cache.shapes;
        List<Shape> result = List.copyOf(shapes(heli, pose, origin));
        Box cabin = bounds(result.stream().filter(shape -> shape.part == Part.CABIN || shape.part == Part.FUEL_TANK).toList());
        heli.collisionCache = new Cache(pose, origin, heli.rotorAngle, gear, rpm, rotor, tail, wreck, heli.hasCabinDoors(),
                leftFront, rightFront, leftRear, rightRear, result, bounds(result), cabin);
        return result;
    }

    /**
     * 功能：按机模包络分段构造机舱、鼻锥、动力罩、油箱及尾根、尾梁、尾翼、双桨及滑橇。
     * 引用 net.minecraft.util.math.Box 保存局部几何；仅查询状态，COMMON 主线程，不写实体位置。
     * 旋翼转动时使用九条薄盒近似扫掠圆盘，停转时保留四片实际桨叶，圆盘角落不会充满整方盒。
     */
    public static List<Shape> shapes(HelicopterEntity heli, Pose pose, Vec3d origin) {
        return shapes(heli, pose, origin, false);
    }

    /** 任务净空使用放架和转动桨盘的最大包络；普通碰撞继续按当前动画状态构造。 */
    private static List<Shape> shapes(HelicopterEntity heli, Pose pose, Vec3d origin, boolean flightEnvelope) {
        List<Shape> result = new ArrayList<>(); Quaternionf body = pose.rotation();
        add(result, Part.CABIN, new Box(-.78, .53, -.92, .78, 1.77, .52), origin, body);
        add(result, Part.CABIN, new Box(-.63, 1.77, -.75, .63, 2.04, .46), origin, body);
        add(result, Part.CABIN, new Box(-.67, .61, .52, .67, 1.75, 1.13), origin, body);
        add(result, Part.CABIN, new Box(-.48, .64, 1.13, .48, 1.43, 1.62), origin, body);
        add(result, Part.CABIN, new Box(-.26, .74, 1.62, .26, 1.10, 1.95), origin, body);
        add(result, Part.CABIN, new Box(-.17, .12, 1.05, .17, .56, 1.43), origin, body);
        // 油箱归入机舱后部隔舱；只变更命中部位映射，不改变现有外部碰撞包络。
        add(result, Part.FUEL_TANK, new Box(-.68, .59, -1.30, .68, 1.67, -.92), origin, body);
        add(result, Part.CABIN, new Box(.78, .67, -.80, .95, 1.28, .39), origin, body);
        add(result, Part.CABIN, new Box(-.95, .67, -.80, -.78, 1.28, .39), origin, body);
        addDoors(result,heli,origin,body);
        add(result, Part.ENGINE, new Box(-.43, 2.02, -1.23, .43, 2.54, .20), origin, body);
        add(result, Part.ENGINE, new Box(-.065, 2.18, -.235, .065, 2.71, -.105), origin, body);
        // 紧接机舱的四段尾根归入油箱命中区，沿用油箱附带机身损伤；复用原曲面包络。
        for (Box box : HelicopterGeometry.TAIL_ROOT_BOUNDS) add(result, Part.FUEL_TANK, box, origin, body);
        add(result, Part.TAIL_BOOM, new Box(-.18, 1.36, -3.40, .18, 1.85, -2.18), origin, body);
        add(result, Part.TAIL_BOOM, new Box(-.13, 1.56, -4.35, .13, 1.95, -3.40), origin, body);
        add(result, Part.TAIL_BOOM, new Box(-.10, 1.72, -5.07, .10, 1.98, -4.35), origin, body);
        // 水平尾翼与模型同步前移 .20 格，后缘 z=-4.15 避开尾桨完整扫掠包络。
        add(result, Part.TAIL_BOOM, new Box(-1.08, 1.755, -4.15, 1.08, 1.845, -3.57), origin, body);
        for (int i = 0; i < 4; i++) {
            double low = 1.50 + i * .375, t = (i + .5) / 4, back = -5.0 - .55 * t;
            double chord = .24 + .20 * Math.sin(Math.PI * t) - .17 * t;
            add(result, Part.TAIL_BOOM, new Box(-.05, low, back - chord, .05, low + .375, back + chord), origin, body);
        }
        double stow = flightEnvelope || heli.flight == null ? 0 : 1 - heli.gearExtension;
        for (double side : new double[]{-1, 1}) {
            add(result, Part.LANDING_GEAR, new Box(side * 1.05 - .045, .075 + .32 * stow, -1.47 - .45 * stow,
                    side * 1.05 + .045, .165 + .32 * stow, 1.23 - .45 * stow), origin, body);
            add(result, Part.LANDING_GEAR, new Box(side * 1.05 - .045, .12 + .32 * stow, 1.23 - .45 * stow,
                    side * 1.05 + .045, .475 + .32 * stow, 1.89 - .45 * stow), origin, body);
        }
        if (heli.flight == null || !heli.flight.wreck && heli.flight.rotor > 0) {
            rotor(result, Part.MAIN_ROTOR, new Vec3d(0, 2.71, -.17), 3.70, .06,
                    heli.rotorAngle, flightEnvelope || heli.flight != null && heli.flight.rpm > .05, origin, body, false);
        }
        if (heli.flight == null || !heli.flight.wreck && heli.flight.tail > 0) {
            rotor(result, Part.TAIL_ROTOR, new Vec3d(.23, 1.84, -4.94), .65, .055,
                    heli.rotorAngle * 2, flightEnvelope || heli.flight != null && heli.flight.rpm > .05, origin, body, true);
        }
        return result;
    }

    /** 四扇门用三段薄盒近似原曲面；net.minecraft.util.math.Box 随渲染的同一铰链和开角旋转。 */
    private static void addDoors(List<Shape> result,HelicopterEntity heli,Vec3d origin,Quaternionf body){
        if (!heli.hasCabinDoors()) return;
        for(int door=0;door<HelicopterCabin.SEATS;door++){
            float progress=heli.cabin==null?0:heli.cabin.doorProgress(door,0);
            if(progress<=0)continue;
            Vec3d hinge=HelicopterCabin.doorHinge(door);
            Quaternionf rotation=new Quaternionf(body).rotateY(HelicopterCabin.doorAngle(door,progress));
            Vec3d pivot=origin.add(rotate(hinge,body));
            for(int band=0;band<3;band++){
                double inner=band==1?.84:.58,outer=band==1?.99:.94;
                double low=band==0?.64:band==1?.95:1.50,high=band==0?.95:band==1?1.50:1.84;
                double minX=HelicopterCabin.side(door)==0?inner:-outer;
                double maxX=HelicopterCabin.side(door)==0?outer:-inner;
                add(result,Part.CABIN,new Box(minX-hinge.x,low-hinge.y,HelicopterCabin.doorBack(door)-hinge.z,
                        maxX-hinge.x,high-hinge.y,HelicopterCabin.doorFront(door)-hinge.z),pivot,rotation);
            }
        }
    }

    /** 转动桨盘为薄分段圆盘；停转桨叶以轴上四盒随真实桨叶角度旋转，不产生圆盘角落假碰撞。 */
    private static void rotor(List<Shape> result, Part part, Vec3d pivot, double radius, double thickness,
            float angle, boolean spinning, Vec3d origin, Quaternionf body, boolean tail) {
        Quaternionf plane = new Quaternionf(body);
        if (tail) plane.rotateY((float) (Math.PI / 2)).rotateX((float) (Math.PI / 2));
        Vec3d center = origin.add(rotate(pivot, body));
        double hub = tail ? .075 : .20;
        add(result, part, new Box(-hub, -thickness, -hub, hub, thickness, hub), center, plane);
        if (spinning) {
            for (int i = 0; i < 9; i++) {
                double x0 = -radius + radius * 2 * i / 9, x1 = -radius + radius * 2 * (i + 1) / 9;
                double nearest = x0 <= 0 && x1 >= 0 ? 0 : Math.min(Math.abs(x0), Math.abs(x1));
                double length = Math.sqrt(radius * radius - nearest * nearest);
                add(result, part, new Box(x0, -thickness, -length, x1, thickness, length), center, plane);
            }
        } else for (int i = 0; i < 4; i++) {
            Quaternionf blade = new Quaternionf(plane).rotateY((float) Math.toRadians(angle + i * 90));
            double chord = tail ? .05 : .085;
            add(result, part, new Box(.08, -thickness, -chord, radius, thickness, chord), center, blade);
        }
    }

    /** 从局部盒生成有向盒及保守世界范围；net.minecraft.util.math.Box 的轴对齐性质仅限此候选范围。 */
    private static void add(List<Shape> result, Part part, Box local, Vec3d origin, Quaternionf rotation) {
        result.add(orientedBox(part, local, origin, rotation));
    }

    /**
     * 功能：为机体与跳伞者构造共用的有向盒，net.minecraft.util.math.Box 只保存局部体积及世界候选范围。
     * COMMON 主线程只读计算；真正移动必须使用 Shape#sweep，不能把旋转后的候选盒当成实心体积。
     */
    public static Shape orientedBox(Part part, Box local, Vec3d origin, Quaternionf rotation) {
        Vec3d[] axes = {rotate(WORLD_AXES[0], rotation), rotate(WORLD_AXES[1], rotation), rotate(WORLD_AXES[2], rotation)};
        Vec3d half = new Vec3d(local.getLengthX() / 2, local.getLengthY() / 2, local.getLengthZ() / 2);
        Vec3d center = origin.add(rotate(local.getCenter(), rotation));
        Vec3d extent = new Vec3d(Math.abs(axes[0].x) * half.x + Math.abs(axes[1].x) * half.y + Math.abs(axes[2].x) * half.z,
                Math.abs(axes[0].y) * half.x + Math.abs(axes[1].y) * half.y + Math.abs(axes[2].y) * half.z,
                Math.abs(axes[0].z) * half.x + Math.abs(axes[1].z) * half.y + Math.abs(axes[2].z) * half.z);
        List<Vec3d> collisionAxes = new ArrayList<>(15);
        collisionAxes.addAll(List.of(WORLD_AXES)); collisionAxes.addAll(List.of(axes));
        for (Vec3d a : axes) for (Vec3d b : WORLD_AXES) {
            Vec3d cross = a.crossProduct(b);
            // HACK(1.21.1): 绕过 net.minecraft.util.math.Vec3d#normalize 的 1e-4 截断，防止零分离轴漏检。
            if (cross.lengthSquared() > EPSILON * EPSILON)
                collisionAxes.add(cross.multiply(1 / Math.sqrt(cross.lengthSquared())));
        }
        return new Shape(part, center, half, new Quaternionf(rotation), axes, collisionAxes.toArray(Vec3d[]::new),
                new Box(center.subtract(extent), center.add(extent)));
    }

    /** 真实整机候选范围包含尾鳍和桨盘，随偏航、俯仰和横滚更新。 */
    public static Box bounds(HelicopterEntity heli) { shapes(heli); return heli.collisionCache.bounds; }

    /** 合并一组分部盒的宽相位范围。 */
    private static Box bounds(List<Shape> shapes) {
        Box result = shapes.getFirst().bounds;
        for (int i = 1; i < shapes.size(); i++) result = result.union(shapes.get(i).bounds);
        return result;
    }

    /** 原版水流/火焰等环境检查仅使用机舱，不让长尾或高处桨盘扩大浸水范围。 */
    public static Box cabinBounds(HelicopterEntity heli) {
        shapes(heli); return heli.collisionCache.cabinBounds;
    }

    /** 读取原版方块碰撞形状并分解为真实组成盒；保留台阶、围栏及非完整方块形状。 */
    private static List<Box> obstacles(HelicopterEntity heli, Box area) {
        List<Box> result = new ArrayList<>();
        for (var shape : heli.getWorld().getBlockCollisions(heli, area.expand(EPSILON))) result.addAll(shape.getBoundingBoxes());
        return result;
    }

    /** 原版移动入口的碰撞裁剪，先垂直再按水平主轴，保留沿墙滑动与原版接地状态更新。 */
    public static Vec3d adjustMovement(HelicopterEntity heli, Vec3d movement) {
        List<Shape> shapes = shapes(heli); List<Box> blocks = obstacles(heli, bounds(shapes).stretch(movement));
        List<Shape> aircraft = new ArrayList<>();
        for (HelicopterEntity other : candidates(heli.getWorld(), heli, bounds(shapes).stretch(movement))) aircraft.addAll(shapes(other));
        Vec3d moved = Vec3d.ZERO;
        int[] order = Math.abs(movement.x) < Math.abs(movement.z) ? new int[]{1, 0, 2} : new int[]{1, 2, 0};
        for (int axis : order) {
            double amount = axis == 0 ? movement.x : axis == 1 ? movement.y : movement.z;
            if (amount == 0) continue;
            Vec3d delta = WORLD_AXES[axis].multiply(amount); double fraction = 1, rotorFraction = 1;
            Part contact = Part.CABIN;
            Vec3d shift = moved.negate();
            List<Box> shiftedBlocks = blocks.stream().map(block -> block.offset(shift)).toList();
            List<Shape> shiftedAircraft = aircraft.stream().map(other -> other.offset(shift)).toList();
            for (Shape shape : shapes) for (Box block : shiftedBlocks) {
                double hit = shape.sweep(block, delta);
                if (shape.part == Part.MAIN_ROTOR) rotorFraction = Math.min(rotorFraction, hit);
                else if (hit < fraction) { fraction = hit; contact = shape.part; }
            }
            double aircraftFraction = 1;
            for (Shape shape : shapes) for (Shape other : shiftedAircraft)
                aircraftFraction = Math.min(aircraftFraction, shape.sweep(other, delta));
            if (rotorFraction < 1 && rotorFraction <= Math.min(fraction, aircraftFraction)) {
                if (!heli.breakMainRotorOnBlock(Math.abs(amount))) { fraction = rotorFraction; contact = Part.MAIN_ROTOR; }
                else {
                    shapes = shapes.stream().filter(shape -> shape.part != Part.MAIN_ROTOR).toList();
                    aircraftFraction = 1;
                    for (Shape shape : shapes) for (Shape other : shiftedAircraft)
                        aircraftFraction = Math.min(aircraftFraction, shape.sweep(other, delta));
                }
            }
            if (fraction < 1 && fraction <= aircraftFraction) heli.recordBlockImpact(contact, Math.abs(amount));
            fraction = Math.min(fraction, aircraftFraction);
            moved = moved.add(delta.multiply(fraction));
        }
        // 世界边界是四个轴向平面；只限制朝边界外的位移，已经越界的传送实体仍能返回。
        var border = heli.getWorld().getWorldBorder(); Box box = bounds(shapes);
        double x = moved.x, z = moved.z;
        if (x > 0) x = Math.min(x, Math.max(0, border.getBoundEast() - box.maxX));
        if (x < 0) x = Math.max(x, Math.min(0, border.getBoundWest() - box.minX));
        if (z > 0) z = Math.min(z, Math.max(0, border.getBoundSouth() - box.maxZ));
        if (z < 0) z = Math.max(z, Math.min(0, border.getBoundNorth() - box.minZ));
        return new Vec3d(x, moved.y, z);
    }

    /** 检查机体结构与起落架在八厘米内的方块支撑，尾梁先触地也需判定倾斜事故；旋翼和空角不作支撑。 */
    public static boolean supported(HelicopterEntity heli) {
        List<Shape> shapes = shapes(heli); Vec3d down = new Vec3d(0, -SUPPORT_DEPTH, 0);
        List<Box> blocks = obstacles(heli, bounds(shapes).stretch(down));
        for (Shape shape : shapes) if (shape.part != Part.MAIN_ROTOR && shape.part != Part.TAIL_ROTOR)
            for (Box block : blocks) if (shape.sweep(block, down) < 1) return true;
        return false;
    }

    /**
     * 功能：残骸绕实际承重点倾倒，只接受重心下降且不穿入方块或其他飞机的姿态。
     * 引用 net.minecraft.entity.Entity#setPosition 提交已检查的接触约束位置；不是完好飞机的飞行移动旁路。
     * 仅逻辑服务端接地后调用；角度与位置一起变化，不能绕实体原点硬扶正而让尾梁卡住地面。
     * groundImpact 表示本 tick 向下移动实际受阻，先恢复接触净距，避免上一轮接触容差形成旧重叠。
     * 排查：单尾支撑不倒查支点是否来自实际下扫；穿地查每半度候选及重心下降检查。
     */
    public static void settleWreck(HelicopterEntity heli, boolean groundImpact) {
        if (!heli.flight.wreck) return;
        if (groundImpact) {
            Vec3d clear = heli.getPos().add(0, WRECK_CONTACT_CLEARANCE, 0);
            if (wreckSpaceEmpty(heli, pose(heli), clear)) heli.setPosition(clear);
        }
        if (!supported(heli)) return;
        for (int step = 0; step < WRECK_SETTLE_STEPS; step++) {
            Pose previous = pose(heli); Vec3d origin = heli.getPos();
            Vec3d center = origin.add(rotate(WRECK_CENTER_OF_MASS, previous.rotation()));
            Vec3d support = wreckSupport(heli, shapes(heli), center);
            if (support == null) return;
            Vec3d pivot = rotate(support.subtract(origin), previous.rotation().conjugate());
            Pose selected = previous; Vec3d position = origin; double bestDrop = EPSILON;
            for (int pitch = -1; pitch <= 1; pitch++) for (int roll = -1; roll <= 1; roll++) {
                if (pitch == 0 && roll == 0) continue;
                Pose candidate = new Pose(previous.yaw,
                        Math.clamp(previous.pitch + pitch * ROTATION_STEP, -180, 180),
                        Math.clamp(previous.roll + roll * ROTATION_STEP, -180, 180));
                Vec3d target = support.subtract(rotate(pivot, candidate.rotation())).add(0, WRECK_CONTACT_CLEARANCE, 0);
                double drop = center.y - target.add(rotate(WRECK_CENTER_OF_MASS, candidate.rotation())).y;
                if (drop <= bestDrop || !wreckSpaceEmpty(heli, candidate, target)) continue;
                selected = candidate; position = target; bestDrop = drop;
            }
            if (selected == previous) return;
            heli.flight.pitch = selected.pitch; heli.flight.roll = selected.roll;
            heli.setPosition(position);
        }
    }

    /**
     * 功能：从八厘米下扫真正命中的结构顶点中选取靠近重心的承重点，不能使用整机 AABB 的空角。
     * net.minecraft.util.math.Box 提供方块承重面及范围；夹紧平面坐标兼容宽底面覆盖窄支撑的情况。
     * COMMON 主线程只读；找不到实际承重点则不施加地面倾倒，下一 tick 继续普通重力下落。
     */
    private static Vec3d wreckSupport(HelicopterEntity heli, List<Shape> shapes, Vec3d center) {
        List<Box> blocks = obstacles(heli, bounds(shapes).expand(SUPPORT_DEPTH));
        Vec3d support = null; double nearest = Double.POSITIVE_INFINITY;
        for (Shape shape : shapes) for (Box block : blocks) {
            if (shape.part == Part.MAIN_ROTOR || shape.part == Part.TAIL_ROTOR
                    || shape.sweep(block, new Vec3d(0, -SUPPORT_DEPTH, 0)) >= 1) continue;
            for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) {
                Vec3d vertex = shape.center.add(shape.axes[0].multiply(x * shape.half.x))
                        .add(shape.axes[1].multiply(y * shape.half.y)).add(shape.axes[2].multiply(z * shape.half.z));
                if (Math.abs(vertex.y - block.maxY) > SUPPORT_DEPTH + EPSILON) continue;
                Vec3d point = new Vec3d(Math.clamp(vertex.x, block.minX, block.maxX), block.maxY,
                        Math.clamp(vertex.z, block.minZ, block.maxZ));
                double distance = MathHelper.square(point.x - center.x) + MathHelper.square(point.z - center.z);
                if (distance < nearest) { nearest = distance; support = point; }
            }
        }
        return support;
    }

    /** 残骸半度候选按实际部位检查方块及其他飞机；net.minecraft.world.World 的候选盒只负责筛选范围。 */
    private static boolean wreckSpaceEmpty(HelicopterEntity heli, Pose pose, Vec3d origin) {
        List<Shape> shapes = shapes(heli, pose, origin); Box area = bounds(shapes);
        for (Box block : obstacles(heli, area)) for (Shape shape : shapes) if (shape.intersects(block)) return false;
        for (HelicopterEntity other : candidates(heli.getWorld(), heli, area))
            for (Shape shape : shapes) for (Shape target : shapes(other)) if (shape.intersects(target)) return false;
        return heli.getWorld().getWorldBorder().contains(area);
    }

    /** 放置时检查真实分部盒；已经重叠的玩家由 preparePlacement 登记临时豁免，其他实体仍阻挡。 */
    public static boolean isSpaceEmpty(HelicopterEntity heli, Entity placingEntity) {
        List<Shape> shapes = shapes(heli);
        for (Box block : obstacles(heli, bounds(shapes))) for (Shape shape : shapes) if (shape.intersects(block)) return false;
        for (Entity other : heli.getWorld().getOtherEntities(heli, bounds(shapes).expand(QUERY_RADIUS),
                other -> other != placingEntity && !(other instanceof PlayerEntity) && !other.isSpectator()
                        && !heli.isConnectedThroughVehicle(other) && other.canHit())) {
            if (other instanceof HelicopterEntity aircraft) {
                for (Shape shape : shapes) for (Shape target : shapes(aircraft)) if (shape.intersects(target)) return false;
            } else for (Shape shape : shapes) if (shape.intersects(other.getBoundingBox())) return false;
        }
        return heli.getWorld().getWorldBorder().contains(bounds(shapes));
    }

    /**
     * 功能：检查自动起降的完整竖直通道，以转动桨盘和放下的起落架覆盖任务中的最大包络。
     * 引用 MC API：net.minecraft.world.World#getBlockCollisions 提供真实障碍；有向盒连续扫掠排除空角。
     * 端侧：服务端主线程；参数：heli 为当前飞机，height 为带方向的升降格数；返回：整段是否安全。
     * 副作用：无，不修改旋翼、起落架或实体位置；排查：地面接触只阻止向下穿入，不阻止向上离开。
     */
    public static boolean verticalClearance(HelicopterEntity heli, double height) {
        Vec3d movement = new Vec3d(0, height, 0);
        List<Shape> parts = shapes(heli, pose(heli), heli.getPos(), true);
        Box area = bounds(parts).stretch(movement);
        if (!heli.getWorld().getWorldBorder().contains(area)) return false;
        List<Box> blocks = obstacles(heli, area);
        for (Shape part : parts) for (Box block : blocks) {
            // 旧飞机触地后展开滑橇可能浅嵌承重面；只允许从滑橇下半部向上退出，侧墙、顶板和机身重叠仍拒绝。
            boolean leavingSupport = height > 0 && part.part == Part.LANDING_GEAR
                    && block.maxY <= part.center.y + CLEARANCE_EPSILON;
            if ((part.intersects(block) && !leavingSupport) || part.sweep(block, movement) < 1) return false;
        }
        for (Entity other : heli.getWorld().getOtherEntities(heli, area.expand(QUERY_RADIUS),
                other -> isFlightEntity(heli, other))) {
            if (other instanceof HelicopterEntity aircraft) {
                for (Shape part : parts) for (Shape target : shapes(aircraft))
                    if (part.intersects(target) || part.sweep(target, movement) < 1) return false;
            } else for (Shape part : parts)
                if (part.intersects(other.getBoundingBox()) || part.sweep(other.getBoundingBox(), movement) < 1) return false;
        }
        return true;
    }

    /**
     * 功能：起降通道共用的外部实体筛选；引用 net.minecraft.entity.Entity#getRootVehicle 识别本机绳索乘员。
     * 端侧：服务端主线程；参数：heli 为本机，other 为候选；返回：是否需检查；副作用：无。
     * 排查：只排除旁观者、已移除实体、本机乘员/绳索，以及放置后尚未走出的特定玩家。
     */
    private static boolean isFlightEntity(HelicopterEntity heli, Entity other) {
        return !other.isRemoved() && !other.isSpectator() && !heli.isConnectedThroughVehicle(other)
                && !heli.ignoresPlacementCollision(other)
                && !(other.getRootVehicle() instanceof RopeSeatEntity rope && rope.helicopter() == heli);
    }

    /**
     * 功能：用滑橇四个承重点寻找同高支撑，再检查实际部件的下降通道。
     * 引用 net.minecraft.world.World#raycast 取得候选高度，支撑面的连续性另由面积差集验证。
     * 服务端主线程只读；返回控制目标高度，无安全支撑返回 NaN；不要求机身中心或尾翼下方有地面。
     */
    public static double landingTargetY(HelicopterEntity heli) {
        double surface = Double.NaN;
        Quaternionf rotation = new Pose(heli.getYaw(), 0, 0).rotation();
        for (double side : SUPPORT_SIDES) for (double end : SUPPORT_ENDS) {
            Vec3d start = heli.getPos().add(rotate(new Vec3d(side, .5, end), rotation));
            if (!heli.getWorld().isChunkLoaded(BlockPos.ofFloored(start))) return Double.NaN;
            var hit = heli.getWorld().raycast(new RaycastContext(start,
                    new Vec3d(start.x, heli.getWorld().getBottomY(), start.z),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, heli));
            if (hit.getType() == HitResult.Type.MISS
                    || Double.isFinite(surface) && Math.abs(surface - hit.getPos().y) > CLEARANCE_EPSILON)
                return Double.NaN;
            surface = hit.getPos().y;
        }
        double target = surface - LANDING_TARGET_DEPTH;
        return landingTerrainClear(heli, target, heli.getX(), heli.getZ()) ? target : Double.NaN;
    }

    /**
     * 功能：分开验证滑橇平整支撑与实际机体净空，允许尾梁、桨盘下方悬空或存在不接触机体的矮物。
     * 引用 net.minecraft.world.World#getBlockCollisions 与有向盒扫掠检查每个部件，VoxelShapes 差集检查承重面。
     * 服务端每 tick 只读；支撑必须干燥同高，主尾桨、机身、展开滑橇均需具有十二厘米横向余量。
     * 排查：净空检查到滑橇真实触地高度，不能扫到用于总距控制的地下目标而误报地面阻挡。
     */
    public static boolean landingTerrainClear(HelicopterEntity heli, double targetY, double anchorX, double anchorZ) {
        double surface = targetY + LANDING_TARGET_DEPTH;
        Pose level = new Pose(heli.getYaw(), 0, 0);
        Vec3d anchor = new Vec3d(anchorX, heli.getY(), anchorZ);
        List<Shape> parts = shapes(heli, level, anchor, true);
        double skidBottom = parts.stream().filter(part -> part.part == Part.LANDING_GEAR)
                .mapToDouble(part -> part.bounds.minY - heli.getY()).min().orElseThrow();
        double contactY = surface - skidBottom + CLEARANCE_EPSILON;
        Vec3d descent = new Vec3d(0, Math.min(0, contactY - heli.getY()), 0);
        Vec3d drift = heli.getPos().subtract(anchor);
        List<Shape> corridor = new ArrayList<>();
        for (Shape part : parts) {
            double vertical = switch (part.part) {
                case LANDING_GEAR -> 0;
                case CABIN, FUEL_TANK -> LANDING_BODY_CLEARANCE;
                default -> LANDING_TILT_CLEARANCE;
            };
            Vec3d padding = new Vec3d(LANDING_CLEARANCE + Math.abs(drift.x), vertical,
                    LANDING_CLEARANCE + Math.abs(drift.z));
            // 尾桨盒相对机身还旋转九十度，世界净空余量必须投影到每个分部自己的三个轴。
            Vec3d half = part.half.add(axisPadding(part.axes[0], padding),
                    axisPadding(part.axes[1], padding), axisPadding(part.axes[2], padding));
            add(corridor, part.part, new Box(half.negate(), half), part.center, part.rotation);
        }
        Box area = bounds(corridor).stretch(descent);
        World world = heli.getWorld();
        if (surface < world.getBottomY() || area.maxY > world.getTopY()
                || !world.getWorldBorder().contains(area)) return false;
        // 方块形状可伸入相邻格，连同一格查询邻域一起确认已加载，不能把未知区块当成空地。
        for (int x = MathHelper.floor(area.minX - 1) >> 4; x <= MathHelper.floor(area.maxX + 1) >> 4; x++)
            for (int z = MathHelper.floor(area.minZ - 1) >> 4; z <= MathHelper.floor(area.maxZ + 1) >> 4; z++)
                if (!world.isChunkLoaded(new BlockPos(x << 4, MathHelper.floor(surface), z << 4))) return false;
        List<Box> blocks = obstacles(heli, area);
        boolean hasFluid = world.containsFluid(area);
        for (Shape part : corridor) {
            if (hasFluid && world.containsFluid(part.bounds.stretch(descent))) return false;
            for (Box block : blocks)
                if (part.intersects(block) || part.sweep(block, descent) < 1) return false;
        }
        for (double side : SUPPORT_SIDES) for (double end : SUPPORT_ENDS) {
            Shape patch = orientedBox(Part.LANDING_GEAR,
                    new Box(side - SUPPORT_HALF_WIDTH, -CLEARANCE_EPSILON, end - SUPPORT_HALF_LENGTH,
                            side + SUPPORT_HALF_WIDTH, CLEARANCE_EPSILON, end + SUPPORT_HALF_LENGTH),
                    new Vec3d(anchorX, surface, anchorZ), level.rotation());
            if (!flatSupport(heli, patch.bounds, surface)) return false;
        }
        return true;
    }

    /** 功能：将世界轴余量投影到分部局部轴；net.minecraft.util.math.Vec3d 保存余量，不改变真实碰撞几何。 */
    private static double axisPadding(Vec3d axis, Vec3d padding) {
        return Math.abs(axis.x) * padding.x + Math.abs(axis.y) * padding.y + Math.abs(axis.z) * padding.z;
    }

    /**
     * 功能：小块承重区的实际顶面必须同高且连续，允许区域以外地形不平。
     * 引用 net.minecraft.util.shape.VoxelShapes#combineAndSimplify 精确扣除方块顶面；服务端只读。
     * 排查：台阶边、单点接触和滑橇下孔洞都留下非空差集；水浸支撑不接受。
     */
    private static boolean flatSupport(HelicopterEntity heli, Box support, double surface) {
        World world = heli.getWorld();
        if (world.containsFluid(support)) return false;
        var uncovered = VoxelShapes.cuboid(new Box(support.minX, 0, support.minZ, support.maxX, 1, support.maxZ));
        for (var shape : world.getBlockCollisions(heli, support)) for (Box block : shape.getBoundingBoxes()) {
            if (Math.abs(block.maxY - surface) > CLEARANCE_EPSILON) continue;
            uncovered = VoxelShapes.combineAndSimplify(uncovered,
                    VoxelShapes.cuboid(new Box(block.minX, 0, block.minZ, block.maxX, 1, block.maxZ)),
                    BooleanBiFunction.ONLY_FIRST);
            if (uncovered.isEmpty()) return true;
        }
        return false;
    }

    /**
     * 功能：持续检查自动降落至目标高度的实体通道，包含已经重叠和途中进入的实体。
     * 引用 MC API：net.minecraft.world.World#getOtherEntities 查询候选；net.minecraft.util.math.Vec3d 表示下降扫掠。
     * 端侧/线程：服务端主线程；参数：heli 为本机，targetY 为原降落目标；返回：是否应暂停下降。
     * 副作用：无；排查：不使用 canHit/isCollidable 过滤，玩家、生物、物品和停转飞机均需检测；
     * 仅排除旁观者、移除实体、本机乘员和本机绳索系统；其他飞机按真实分部盒检查，避免大盒空角误报。
     */
    public static boolean landingObstructed(HelicopterEntity heli, double targetY) {
        Vec3d movement = new Vec3d(0, Math.min(0, targetY - heli.getY()), 0);
        List<Shape> parts = new ArrayList<>();
        for (Shape part : shapes(heli, pose(heli), heli.getPos(), true)) {
            Vec3d half = part.half.add(LANDING_ENTITY_MARGIN, LANDING_ENTITY_MARGIN, LANDING_ENTITY_MARGIN);
            add(parts, part.part, new Box(half.negate(), half), part.center, part.rotation);
        }
        Box area = bounds(parts).stretch(movement);
        for (Entity other : heli.getWorld().getOtherEntities(heli, area.expand(QUERY_RADIUS),
                other -> isFlightEntity(heli, other))) {
            if (other instanceof HelicopterEntity aircraft) {
                for (Shape part : parts) for (Shape target : shapes(aircraft))
                    if (part.intersects(target) || part.sweep(target, movement) < 1) return true;
            } else {
                Box target = other.getBoundingBox();
                for (Shape part : parts)
                    if (part.intersects(target) || part.sweep(target, movement) < 1) return true;
            }
        }
        return false;
    }

    /** 原地旋转也检查尾部扫掠；受阻时停在最后安全姿态，记录部位与尾端线速度。 */
    public static Pose limitRotation(HelicopterEntity heli, Pose previous, Pose requested) {
        double angle = Math.max(Math.abs(MathHelper.wrapDegrees(requested.yaw - previous.yaw)),
                Math.max(Math.abs(requested.pitch - previous.pitch), Math.abs(requested.roll - previous.roll)));
        int steps = (int) Math.ceil(angle / ROTATION_STEP);
        if (steps == 0) return requested;
        List<HelicopterEntity> aircraft = candidates(heli.getWorld(), heli,
                new Box(heli.getPos().add(-QUERY_RADIUS, -QUERY_RADIUS, -QUERY_RADIUS),
                        heli.getPos().add(QUERY_RADIUS, QUERY_RADIUS, QUERY_RADIUS)));
        for (int i = 1; i <= steps; i++) {
            Pose sample = previous.interpolate(requested, i / (double) steps); List<Shape> shapes = shapes(heli, sample, heli.getPos());
            List<Box> blocks = obstacles(heli, bounds(shapes));
            for (Shape shape : shapes) for (Box block : blocks) if (shape.intersects(block)) {
                double speed = Math.toRadians(angle) * shape.center.distanceTo(heli.getPos());
                if (shape.part == Part.MAIN_ROTOR && heli.breakMainRotorOnBlock(speed)) continue;
                heli.recordBlockImpact(shape.part, speed);
                return previous.interpolate(requested, (i - 1.0) / steps);
            }
            for (HelicopterEntity other : aircraft) for (Shape shape : shapes) for (Shape target : shapes(other))
                if (shape.intersects(target)) {
                    double speed = Math.toRadians(angle) * shape.center.distanceTo(heli.getPos());
                    heli.collide(shape.part, speed); other.collide(target.part, speed);
                    return previous.interpolate(requested, (i - 1.0) / steps);
                }
        }
        return requested;
    }

    /** 发动旋翼时即使机体静止也检测桨盘接触，损伤限定在接触的主桨或尾桨。 */
    public static void spinningContact(HelicopterEntity heli) {
        if (heli.flight.rpm <= 0 || heli.flight.wreck) return;
        List<Shape> shapes = shapes(heli); List<Box> blocks = obstacles(heli, bounds(shapes));
        for (Shape shape : shapes) if (shape.part == Part.MAIN_ROTOR || shape.part == Part.TAIL_ROTOR)
            for (Box block : blocks) if (shape.intersects(block)) {
                // 桨尖自身转速只用于局部损伤，不能冒充整机高速撞墙而直接引爆飞机。
                if (shape.part == Part.MAIN_ROTOR) heli.recordBlockImpact(shape.part, 0);
                else heli.recordBlockImpact(shape.part, heli.flight.rpm * .8);
                break;
            }
    }

    /** 获取移动扫过其他实体的首个部位；连续平移扫掠替代原来整块轴对齐盒的相交即伤害。 */
    public static Part sweptPart(HelicopterEntity heli, Box target, Vec3d previous, Vec3d movement) {
        Part part = null; double first = Double.POSITIVE_INFINITY;
        for (Shape shape : shapes(heli, pose(heli), previous)) {
            if (shape.intersects(target)) return shape.part;
            double fraction = shape.sweep(target, movement);
            if (fraction < 1 && fraction < first) { first = fraction; part = shape.part; }
        }
        return part;
    }

    /** 两架飞机分别使用真实分部盒，其他实体沿用原版轴对齐实体盒。 */
    public static Contact sweptContact(HelicopterEntity heli, Entity target, Vec3d previous, Vec3d movement) {
        if (!(target instanceof HelicopterEntity aircraft)) {
            Part part = sweptPart(heli, target.getBoundingBox(), previous, movement);
            return part == null ? null : new Contact(part, null);
        }
        Contact contact = null; double first = Double.POSITIVE_INFINITY; List<Shape> targets = shapes(aircraft);
        for (Shape shape : shapes(heli, pose(heli), previous)) for (Shape other : targets) {
            if (shape.intersects(other)) return new Contact(shape.part, other.part);
            double fraction = shape.sweep(other, movement);
            if (fraction < 1 && fraction < first) { first = fraction; contact = new Contact(shape.part, other.part); }
        }
        return contact;
    }

    /** 根据真实盒面返回最近射线命中；在盒内部起射时距离为零，不返回候选大盒的空角。 */
    public static Hit raycast(HelicopterEntity heli, Vec3d start, Vec3d end) {
        Hit closest = null;
        for (Shape shape : shapes(heli)) {
            Vec3d a = shape.local(start), b = shape.local(end); Box local = new Box(shape.half.negate(), shape.half);
            var point = local.contains(a) ? java.util.Optional.of(a) : local.raycast(a, b);
            if (point.isEmpty()) continue;
            Vec3d p = point.get(); Vec3d world = shape.center.add(shape.axes[0].multiply(p.x))
                    .add(shape.axes[1].multiply(p.y)).add(shape.axes[2].multiply(p.z));
            double distance = start.squaredDistanceTo(world);
            if (closest == null || distance < closest.distanceSquared) closest = new Hit(shape.part, world, distance);
        }
        return closest;
    }

    /** 外部非定向伤害按离来源最近的实际盒面定位，不再把攻击者高度当作中弹位置。 */
    public static Part nearestPart(HelicopterEntity heli, Vec3d point) {
        Part result = Part.CABIN; double nearest = Double.POSITIVE_INFINITY;
        for (Shape shape : shapes(heli)) {
            Vec3d local = shape.local(point);
            double dx = Math.max(0, Math.abs(local.x) - shape.half.x), dy = Math.max(0, Math.abs(local.y) - shape.half.y);
            double dz = Math.max(0, Math.abs(local.z) - shape.half.z), distance = dx * dx + dy * dy + dz * dz;
            if (distance < nearest) { nearest = distance; result = shape.part; }
        }
        return result;
    }

    /** 原版选取入口补查长尾候选；同机乘员及其投射物不命中本机。 */
    public static List<HelicopterEntity> candidates(World world, Entity source, Box area) {
        return world.getEntitiesByClass(HelicopterEntity.class, area.expand(QUERY_RADIUS), heli -> {
            if (heli == source || source != null && heli.isConnectedThroughVehicle(source)) return false;
            return !(source instanceof ProjectileEntity projectile && projectile.getOwner() != null
                    && heli.hasPassenger(projectile.getOwner()));
        });
    }

    /** 其他实体的移动也按旋转盒裁剪，原版不得把整机大包围盒的空角变成一面隐形墙。 */
    public static Vec3d clipActor(Entity actor, Vec3d movement) {
        if (actor.noClip || actor.isSpectator() || actor instanceof RopeSeatEntity || actor.getRootVehicle() instanceof RopeSeatEntity)
            return movement;
        List<HelicopterEntity> aircraft = candidates(actor.getWorld(), actor, actor.getBoundingBox().stretch(movement));
        aircraft.removeIf(heli -> heli.ignoresPlacementCollision(actor));
        if (aircraft.isEmpty()) return movement;
        Vec3d moved = Vec3d.ZERO;
        for (int axis : new int[]{1, 0, 2}) {
            double amount = axis == 0 ? movement.x : axis == 1 ? movement.y : movement.z;
            if (amount == 0) continue;
            Vec3d delta = WORLD_AXES[axis].multiply(amount); double fraction = 1;
            Box box = actor.getBoundingBox().offset(moved);
            for (HelicopterEntity heli : aircraft) for (Shape shape : shapes(heli))
                fraction = Math.min(fraction, shape.sweep(box, delta.negate()));
            moved = moved.add(delta.multiply(fraction));
        }
        return moved;
    }
}
