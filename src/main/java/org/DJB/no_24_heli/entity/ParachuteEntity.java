/** 文件：载人伞降、失压拖行及故障生命周期；端侧：COMMON；实体：no_24_heli:parachute。 */
package org.DJB.no_24_heli.entity;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.flight.ParachuteCanopy;
import org.DJB.no_24_heli.flight.ParachutePhysics;
import org.DJB.no_24_heli.item.ParachuteItem;
import org.DJB.no_24_heli.network.ParachutePayload;
import org.DJB.no_24_heli.registry.ModAdvancements;
import org.DJB.no_24_heli.registry.ModParachutes;

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

/**
 * 职责：服务端负责开伞资格、气动力、故障、耐久和失压布料；客户端仅插值显示。
 * 引用：net.minecraft.entity.Entity#move 提供乘员碰撞；DataTracker 同步姿态及布面快照。
 * 生命周期：ACTIVE → TRAILING 保留与胸槽连接，或 DETACHED 飘走十五秒后移除。
 * 线程：逻辑服务端主线程，不接受原版载具坐标包；结束后不再改变玩家运动或跌落距离。
 * 排查：碰墙缓降查 finishFlight；拖行消失查伞包 UUID；卡伞恢复查 F 的按下边沿。
 */
public final class ParachuteEntity extends Entity {
    /** 生命周期与故障码保存到 NBT，不能随渲染或重连重新抽签。 */
    public static final int ACTIVE = 0, TRAILING = 1, DETACHED = 2;
    public static final int NORMAL = 0, JAMMED = 1, TORN = 2, WING_LOST = 3;
    /** 开伞至少十格；严重故障的自由伞面保留十五秒。 */
    public static final double MIN_DEPLOY_HEIGHT = 10;
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
    /** 每具伞独立的权威气动与失压布料；客户端只读取同步后的节点。 */
    private final ParachutePhysics.State flight = new ParachutePhysics.State();
    private final ParachuteCanopy cloth = new ParachuteCanopy();
    /** 所有者和所穿伞包的持久连接，与可回收的运行时实体 ID 分开。 */
    private UUID ownerUuid, packUuid;
    /** 操纵意图范围 -1..1；输入和拉绳时间使用世界 tick，不从客户端接收时钟。 */
    private float forwardInput, turnInput;
    private long lastInput = -100, lastPull = -100;
    /** 离线宽限、自由伞面寿命和耐久损耗计时相互独立。 */
    private int emptyTicks, detachedTicks, flyingTicks;
    /** 客户端追踪插值；气动和故障只由服务端决定。 */
    private int lerpTicks, previousOpening;
    /** 最近布面快照的客户端年龄，用于跨两 tick 插值，避免每帧计时归零造成回跳。 */
    private int clothSnapshotAge;
    private double lerpX, lerpY, lerpZ;
    private float lerpYaw, previousBank, previousCanopyPitch;

    /** 原版实体工厂入口，碰撞箱保持站立玩家大小。 */
    public ParachuteEntity(EntityType<?> type, World world) { super(type, world); }

