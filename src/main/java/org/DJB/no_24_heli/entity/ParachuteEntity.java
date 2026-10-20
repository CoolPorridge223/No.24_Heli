/** 文件：未开伞趴姿飞行、载人伞降、失压拖行及故障生命周期；端侧：COMMON；实体：no_24_heli:parachute。 */
package org.DJB.no_24_heli.entity;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.flight.ParachuteCanopy;
import org.DJB.no_24_heli.flight.ParachutePhysics;
import org.DJB.no_24_heli.flight.SkydivingPhysics;
import org.DJB.no_24_heli.item.ParachuteItem;
import org.DJB.no_24_heli.network.ParachutePayload;
import org.DJB.no_24_heli.registry.ModAdvancements;
import org.DJB.no_24_heli.registry.ModParachutes;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 职责：服务端负责开伞资格、气动力、故障、耐久和失压布料；客户端仅插值显示。
 * 引用：net.minecraft.entity.Entity#move 提供乘员碰撞；DataTracker 同步姿态及布面快照。
 * 生命周期：FREEFALL → ACTIVE → TRAILING 保留与胸槽连接，或 DETACHED 飘走十五秒后移除。
 * 线程：逻辑服务端主线程，不接受原版载具坐标包；结束后不再改变玩家运动或跌落距离。
 * 排查：碰墙缓降查 finishFlight；拖行消失查伞包 UUID；卡伞恢复查 F 的按下边沿。
 */