    /** 默认未展开、无故障；NBT 布面只在失压阶段同步。 */
    @Override protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(OPENING, 0); builder.add(MODE, ACTIVE); builder.add(FAILURE, NORMAL);
        builder.add(OWNER, -1); builder.add(BANK, 0f); builder.add(CANOPY_PITCH, 0f);
        builder.add(CLOTH, new NbtCompound());
    }

    /** 两端共用资格，服务端开伞时再次复核；未叠好、低空或空间不足均不显示 F。 */
    public static boolean canDeploy(PlayerEntity player) {
        return player.isAlive() && !player.isSpectator() && !player.hasVehicle() && !player.isOnGround()
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
        ItemStack pack = player.getEquippedStack(EquipmentSlot.CHEST);
        ParachuteEntity canopy = new ParachuteEntity(ModParachutes.CANOPY, player.getWorld());
        canopy.setPosition(player.getPos()); canopy.setYaw(player.getYaw());
        Vec3d velocity = player.getVelocity();
        // 原版自由落体以坐标包更新，最近一 tick 位移补足服务端 velocity 未包含的惯性。
        Vec3d displacement = player.getPos().subtract(player.prevX, player.prevY, player.prevZ);
        if (displacement.lengthSquared() <= 25 && displacement.lengthSquared() > velocity.lengthSquared())
            velocity = displacement;
        canopy.flight.vx = velocity.x; canopy.flight.vy = velocity.y; canopy.flight.vz = velocity.z;
        canopy.flight.yaw = player.getYaw(); canopy.setVelocity(velocity);
        canopy.ownerUuid = player.getUuid(); canopy.packUuid = UUID.randomUUID();
        canopy.dataTracker.set(OWNER, player.getId());
        if (!player.getWorld().spawnEntity(canopy)) return;
        if (!player.startRiding(canopy, true)) { canopy.discard(); return; }
        canopy.dataTracker.set(FAILURE, rollFailure(pack, player.getRandom()));
        ParachuteItem.markUsed(pack, canopy.packUuid);
        if (canopy.failure() == WING_LOST) ParachuteItem.loseWing(pack);
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
        if (mode() != ACTIVE || !hasPassenger(player) || !packet.valid()) return;
        forwardInput = packet.forward(); turnInput = packet.turn(); lastInput = getWorld().getTime();
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
        return mode() == ACTIVE && !hasPassengers() && passenger instanceof PlayerEntity;
    }
    /** 不受实体推挤，但保留原版 move 方块碰撞。 */
    @Override public boolean isPushable() { return false; }
    /** 不把刚离开的飞机或其他乘员当成墙。 */
    @Override public boolean collidesWith(Entity entity) { return false; }
    /** 玩家冲击按真实速度结算，布料本身不承受跌落伤害。 */
    @Override public boolean handleFallDamage(float distance, float multiplier,
            net.minecraft.entity.damage.DamageSource source) { return false; }

    /** 服务端分阶段 tick；只有 ACTIVE 能操作玩家速度/跌落距离。 */
    @Override public void tick() {
        super.tick();
        if (getWorld().isClient) { tickClient(); return; }
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
        setVelocity(velocity); move(MovementType.SELF, velocity);
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

    /**
     * 先捕获当前布面和安全坐标再脱离；墙和地面同样取消气动，空中故障保留下落惯性。
     * 结束只清一次跌落计时，之后原版重力/跌落伤害正常累计，不添加缓降状态效果。
     */
    private void finishFlight(PlayerEntity player, boolean stopped, boolean wingLost) {
        boolean detached = wingLost || failure() == WING_LOST || !player.isAlive();
        cloth.initialize(this, detached); Vec3d position = getPos(), velocity = getVelocity();
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
        // 粗布面和柔绳共用每两 tick 的快照，225 个细分显示节点不参与网络发送。
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
            cloth.read(dataTracker.get(CLOTH)); clothSnapshotAge = age;
        }
    }
    /** 原版追踪位置插值，布面世界坐标单独插值。 */
    private void tickClient() {
        previousOpening = dataTracker.get(OPENING); previousBank = dataTracker.get(BANK);
        previousCanopyPitch = dataTracker.get(CANOPY_PITCH);
        if (lerpTicks > 0) {
            setPosition(getX() + (lerpX - getX()) / lerpTicks, getY() + (lerpY - getY()) / lerpTicks,
                    getZ() + (lerpZ - getZ()) / lerpTicks);
            setYaw(getYaw() + MathHelper.wrapDegrees(lerpYaw - getYaw()) / lerpTicks); lerpTicks--;
        }
    }
    /** 同步模式，显示端不能自行切换生命周期。 */
    public int mode() { return dataTracker.get(MODE); }
    /** 同步故障类型；卡伞完成后变为 NORMAL。 */
    public int failure() { return dataTracker.get(FAILURE); }
    /** 卡伞时客户端允许 F 连点，实际处理仍校验真实乘员。 */
    public boolean needsPull() { return mode() == ACTIVE && failure() == JAMMED; }
    /** 从快照采样世界节点后减去实体插值原点，供不旋转的失压网格使用。 */
    public Vec3d loosePoint(double x, double z, float delta) {
        float blend = getWorld().isClient ? MathHelper.clamp((age - clothSnapshotAge + delta) / 2, 0, 1) : 1;
        return cloth.point(x * ParachuteCanopy.SPAN / 24, z * ParachuteCanopy.CHORD / 8, blend)
                .subtract(getLerpedPos(delta));
    }
    /** 柔绳中间节点的同帧插值；第一节点用当前人物胸口，消除网络延迟造成的绳端脱开。 */
    public Vec3d linePoint(int line, int node, float delta) {
        if (node == 0) return org.DJB.no_24_heli.flight.ParachuteSuspension.anchor(harness(delta), line);
        float blend = getWorld().isClient ? MathHelper.clamp((age - clothSnapshotAge + delta) / 2, 0, 1) : 1;
        return cloth.linePoint(line, node, blend).subtract(getLerpedPos(delta));
    }
    /** 存档升级产生的短暂缺省绳网不能当成世界原点坐标绘制。 */
    public boolean hasLines() { return cloth.hasLines(); }
    /** 拖行绳端跟随玩家胸口，严重脱落阶段不再绘制与人物相连的吊绳。 */
    public Vec3d harness(float delta) {
        Entity player = getWorld().getEntityById(dataTracker.get(OWNER));
        return player == null ? Vec3d.ZERO : player.getLerpedPos(delta).add(0, 1.25, 0).subtract(getLerpedPos(delta));
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
        if (mode() != ACTIVE) nbt.put("Cloth", cloth.snapshot(true));
    }
    /** NBT 姿态限定有限范围；原版 Entity 恢复 Motion、Rotation 和乘员关系。 */
    @Override protected void readCustomDataFromNbt(NbtCompound nbt) {
        double rate = nbt.getDouble("YawRate"), bank = nbt.getDouble("Bank"), pitch = nbt.getDouble("CanopyPitch");
        if (!Double.isFinite(rate) || !Double.isFinite(bank) || !Double.isFinite(pitch)) {
            No_24_heli.LOGGER.warn("滑翔伞姿态存档无效，恢复中性：{}", getUuid()); rate = bank = pitch = 0;
        }
        ownerUuid = nbt.containsUuid("Owner") ? nbt.getUuid("Owner") : null;
        packUuid = nbt.containsUuid("Pack") ? nbt.getUuid("Pack") : null;
        dataTracker.set(MODE, Math.clamp(nbt.getInt("Mode"), ACTIVE, DETACHED));
        dataTracker.set(FAILURE, Math.clamp(nbt.getInt("Failure"), NORMAL, WING_LOST));
        detachedTicks = Math.clamp(nbt.getInt("DetachedTicks"), 0, DETACHED_LIFETIME);
        flyingTicks = Math.max(0, nbt.getInt("FlyingTicks"));
        flight.yaw = getYaw(); flight.yawRate = Math.clamp(rate, -38, 38); flight.bank = Math.clamp(bank, -28, 28);
        Vec3d velocity = getVelocity(); flight.vx = velocity.x; flight.vy = velocity.y; flight.vz = velocity.z;
        dataTracker.set(OPENING, Math.clamp(nbt.getInt("Opening"), 0, ParachutePhysics.OPEN_TICKS));
        dataTracker.set(BANK, (float) flight.bank);
        flight.pitch = Math.clamp(pitch, -ParachutePhysics.MAX_PITCH, ParachutePhysics.MAX_PITCH);
        dataTracker.set(CANOPY_PITCH, (float) flight.pitch);
        if (mode() != ACTIVE) { cloth.read(nbt.getCompound("Cloth")); dataTracker.set(CLOTH, cloth.snapshot(false)); }
    }
}