public final class ParachuteEntity extends Entity {
    /** 生命周期与故障码保存到 NBT，不能随渲染或重连重新抽签。 */
    public static final int ACTIVE = 0, TRAILING = 1, DETACHED = 2, FREEFALL = 3;
    public static final int NORMAL = 0, JAMMED = 1, TORN = 2, WING_LOST = 3;
    /** 开伞至少十格；严重故障的自由伞面保留十五秒。 */
    public static final double MIN_DEPLOY_HEIGHT = 10;
    /** 只限制开始未开伞趴姿，严格超过五十格；不提高原有十格开伞门槛。 */
    public static final double MIN_FREEFALL_HEIGHT = 50;
    /** 身体与碰撞共用的胸前旋转支点，单位格。 */
    private static final double BODY_PIVOT = 1.25;
    /** 测高和方块候选查询的容差，单位格；五十格整不得因射线终点取舍而进入趴姿。 */
    private static final double COLLISION_EPSILON = 1.0e-5;
    /** 释放站姿时脚底保留一毫米净距，抵消有向扫掠接触容差，防止原版移动校验反复回传传送纠正。 */
    private static final double DISMOUNT_CLEARANCE = .001;
    private static final int DETACHED_LIFETIME = 300, JAM_START = 6, PULL_WINDOW = 12;
    private static final double SAFE_SINK = 7, SAFE_WALL_SPEED = 6;
    private static final TrackedData<Integer> OPENING = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> MODE = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> FAILURE = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> OWNER = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Float> BANK = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CANOPY_PITCH = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<NbtCompound> CLOTH = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
    /** 未开伞的身体俯仰、横滚与收拢比例独立于伞翼，开伞时保留为动作混合起点。 */
    private static final TrackedData<Float> DIVE_PITCH = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DIVE_BANK = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> DIVE_TUCK = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.FLOAT);
    /** 跃出时身体四元数、视觉偏移及世界时间；重追踪不会重新播放跃出到趴姿的过渡。 */
    private static final TrackedData<NbtCompound> ENTRY = DataTracker.registerData(
            ParachuteEntity.class, TrackedDataHandlerRegistry.NBT_COMPOUND);
    /** 每具伞独立的权威气动与失压布料；客户端只读取同步后的节点。 */
    private final ParachutePhysics.State flight = new ParachutePhysics.State();
    private final ParachuteCanopy cloth = new ParachuteCanopy();
    private final SkydivingPhysics.State dive = new SkydivingPhysics.State();
    /** 未开伞阶段累计实际下降距离，用于触地跌落伤害，不能因挂载载具获得免伤。 */
    private float diveDistance;
    /** 所有者和所穿伞包的持久连接，与可回收的运行时实体 ID 分开。 */
    private UUID ownerUuid, packUuid;
    /** 操纵意图范围 -1..1；输入和拉绳时间使用世界 tick，不从客户端接收时钟。 */
    private float forwardInput, turnInput;
    /** 观察角单位度，来自已校验 C2S；仅趴姿飞行使用，展开后保持原有自由观察。 */
    private float lookYaw, lookPitch;
    private long lastInput = -100, lastPull = -100;
    /** 离线宽限、自由伞面寿命和耐久损耗计时相互独立。 */
    private int emptyTicks, detachedTicks, flyingTicks;
    /** 客户端追踪插值；气动和故障只由服务端决定。 */
    private int lerpTicks, previousOpening;
    /** 快照过渡的时间常数为一客户端 tick；连续追踪目标，包间隔波动时也不突然停住。 */
    private static final double CLOTH_SMOOTHING_TICKS = 1;
    /** 客户端最近显示时间与混合比例；新包从该显示位置继续，不能回退到上一权威快照。 */
    private double clothSnapshotTime, clothRenderTime;
    private float clothBlend = 1;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, previousBank, previousCanopyPitch;
    /** 上一客户端 tick 的身体姿态，用于帧内插值，不回写服务端气动。 */
    private float previousDivePitch = 90, previousDiveBank, previousDiveTuck;

    /** 原版实体工厂入口；载人碰撞体随共用身体姿态旋转。 */
    public ParachuteEntity(EntityType<?> type, World world) { super(type, world); }

    /** 默认未展开、无故障；NBT 布面只在失压阶段同步。 */
    @Override protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(OPENING, 0); builder.add(MODE, ACTIVE); builder.add(FAILURE, NORMAL);
        builder.add(OWNER, -1); builder.add(BANK, 0f); builder.add(CANOPY_PITCH, 0f);
        builder.add(CLOTH, new NbtCompound());
        builder.add(DIVE_PITCH, 90f); builder.add(DIVE_BANK, 0f); builder.add(DIVE_TUCK, 0f);
        builder.add(ENTRY, new NbtCompound());
    }

    /** 未开伞趴姿只接管离地超过五十格的合资格玩家；低于该高度保持原版下落。 */
    public static boolean canStartFreefall(PlayerEntity player) {
        return canStartFlight(player) && hasFreefallHeight(player);
    }

    /** 载人阶段共用的穿戴与运动资格；net.minecraft.entity.player.PlayerEntity 提供飞行、流体和乘骑状态。 */
    private static boolean canStartFlight(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && !player.hasVehicle() && !player.isOnGround()
                && !player.isTouchingWater() && !player.isInLava() && !player.isFallFlying()
                && !player.getAbilities().flying && ParachuteItem.isPacked(player.getEquippedStack(EquipmentSlot.CHEST));
    }

    /**
     * 功能：用 net.minecraft.world.World#raycast 检查脚底中心和四角，平台、非完整方块和液面均计入地面。
     * COMMON 主线程只读；任一点在五十格以内（含边界）命中即拒绝，不把世界 Y 当作离地高度。
     */
    private static boolean hasFreefallHeight(PlayerEntity player) {
        Box body = player.getBoundingBox();
        double[] xs = {player.getX(), body.minX + COLLISION_EPSILON, body.maxX - COLLISION_EPSILON};
        double[] zs = {player.getZ(), body.minZ + COLLISION_EPSILON, body.maxZ - COLLISION_EPSILON};
        for (int i = 0; i < 5; i++) {
            int x = i == 0 ? 0 : i <= 2 ? 1 : 2, z = i == 0 ? 0 : i % 2 == 1 ? 1 : 2;
            Vec3d feet = new Vec3d(xs[x], body.minY, zs[z]);
            var hit = player.getWorld().raycast(new RaycastContext(feet,
                    feet.add(0, -MIN_FREEFALL_HEIGHT - COLLISION_EPSILON, 0),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
            if (hit.getType() != HitResult.Type.MISS && feet.y - hit.getPos().y <= MIN_FREEFALL_HEIGHT) return false;
        }
        return true;
    }

    /**
     * 功能：在跃出或正常高处下落时接管未开伞运动，不扣耐久、不消耗折叠状态、不抽取故障。
     * net.minecraft.entity.Entity#startRiding 保持服务端位置权威；可选 source/seat 保存离机姿态以连续混合。
     * 仅逻辑服务端调用；返回是否成功接管，失败不改变玩家；原版落地伤害由 diveDistance 单独累计。
     */
    public static boolean startFreefall(PlayerEntity player, HelicopterEntity source, int seat) {
        if (player.getWorld().isClient) return false;
        if (source != null && !source.isCabinGrounded() && !player.hasVehicle()) player.setOnGround(false);
        if (!canStartFreefall(player)) return false;
        return createFlight(player, source, seat, true);
    }

    /**
     * 功能：生成权威载人实体，趴姿及直接开伞共用挂载与惯性初始化；prone 决定是否保存趴姿过渡。
     * net.minecraft.world.World#spawnEntity 和 net.minecraft.entity.Entity#startRiding 只在逻辑服务端调用。
     * 十至五十格直接开伞不经过趴姿高度检查；失败清理新实体，不消耗伞包。
     */
    private static boolean createFlight(PlayerEntity player, HelicopterEntity source, int seat, boolean prone) {
        if (!canStartFlight(player)) return false;
        ParachuteEntity canopy = new ParachuteEntity(ModParachutes.CANOPY, player.getWorld());
        canopy.dataTracker.set(MODE, FREEFALL); canopy.ownerUuid = player.getUuid();
        canopy.setPosition(player.getPos()); canopy.setYaw(player.getYaw());
        canopy.lookYaw = MathHelper.wrapDegrees(player.getYaw()); canopy.lookPitch = player.getPitch();
        Vec3d velocity = player.getVelocity();
        // 普通玩家移动以坐标包同步，服务端 velocity 可能只含重力；补回真实下落位移，避免接管时刹停。
        if (source == null && player.fallDistance > 0) {
            Vec3d displacement = player.getPos().subtract(player.prevX, player.prevY, player.prevZ);
            if (displacement.lengthSquared() <= 25 && displacement.lengthSquared() > velocity.lengthSquared()) velocity = displacement;
        }
        canopy.dive.vx = velocity.x; canopy.dive.vy = velocity.y; canopy.dive.vz = velocity.z;
        canopy.dive.yaw = player.getYaw(); canopy.diveDistance = player.fallDistance;
        canopy.setVelocity(velocity); canopy.dataTracker.set(OWNER, player.getId());
        NbtCompound entry = new NbtCompound(); entry.putLong("Started", player.getWorld().getTime());
        entry.putBoolean("Cabin", source != null);
        Quaternionf rotation = new Quaternionf().rotationY((float) -Math.toRadians(player.bodyYaw));
        if (source != null) {
            Quaternionf body = HelicopterCollision.pose(source).rotation();
            rotation.set(body).rotateY((float) Math.toRadians(HelicopterCabin.side(seat) == 0 ? 90 : -90));
            Vec3d local = player.getPos().subtract(source.getPos()).rotateY((float) Math.toRadians(source.getYaw()));
            Vector3f visual = new Vector3f((float) local.x, (float) local.y, (float) local.z).rotate(body);
            Vec3d offset = source.getPos().add(visual.x, visual.y, visual.z).subtract(player.getPos());
            entry.putDouble("X", offset.x); entry.putDouble("Y", offset.y); entry.putDouble("Z", offset.z);
        }
        entry.putFloat("Qx", rotation.x); entry.putFloat("Qy", rotation.y);
        entry.putFloat("Qz", rotation.z); entry.putFloat("Qw", rotation.w);
        if (prone) canopy.dataTracker.set(ENTRY, entry);
        if (!player.getWorld().spawnEntity(canopy)) return false;
        if (!player.startRiding(canopy, true)) { canopy.discard(); return false; }
        return true;
    }

    /** 两端共用资格，服务端开伞时再次复核；未叠好、低空或空间不足均不显示 F。 */
    public static boolean canDeploy(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator()
                && (!player.hasVehicle() || player.getVehicle() instanceof ParachuteEntity canopy && canopy.isFreefall())
                && !player.isOnGround()
                && !player.isTouchingWater() && !player.isInLava() && !player.isFallFlying()
                && !player.getAbilities().flying
                && ParachuteItem.isPacked(player.getEquippedStack(EquipmentSlot.CHEST))
                && hasDeploymentClearance(player);
    }

    /**
     * 原版碰撞面/液面五点测高；翼展包围盒按朝向旋转后保守检查墙、顶板和实体。
     * net.minecraft.world.World#isSpaceEmpty 同时检查方块与实体，世界边界另行校验。
     */
    private static boolean hasDeploymentClearance(PlayerEntity player) {
        Box body = player.getBoundingBox();
        double[] xs = {player.getX(), body.minX + .001, body.maxX - .001};
        double[] zs = {player.getZ(), body.minZ + .001, body.maxZ - .001};
        for (int i = 0; i < 5; i++) {
            int x = i == 0 ? 0 : i <= 2 ? 1 : 2, z = i == 0 ? 0 : i % 2 == 1 ? 1 : 2;
            Vec3d feet = new Vec3d(xs[x], body.minY, zs[z]);
            var hit = player.getWorld().raycast(new RaycastContext(feet, feet.add(0, -MIN_DEPLOY_HEIGHT, 0),
                    RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
            if (hit.getType() != HitResult.Type.MISS
                    && feet.y - hit.getPos().y < MIN_DEPLOY_HEIGHT - .000001) return false;
        }
        double yaw = Math.toRadians(player.getYaw()), c = Math.abs(Math.cos(yaw)), s = Math.abs(Math.sin(yaw));
        double dx = 3.9 * c + 1.7 * s, dz = 3.9 * s + 1.7 * c;
        Box wing = new Box(player.getX() - dx, player.getY() + 1.9, player.getZ() - dz,
                player.getX() + dx, player.getY() + ParachuteCanopy.HEIGHT + .4, player.getZ() + dz);
        return player.getWorld().getWorldBorder().contains(wing) && player.getWorld().isSpaceEmpty(player, wing);
    }

    /** 成功生成、挂载后才消耗耐久及折叠状态；故障按消耗前的剩余耐久判断。 */
    public static void deploy(ServerPlayerEntity player) {
        if (!canDeploy(player)) return;
        if (!player.hasVehicle() && !createFlight(player, null, -1, false)) return;
        if (player.getVehicle() instanceof ParachuteEntity canopy) canopy.open(player);
    }

    /** 同一实体从趴姿转为开伞，保留速度及人物姿态；完成资格复核后才扣耐久并抽取故障。 */
    private void open(ServerPlayerEntity player) {
        if (!isFreefall() || !canDeploy(player)) return;
        ItemStack pack = player.getEquippedStack(EquipmentSlot.CHEST);
        Vec3d velocity = getVelocity();
        flight.vx = velocity.x; flight.vy = velocity.y; flight.vz = velocity.z;
        flight.yaw = getYaw(); flight.yawRate = flight.bank = flight.pitch = 0;
        packUuid = UUID.randomUUID(); dataTracker.set(MODE, ACTIVE);
        dataTracker.set(FAILURE, rollFailure(pack, player.getRandom()));
        ParachuteItem.markUsed(pack, packUuid);
        if (failure() == WING_LOST) ParachuteItem.loseWing(pack);
        else pack.damage(ParachuteItem.DEPLOY_DAMAGE, player, EquipmentSlot.CHEST);
        ModAdvancements.grant(player, ModAdvancements.DIFFERENT_ENDING);
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(),
                net.minecraft.sound.SoundCategory.PLAYERS, .9f, .65f);
    }

    /** 严格低于 10% 才抽取 10% 故障；条件权重 60/30/10，健康伞包不消耗随机数。 */
    private static int rollFailure(ItemStack pack, Random random) {
        if ((pack.getMaxDamage() - pack.getDamage()) * 10 >= pack.getMaxDamage() || random.nextFloat() >= .1f)
            return NORMAL;
        int draw = random.nextInt(100);
        return draw < 60 ? JAMMED : draw < 90 ? TORN : WING_LOST;
    }

    /** 真实乘员的 WASD 意图；卡伞时只接受分离的 F 按下边沿，长按重复不计。 */
    public void control(PlayerEntity player, ParachutePayload packet) {
        if (!isAirborne() || !hasPassenger(player) || !packet.valid()) return;
        forwardInput = packet.forward(); turnInput = packet.turn(); lastInput = getWorld().getTime();
        lookYaw = packet.lookYaw(); lookPitch = packet.lookPitch();
        if (isFreefall()) {
            if (packet.deploy() && player instanceof ServerPlayerEntity serverPlayer) open(serverPlayer);
            return;
        }
        if (failure() != JAMMED || !packet.deploy() || lastInput - lastPull < 2) return;
        int progress = lastInput - lastPull > PULL_WINDOW ? JAM_START : dataTracker.get(OPENING);
        lastPull = lastInput; progress = Math.min(ParachutePhysics.OPEN_TICKS, progress + 4);
        dataTracker.set(OPENING, progress);
        if (progress == ParachutePhysics.OPEN_TICKS) dataTracker.set(FAILURE, NORMAL);
    }

    /** 原版不能回写载具坐标，所有气动由服务端积分。 */
    @Override public LivingEntity getControllingPassenger() { return null; }
    /** 失压布面不可再乘坐，载人阶段仅允许一名玩家。 */
    @Override protected boolean canAddPassenger(Entity passenger) {
        return isAirborne() && !hasPassengers() && passenger instanceof PlayerEntity;
    }
    /** 不受实体推挤，但保留原版 move 方块碰撞。 */
    @Override public boolean isPushable() { return false; }
    /** 不把刚离开的飞机或其他乘员当成墙。 */
    @Override public boolean collidesWith(Entity entity) { return false; }
    /**
     * 功能：向 net.minecraft.entity.Entity 的环境查询提供身体旋转后的轴对齐候选范围。
     * 父类构造期间 flight 尚未初始化，先用注册尺寸；真实碰撞通过 clipMovement 的有向盒完成。
     */
    @Override protected Box calculateBoundingBox() {
        return flight == null || !isAirborne() ? super.calculateBoundingBox() : bodyShape().bounds();
    }

    /**
     * 功能：将乘员原始宽高绕与显示一致的支点、缩放及四元数旋转，覆盖趴姿、跃出和开伞起身。
     * 引用 net.minecraft.entity.Entity#getWidth/getHeight 读取乘员尺寸，net.minecraft.util.math.Box 保存局部体积。
     * COMMON 主线程只读；不使用玩家 getBoundingBox 反推尺寸，防止已经旋转的范围被重复扩大。
     */
    private HelicopterCollision.Shape bodyShape() {
        Entity rider = getFirstPassenger();
        double width = rider == null ? getWidth() : rider.getWidth();
        double height = rider == null ? getHeight() : rider.getHeight();
        double pivot = bodyPivot(1), scale = bodyScale(1);
        Box local = new Box(-width * scale / 2, -pivot * scale, -width * scale / 2,
                width * scale / 2, (height - pivot) * scale, width * scale / 2);
        return HelicopterCollision.orientedBox(HelicopterCollision.Part.CABIN, local,
                getPos().add(entryOffset(1)).add(0, pivot, 0), bodyRotation(1));
    }

    /** 乘员和载具共用同一候选盒，net.minecraft.entity.Entity#setPosition 重算玩家碰撞箱时由 Mixin 调用。 */
    public Box riderBounds(Entity rider) {
        return bodyShape().bounds().offset(rider.getPos().subtract(getPos()));
    }

    /**
     * 功能：以身体有向盒连续扫掠原版方块形状，先垂直再水平，交给 Entity#move 更新接地与撞墙标记。
     * net.minecraft.world.World#getBlockCollisions 只筛选候选；COMMON 主线程，无客户端物理积分。
     * 排查：斜身空角被挡时检查是否仍使用候选 AABB；不得通过 setPos 绕过连续碰撞。
     */
    public Vec3d clipMovement(Vec3d movement) {
        HelicopterCollision.Shape body = bodyShape();
        List<Box> blocks = new ArrayList<>();
        for (var shape : getWorld().getBlockCollisions(this, body.bounds().stretch(movement).expand(COLLISION_EPSILON)))
            blocks.addAll(shape.getBoundingBoxes());
        Vec3d moved = Vec3d.ZERO;
        int[] order = Math.abs(movement.x) < Math.abs(movement.z) ? new int[]{1, 0, 2} : new int[]{1, 2, 0};
        for (int axis : order) {
            Vec3d delta = axis == 0 ? new Vec3d(movement.x, 0, 0)
                    : axis == 1 ? new Vec3d(0, movement.y, 0) : new Vec3d(0, 0, movement.z);
            double fraction = 1;
            for (Box block : blocks) fraction = Math.min(fraction, body.sweep(block.offset(moved.negate()), delta));
            moved = moved.add(delta.multiply(fraction));
        }
        // 保留原版世界边界的四个限制平面；有向盒只替换方块裁剪，不能让玩家穿出边界。
        var border = getWorld().getWorldBorder(); Box bounds = body.bounds();
        double x = moved.x, z = moved.z;
        if (x > 0) x = Math.min(x, Math.max(0, border.getBoundEast() - bounds.maxX));
        if (x < 0) x = Math.max(x, Math.min(0, border.getBoundWest() - bounds.minX));
        if (z > 0) z = Math.min(z, Math.max(0, border.getBoundSouth() - bounds.maxZ));
        if (z < 0) z = Math.max(z, Math.min(0, border.getBoundNorth() - bounds.minZ));
        return new Vec3d(x, moved.y, z);
    }

    /**
     * 功能：姿态变化也必须检查真实方块接触，避免先旋入地面后被平移扫掠视为允许退出的旧重叠。
     * net.minecraft.world.World#getBlockCollisions 读取碰撞形状；COMMON 服务端使用上一安全身体范围结束飞行。
     * 排查：落地站姿进入地面时检查此处是否在 move 前执行，以及释放脚位是否取身体实际最低点。
     */
    private void moveBody(Vec3d velocity, HelicopterCollision.Shape previous, Box previousBounds) {
        HelicopterCollision.Shape body = bodyShape();
        boolean overlaps = false, supported = false;
        for (var shape : getWorld().getBlockCollisions(this, body.bounds().union(previousBounds).expand(COLLISION_EPSILON)))
            for (Box block : shape.getBoundingBoxes()) {
                overlaps |= body.intersects(block);
                supported |= previous.sweep(block, new Vec3d(0, -.08, 0)) < 1;
            }
        if (overlaps) {
            setBoundingBox(previousBounds);
            setOnGround(supported && velocity.y <= 0);
            verticalCollision = supported; horizontalCollision = !supported;
        } else {
            setBoundingBox(body.bounds()); move(MovementType.SELF, velocity);
        }
    }
    /** 玩家冲击按真实速度结算，布料本身不承受跌落伤害。 */
    @Override public boolean handleFallDamage(float distance, float multiplier,
            net.minecraft.entity.damage.DamageSource source) { return false; }

    /** 服务端分阶段 tick；只有 ACTIVE 能操作玩家速度/跌落距离。 */
    @Override public void tick() {
        super.tick();
        if (getWorld().isClient) { tickClient(); return; }
        if (isFreefall()) { tickFreefall(); return; }
        if (mode() != ACTIVE) { tickLoose(); return; }
        PlayerEntity player = getFirstPassenger() instanceof PlayerEntity rider ? rider : owner();
        if (player == null) { if (++emptyTicks > 100) discard(); return; }
        // 兼容更新前已在存档中展开的伞，仅认领原来的胸槽物品，不重新抽签或扣开伞耐久。
        if (packUuid == null && ParachuteItem.isParachute(player.getEquippedStack(EquipmentSlot.CHEST))) {
            ownerUuid = player.getUuid(); packUuid = UUID.randomUUID();
            ParachuteItem.markUsed(player.getEquippedStack(EquipmentSlot.CHEST), packUuid);
        }
        dataTracker.set(OWNER, player.getId()); emptyTicks = 0;
        if (!player.isAlive() || !hasPassenger(player) || !wearingPack(player)) {
            finishFlight(player, false, false); return;
        }
        if (getWorld().getTime() - lastInput > 10) { forwardInput = 0; turnInput = 0; }
        HelicopterCollision.Shape previousBody = bodyShape(); Box previousBounds = getBoundingBox();
        int opening = dataTracker.get(OPENING);
        if (failure() == JAMMED) {
            if (getWorld().getTime() - lastPull > PULL_WINDOW) opening = Math.min(JAM_START, opening + 1);
        } else opening = Math.min(ParachutePhysics.OPEN_TICKS, opening + 1);
        dataTracker.set(OPENING, opening);
        double time = getWorld().getTime(), wind = getWorld().hasRain(getBlockPos()) ? .7 : .25;
        double effectiveness = failure() == JAMMED ? 0 : failure() == TORN ? .5 : 1;
        ParachutePhysics.step(flight, forwardInput, turnInput, ParachutePhysics.inflation(opening),
                Math.sin(time * .013) * wind, Math.cos(time * .009) * wind, effectiveness);
        setYaw(MathHelper.wrapDegrees((float) flight.yaw)); dataTracker.set(BANK, (float) flight.bank);
        dataTracker.set(CANOPY_PITCH, (float) flight.pitch);
        Vec3d velocity = new Vec3d(flight.vx, flight.vy, flight.vz);
        setVelocity(velocity); moveBody(velocity, previousBody, previousBounds);
        fallDistance = 0; player.fallDistance = 0; velocityDirty = true;
        if (failure() == WING_LOST && opening == ParachutePhysics.OPEN_TICKS) {
            finishFlight(player, false, true); return;
        }
        if (++flyingTicks % ParachuteItem.WEAR_INTERVAL == 0)
            player.getEquippedStack(EquipmentSlot.CHEST).damage(1, player, EquipmentSlot.CHEST);
        if (isTouchingWater() || isInLava()) { finishFlight(player, true, false); return; }
        if (isOnGround() || horizontalCollision || verticalCollision) {
            boolean ground = isOnGround();
            double impact = ground ? Math.max(0, -velocity.y * 20 - SAFE_SINK)
                    : Math.max(0, velocity.length() * 20 - SAFE_WALL_SPEED);
            finishFlight(player, true, false);
            if (impact > 0) player.damage(ground ? getDamageSources().fall()
                    : getDamageSources().flyIntoWall(), (float) (impact * impact * .16));
        }
    }

    /** 未开伞阶段积分重力、姿态阻力和水平平移；net.minecraft.entity.Entity#move 继续执行真实方块碰撞。 */
    private void tickFreefall() {
        PlayerEntity player = getFirstPassenger() instanceof PlayerEntity rider ? rider : null;
        if (player == null) { if (++emptyTicks > 100) discard(); return; }
        emptyTicks = 0;
        if (!player.isAlive() || !ParachuteItem.isPacked(player.getEquippedStack(EquipmentSlot.CHEST))) {
            finishFreefall(player, false); return;
        }
        if (getWorld().getTime() - lastInput > 10) { forwardInput = 0; turnInput = 0; }
        HelicopterCollision.Shape previousBody = bodyShape(); Box previousBounds = getBoundingBox();
        SkydivingPhysics.step(dive, forwardInput, turnInput, lookYaw, lookPitch);
        setYaw(MathHelper.wrapDegrees((float) dive.yaw));
        dataTracker.set(DIVE_PITCH, (float) dive.pitch); dataTracker.set(DIVE_BANK, (float) dive.bank);
        dataTracker.set(DIVE_TUCK, (float) dive.tuck);
        Vec3d velocity = new Vec3d(dive.vx, dive.vy, dive.vz);
        double previousY = getY(); setVelocity(velocity); moveBody(velocity, previousBody, previousBounds);
        diveDistance += (float) Math.max(0, previousY - getY()); velocityDirty = true;
        if (isTouchingWater() || isInLava() || isOnGround() || horizontalCollision || verticalCollision) {
            boolean ground = isOnGround(), water = isTouchingWater() || isInLava();
            finishFreefall(player, true);
            if (ground && !water) player.handleFallDamage(diveDistance, 1, getDamageSources().fall());
            else if (!water && velocity.horizontalLength() * 20 > SAFE_WALL_SPEED) {
                double impact = velocity.horizontalLength() * 20 - SAFE_WALL_SPEED;
                player.damage(getDamageSources().flyIntoWall(), (float) (impact * impact * .16));
            }
        }
    }

    /**
     * 趴姿触地/碰墙或失去伞包时恢复原版玩家；站姿脚底必须在接触面上方，不能沿用扫掠允许的微小穿入。
     * net.minecraft.server.network.ServerPlayNetworkHandler 会拒绝嵌入方块的移动并重置视角；未开伞仍结算跌落伤害。
     */
    private void finishFreefall(PlayerEntity player, boolean stopped) {
        Vec3d position = stopped ? new Vec3d(getX(), getBoundingBox().minY + DISMOUNT_CLEARANCE, getZ()) : getPos();
        Vec3d velocity = getVelocity();
        player.stopRiding(); player.requestTeleport(position.x, position.y, position.z);
        player.setVelocity(stopped ? Vec3d.ZERO : velocity); player.velocityModified = true;
        player.fallDistance = stopped ? 0 : diveDistance;
        discard();
    }

    /**
     * 先捕获当前布面和安全坐标再脱离；墙和地面同样取消气动，空中故障保留下落惯性。
     * 结束只清一次跌落计时，之后原版重力/跌落伤害正常累计，不添加缓降状态效果。
     */
    private void finishFlight(PlayerEntity player, boolean stopped, boolean wingLost) {
        boolean detached = wingLost || failure() == WING_LOST || !player.isAlive();
        cloth.initialize(this, detached);
        Vec3d position = stopped ? new Vec3d(getX(), getBoundingBox().minY + DISMOUNT_CLEARANCE, getZ()) : getPos();
        Vec3d velocity = getVelocity();
        if (hasPassenger(player)) {
            player.stopRiding(); player.requestTeleport(position.x, position.y, position.z);
            player.setVelocity(stopped ? Vec3d.ZERO : velocity); player.velocityModified = true;
            player.fallDistance = 0;
        }
        dataTracker.set(MODE, detached ? DETACHED : TRAILING);
        dataTracker.set(CLOTH, cloth.snapshot(false)); setVelocity(Vec3d.ZERO);
        if (!detached && !wearingPack(player)) discard();
    }

    /** 拖行只更新布料节点；移出原胸槽即删除，脱落布料则按实际风和重力漂浮十五秒。 */
    private void tickLoose() {
        boolean detached = mode() == DETACHED;
        PlayerEntity player = owner();
        if (!detached && player == null) { if (++emptyTicks > 100) discard(); return; }
        if (!detached && (!player.isAlive() || !wearingPack(player))) { discard(); return; }
        if (detached && ++detachedTicks > DETACHED_LIFETIME) { discard(); return; }
        if (player != null) dataTracker.set(OWNER, player.getId());
        Vec3d harness = detached ? null : player.getPos().add(0, 1.25, 0);
        cloth.tick(this, harness, detached); setPosition(cloth.center());
        // 权威布面和柔绳共用每两 tick 的快照，客户端更密的显示节点不参与网络发送。
        if (age % 2 == 0) dataTracker.set(CLOTH, cloth.snapshot(false));
    }

    /** 原版世界玩家表只在所在维度解析；离线或跨维度后宽限清理，不保留不可见孤儿。 */
    private PlayerEntity owner() { return ownerUuid == null ? null : getWorld().getPlayerByUuid(ownerUuid); }
    /** 认原来的物品 UUID，换上另一只已叠伞包不能继续牵引这块布料。 */
    private boolean wearingPack(PlayerEntity player) {
        ItemStack stack = player.getEquippedStack(EquipmentSlot.CHEST);
        return packUuid != null && ParachuteItem.isParachute(stack) && packUuid.equals(ParachuteItem.packId(stack));
    }
    /** 乘员脚底对齐实体；失压阶段没有乘员，不再保护跌落计时。 */
    @Override protected void updatePassengerPosition(Entity passenger, PositionUpdater updater) {
        updater.accept(passenger, getX(), getY(), getZ()); passenger.fallDistance = 0;
    }
    /** 客户端接收布料快照，无物理反写。 */
    @Override public void onTrackedDataSet(TrackedData<?> data) {
        super.onTrackedDataSet(data);
        if (CLOTH.equals(data) && getWorld().isClient) {
            cloth.read(dataTracker.get(CLOTH), clothBlend);
            clothSnapshotTime = Math.max(age, clothRenderTime); clothBlend = 0;
        }
    }
    /** 原版追踪位置插值，布面世界坐标单独插值。 */
    private void tickClient() {
        previousOpening = dataTracker.get(OPENING); previousBank = dataTracker.get(BANK);
        previousCanopyPitch = dataTracker.get(CANOPY_PITCH);
        previousDivePitch = dataTracker.get(DIVE_PITCH); previousDiveBank = dataTracker.get(DIVE_BANK);
        previousDiveTuck = dataTracker.get(DIVE_TUCK);
        if (lerpTicks > 0) {
            setPosition(getX() + (lerpX - getX()) / lerpTicks, getY() + (lerpY - getY()) / lerpTicks,
                    getZ() + (lerpZ - getZ()) / lerpTicks);
            setYaw(getYaw() + MathHelper.wrapDegrees(lerpYaw - getYaw()) / lerpTicks); lerpTicks--;
        }
        setBoundingBox(calculateBoundingBox());
    }
    /** 同步模式，显示端不能自行切换生命周期。 */
    public int mode() { return dataTracker.get(MODE); }
    /** 未开伞阶段不绘制翼面、不消耗伞包；服务端仍拥有玩家运动。 */
    public boolean isFreefall() { return mode() == FREEFALL; }
    /** 只有未开伞及载人翼面阶段控制乘员，失压布料不接管玩家。 */
    public boolean isAirborne() { return isFreefall() || mode() == ACTIVE; }
    /** 旧存档已经展开的伞没有离机姿态，应直接使用原有握绳姿势。 */
    public boolean hasDiveEntry() { return !dataTracker.get(ENTRY).isEmpty(); }
    /** 世界 tick 驱动离机到趴姿的平滑比例；新进入视野的玩家读取已完成的同一时间线。 */
    public float entryBlend(float delta) {
        if (!hasDiveEntry()) return 1;
        float t = Math.clamp((getWorld().getTime() - dataTracker.get(ENTRY).getLong("Started") + delta)
                / SkydivingPhysics.ENTRY_TICKS, 0, 1);
        return t * t * (3 - 2 * t);
    }
    /** 跳机前的模型缩放与腿部动作只在直升机跃出时使用，普通坠落从原版站姿过渡。 */
    public boolean enteredFromCabin() { return dataTracker.get(ENTRY).getBoolean("Cabin"); }
    /** 跃出末帧的世界身体四元数；net.minecraft.entity.Entity 的观察方向不被回写。 */
    public Quaternionf entryRotation() {
        NbtCompound entry = dataTracker.get(ENTRY);
        return entry.isEmpty() ? new Quaternionf().rotationY((float) -Math.toRadians(getYaw()))
                : new Quaternionf(entry.getFloat("Qx"), entry.getFloat("Qy"), entry.getFloat("Qz"), entry.getFloat("Qw"));
    }
    /** 机身倾斜造成的乘员视觉/权威位置差在入趴姿时逐渐消失，相机与第三人称读取同一偏移。 */
    public Vec3d entryOffset(float delta) {
        NbtCompound entry = dataTracker.get(ENTRY);
        return new Vec3d(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z")).multiply(1 - entryBlend(delta));
    }
    /** 趴姿与握绳动作随 24 tick 开伞过程平滑混合，未开伞时保持趴姿。 */
    public float openingBlend(float delta) {
        if (isFreefall()) return 0;
        if (!hasDiveEntry()) return 1;
        float t = (float) Math.clamp(openingTicks(delta) / ParachutePhysics.OPEN_TICKS, 0, 1);
        return t * t * (3 - 2 * t);
    }
    /** 未开伞身体俯仰插值，单位度。 */
    public float divePitch(float delta) { return MathHelper.lerp(delta, previousDivePitch, dataTracker.get(DIVE_PITCH)); }
    /** 平移造成的身体侧倾插值，D 为正，单位度。 */
    public float diveBank(float delta) { return MathHelper.lerp(delta, previousDiveBank, dataTracker.get(DIVE_BANK)); }
    /** 纯前进时收拢，静止及其他平移方向展开，返回连续的 0..1 比例。 */
    public float diveTuck(float delta) { return MathHelper.lerp(delta, previousDiveTuck, dataTracker.get(DIVE_TUCK)); }
    /**
     * 功能：渲染和碰撞共用跃出→趴姿→悬挂的身体旋转；Quaternionf 球面插值避免航向跨界反转。
     * COMMON 只读；服务端 delta=1 读取权威姿态，客户端按帧插值，不回写玩家鼠标方向。
     */
    public Quaternionf bodyRotation(float delta) {
        Quaternionf wing = new Quaternionf().rotationY((float) -Math.toRadians(canopyYaw(delta)))
                .rotateZ((float) Math.toRadians(bank(delta))).rotateX((float) Math.toRadians(canopyPitch(delta)));
        if (!hasDiveEntry() && !isFreefall()) return wing;
        Quaternionf prone = new Quaternionf().rotationY((float) -Math.toRadians(canopyYaw(delta)))
                .rotateZ((float) Math.toRadians(diveBank(delta))).rotateX((float) Math.toRadians(divePitch(delta)));
        return entryRotation().slerp(prone, entryBlend(delta)).slerp(wing, openingBlend(delta));
    }
    /** 身体旋转支点由离舱髋部平滑转为胸前吊点，单位格，两端与碰撞共用。 */
    public double bodyPivot(float delta) {
        return enteredFromCabin() ? MathHelper.lerp(entryBlend(delta), .70, BODY_PIVOT) : BODY_PIVOT;
    }
    /** 舱内额外缩放在离机后恢复；与人物矩阵共用，避免碰撞体提前变大。 */
    public float bodyScale(float delta) {
        return enteredFromCabin() ? MathHelper.lerp(entryBlend(delta), .88f, 1) : 1;
    }
    /** 同步故障类型；卡伞完成后变为 NORMAL。 */
    public int failure() { return dataTracker.get(FAILURE); }
    /** 卡伞时客户端允许 F 连点，实际处理仍校验真实乘员。 */
    public boolean needsPull() { return mode() == ACTIVE && failure() == JAMMED; }
    /** 按归一化翼展/弦长采样世界曲面，减去实体插值原点，避免显示细分密度耦合到实体。 */
    public Vec3d loosePoint(double x, double z, float delta) {
        return cloth.point(x * ParachuteCanopy.SPAN, z * ParachuteCanopy.CHORD, clothBlend(delta))
                .subtract(getLerpedPos(delta));
    }
    /** 柔绳中间节点的同帧插值；第一节点取当前背包穿绳点，消除网络延迟造成的绳端脱开。 */
    public Vec3d linePoint(int line, int node, float delta) {
        if (node == 0) return org.DJB.no_24_heli.flight.ParachuteSuspension.anchor(harness(delta), line, harnessYaw(delta));
        return cloth.linePoint(line, node, clothBlend(delta)).subtract(getLerpedPos(delta));
    }
    /**
     * 布面和柔绳共享连续衰减的显示时间；net.minecraft.entity.Entity#age 加帧内 delta 与帧率无关。
     * 仅客户端记录显示进度，服务端始终读取权威终点；不外推，避免越过碰撞面。
     */
    private float clothBlend(float delta) {
        if (!getWorld().isClient) return 1;
        clothRenderTime = age + (double) delta;
        clothBlend = (float) (1 - Math.exp(-Math.max(0, clothRenderTime - clothSnapshotTime) / CLOTH_SMOOTHING_TICKS));
        return clothBlend;
    }
    /** 存档升级产生的短暂缺省绳网不能当成世界原点坐标绘制。 */
    public boolean hasLines() { return cloth.hasLines(); }
    /** 拖行绳端跟随玩家胸口，严重脱落阶段不再绘制与人物相连的吊绳。 */
    public Vec3d harness(float delta) {
        Entity player = getWorld().getEntityById(dataTracker.get(OWNER));
        return player == null ? Vec3d.ZERO : player.getLerpedPos(delta).add(0, 1.25, 0).subtract(getLerpedPos(delta));
    }
    /** net.minecraft.entity.LivingEntity 的身体航向让落地绳根跟随背包，不能只跟随玩家观察方向。 */
    public float harnessYaw(float delta) {
        Entity player = getWorld().getEntityById(dataTracker.get(OWNER));
        return player instanceof LivingEntity owner ? MathHelper.lerpAngleDegrees(delta, owner.prevBodyYaw, owner.bodyYaw) : getYaw();
    }
    /** 开伞动画不会在卡伞状态自行越过服务端进度。 */
    public double openingTicks(float delta) {
        return needsPull() ? dataTracker.get(OPENING) : Math.min(ParachutePhysics.OPEN_TICKS,
                Math.max(previousOpening, dataTracker.get(OPENING) - 1) + delta);
    }
    /** 布面和气动使用同一充气曲线。 */
    public double inflation(float delta) { return ParachutePhysics.inflation(openingTicks(delta)); }
    /** 人物与伞共用横滚角度。 */
    public float bank(float delta) { return MathHelper.lerp(delta, previousBank, dataTracker.get(BANK)); }
    /** W/S 俯仰角，与人物共享。 */
    public float canopyPitch(float delta) {
        return MathHelper.lerp(delta, previousCanopyPitch, dataTracker.get(CANOPY_PITCH));
    }
    /** 跨 ±180 度取短角航向，保持原先稳定的左/右转向。 */
    public float canopyYaw(float delta) { return MathHelper.lerpAngleDegrees(delta, prevYaw, getYaw()); }
    /** 原版实体追踪包只设置客户端插值目标。 */
    @Override public void updateTrackedPositionAndAngles(double x, double y, double z,
            float yaw, float pitch, int steps) {
        lerpX = x; lerpY = y; lerpZ = z; lerpYaw = yaw; lerpTicks = Math.max(1, steps);
    }
    /** 保存生命周期、物品连接、故障及布料速度，读档不会重新变成已叠伞。 */
    @Override protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("Opening", dataTracker.get(OPENING)); nbt.putInt("Mode", mode());
        nbt.putInt("Failure", failure()); nbt.putInt("DetachedTicks", detachedTicks);
        nbt.putInt("FlyingTicks", flyingTicks);
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
        if (packUuid != null) nbt.putUuid("Pack", packUuid);
        nbt.putDouble("YawRate", flight.yawRate); nbt.putDouble("Bank", flight.bank);
        nbt.putDouble("CanopyPitch", flight.pitch);
        nbt.put("DiveEntry", dataTracker.get(ENTRY).copy());
        nbt.putDouble("DivePitch", dataTracker.get(DIVE_PITCH)); nbt.putDouble("DiveBank", dataTracker.get(DIVE_BANK));
        nbt.putDouble("DiveTuck", dataTracker.get(DIVE_TUCK)); nbt.putFloat("DiveDistance", diveDistance);
        if (!isAirborne()) nbt.put("Cloth", cloth.snapshot(true));
    }
    /** NBT 姿态限定有限范围；原版 Entity 恢复 Motion、Rotation 和乘员关系。 */
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        double rate = nbt.getDouble("YawRate"), bank = nbt.getDouble("Bank"), pitch = nbt.getDouble("CanopyPitch");
        if (!Double.isFinite(rate) || !Double.isFinite(bank) || !Double.isFinite(pitch)) {
            No_24_heli.LOGGER.warn("滑翔伞姿态存档无效，恢复中性：{}", getUuid()); rate = bank = pitch = 0;
        }
        ownerUuid = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        packUuid = nbt.containsUuid("Pack") ? nbt.getUuid("Pack") : null;
        dataTracker.set(MODE, Math.clamp(nbt.getInt("Mode"), ACTIVE, FREEFALL));
        dataTracker.set(FAILURE, Math.clamp(nbt.getInt("Failure"), NORMAL, WING_LOST));
        detachedTicks = Math.clamp(nbt.getInt("DetachedTicks"), 0, DETACHED_LIFETIME);
        flyingTicks = Math.max(0, nbt.getInt("FlyingTicks"));
        flight.yaw = getYaw(); flight.yawRate = Math.clamp(rate, -38, 38); flight.bank = Math.clamp(bank, -28, 28);
        Vec3d velocity = getVelocity(); flight.vx = velocity.x; flight.vy = velocity.y; flight.vz = velocity.z;
        dataTracker.set(OPENING, Math.clamp(nbt.getInt("Opening"), 0, ParachutePhysics.OPEN_TICKS));
        dataTracker.set(BANK, (float) flight.bank);
        flight.pitch = Math.clamp(pitch, -ParachutePhysics.MAX_PITCH, ParachutePhysics.MAX_PITCH);
        dataTracker.set(CANOPY_PITCH, (float) flight.pitch);
        readDive(nbt);
        if (!isAirborne()) { cloth.read(nbt.getCompound("Cloth")); dataTracker.set(CLOTH, cloth.snapshot(false)); }
    }

    /** NBT 系统边界验证未开伞姿态及离机四元数，旧存档缺省为已展开的原有姿势，不重置伞包状态。 */
    private void readDive(NbtCompound nbt) {
        double pitch = nbt.contains("DivePitch") ? nbt.getDouble("DivePitch") : 90;
        double bank = nbt.getDouble("DiveBank"), tuck = nbt.getDouble("DiveTuck");
        float distance = nbt.getFloat("DiveDistance");
        if (!Double.isFinite(pitch) || !Double.isFinite(bank) || !Double.isFinite(tuck) || !Float.isFinite(distance)) {
            No_24_heli.LOGGER.warn("未开伞姿态存档无效，恢复中性：{}", getUuid());
            pitch = 90; bank = tuck = distance = 0;
        }
        dive.pitch = Math.clamp(pitch, 65, 175); dive.bank = Math.clamp(bank, -25, 25); dive.tuck = Math.clamp(tuck, 0, 1);
        diveDistance = Math.max(0, distance); dive.yaw = getYaw(); lookYaw = getYaw();
        Vec3d velocity = getVelocity(); dive.vx = velocity.x; dive.vy = velocity.y; dive.vz = velocity.z;
        dataTracker.set(DIVE_PITCH, (float) dive.pitch); dataTracker.set(DIVE_BANK, (float) dive.bank);
        dataTracker.set(DIVE_TUCK, (float) dive.tuck);
        NbtCompound entry = nbt.getCompound("DiveEntry").copy();
        if (!entry.isEmpty()) {
            Quaternionf rotation = new Quaternionf(entry.getFloat("Qx"), entry.getFloat("Qy"), entry.getFloat("Qz"), entry.getFloat("Qw"));
            Vec3d offset = new Vec3d(entry.getDouble("X"), entry.getDouble("Y"), entry.getDouble("Z"));
            if (!rotation.isFinite() || rotation.lengthSquared() < .001 || !Double.isFinite(offset.lengthSquared())
                    || offset.lengthSquared() > 400) {
                No_24_heli.LOGGER.warn("未开伞离机姿态存档无效，丢弃过渡：{}", getUuid()); entry = new NbtCompound();
            } else {
                rotation.normalize(); entry.putFloat("Qx", rotation.x); entry.putFloat("Qy", rotation.y);
                entry.putFloat("Qz", rotation.z); entry.putFloat("Qw", rotation.w);
            }
        }
        dataTracker.set(ENTRY, entry);
    }
}
