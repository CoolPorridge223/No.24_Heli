package org.DJB.no_24_heli;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 职责：救援绳的分段惯性、弯曲及多名独立攀爬乘员；注册名 no_24_heli:rope_seat。
 * net.minecraft.entity.Entity 提供乘骑，net.minecraft.entity.data.DataTracker 同步绳形和挂点。
 * 直升机部署时创建；仅服务端 tick 积分，客户端插值。自动滑动查输入回中，错位查各 UUID 挂点。
 */
public class RopeSeatEntity extends Entity {
    /** 最长二十四格采用十六段；两端固定节点数，保证同步和插值一致。 */
    public static final int SEGMENTS=16;
    /** 挂点到脚底偏移、头顶净空和安全攀爬上限下方的登机范围，单位：格。 */
    private static final double FEET_OFFSET=.15,HEAD_CLEARANCE=.1,BOARD_REACH=.5;
    /** 登机许可保留六 tick，容忍提示同步和按键回程；主动下降或离开附近区域立即失效。 */
    private static final int BOARD_GRACE_TICKS=6;
    private static final double BOARD_GRACE_DISTANCE=.25;
    /** 方块阻挡的位移误差阈值（格），撞击伤害为速度（格/tick）平方乘二十点生命值。 */
    private static final double COLLISION_EPSILON=.001,IMPACT_DAMAGE_SCALE=20;
    /** 手动上升/下降速度，单位：格/tick。 */
    private static final double CLIMB_SPEED=.14,DESCEND_SPEED=.23;
    /** Verlet 阻尼和重力（格/tick²）；迭代次数限制绳段拉伸。 */
    private static final double DAMPING=.985,GRAVITY=.04;
    /** 每名玩家增加的摆动衰减；阻尼趋近 0.90 而非零，始终保留节点惯性。 */
    private static final double LOADED_DAMPING=.90,LOAD_RESPONSE=.55;
    private static final int CONSTRAINT_PASSES=12;
    /** 权威锚点与形状快照，不接受原版载具移动包回写。 */
    private static final TrackedData<Integer> ANCHOR=DataTracker.registerData(RopeSeatEntity.class,TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<NbtCompound> SHAPE=DataTracker.registerData(RopeSeatEntity.class,TrackedDataHandlerRegistry.NBT_COMPOUND);
    /** 持久锚点 UUID，避免重载后实体 ID 改变；长度不随攀爬改变。 */
    private UUID anchorUuid;
    private double length=2.5;
    /** 节点、上次积分位置和客户端帧间位置，单位为世界坐标。 */
    private final Vec3d[] nodes=new Vec3d[SEGMENTS+1],previous=new Vec3d[SEGMENTS+1],frame=new Vec3d[SEGMENTS+1];
    /** 每位乘员独立挂点与输入；不能共用第一名玩家的攀爬方向。 */
    private final Map<UUID,Rider> riders=new HashMap<>();
    /** 跨区块锚点等待与客户端标准位置插值。 */
    private int missingTicks,lerpTicks;
    private double lerpX,lerpY,lerpZ;

    /** 距离单位为格；新挂绳无输入，超时十 tick 回中，确认窗口每人独立。 */
    private static final class Rider {
        private double distance;
        private float input;
        private long inputTick=-100,exitUntil=-1;
        /** 许可由服务器写入 SHAPE；客户端禁止用平滑后的节点重新推断是否可登机。 */
        private boolean boardReady;
        private long boardUntil=-1;
        /** 服务端上次安全脚底位置和跟绳速度，用于整个人物的连续方块碰撞检测。 */
        private Vec3d position,velocity=Vec3d.ZERO;
        /** 新挂点只记录位置，必须手动输入才上下移动。 */
        private Rider(double distance){this.distance=distance;}
    }
    /** net.minecraft.entity.EntityType 工厂入口，COMMON；节点在部署或恢复时建立。 */
    public RopeSeatEntity(EntityType<?> type,World world){super(type,world);}
    /** 注册锚点与形状默认值，服务端计算后同步。 */
    @Override protected void initDataTracker(DataTracker.Builder builder){builder.add(ANCHOR,-1);builder.add(SHAPE,new NbtCompound());}
    /** 禁用客户端载具坐标权威，多人挂点由服务端计算。 */
    @Override public LivingEntity getControllingPassenger(){return null;}
    /** 同一根绳允许多名乘员，不沿用机舱两座上限。 */
    @Override protected boolean canAddPassenger(Entity passenger){return helicopter()!=null;}
    /** net.minecraft.server.world.ServerWorld 按 UUID 找锚点；客户端按跟踪 ID 查找。 */
    public HelicopterEntity helicopter(){
        Entity entity=getWorld().isClient?getWorld().getEntityById(dataTracker.get(ANCHOR))
                :anchorUuid==null?null:((ServerWorld)getWorld()).getEntity(anchorUuid);
        return entity instanceof HelicopterEntity heli&&!heli.isRemoved()&&!heli.flight.wreck?heli:null;
    }
    /** 部署时初始化完整绳形；后续乘员攀爬只改变自己的距离。 */
    public void attach(HelicopterEntity heli){
        anchorUuid=heli.getUuid();dataTracker.set(ANCHOR,heli.getId());
        length=availableLength(heli);initializeNodes(heli.getPos());setPosition(nodes[SEGMENTS]);syncShape();
    }
    /** 总长取展开长度和机腹离地净空的较小值，留 0.2 格端点间隙。 */
    private double availableLength(HelicopterEntity heli){return Math.max(0,Math.min(heli.ropeLength,heli.heightAboveGround()-.2));}
    /** 恢复垂直初始形状，不写入世界方块。 */
    private void initializeNodes(Vec3d anchor){
        for(int i=0;i<=SEGMENTS;i++)nodes[i]=previous[i]=frame[i]=anchor.add(0,-length*i/SEGMENTS,0);
    }
    /**
     * 吸附到最近绳段，机内挂顶部；net.minecraft.entity.Entity#startRiding 建立服务端乘骑。
     * 服务端复核外部抓取距离不超过三格；挂绳不自动上升/下降。
     */
    public boolean board(Entity passenger,boolean fromOutside){
        HelicopterEntity heli=helicopter();
        if(getWorld().isClient||heli==null||!heli.flight.rope||passenger.isSpectator())return false;
        if(nodes[0]==null)initializeNodes(heli.getPos());
        double distance=fromOutside?nearestDistance(passenger.getPos()):topDistance(passenger);
        if(fromOutside&&pointAt(distance).squaredDistanceTo(passenger.getPos())>9)return false;
        distance=Math.max(distance,topDistance(passenger));
        if(!passenger.startRiding(this,true))return false;
        Rider rider=new Rider(distance);rider.position=passenger.getPos();
        riders.put(passenger.getUuid(),rider);passenger.fallDistance=0;syncShape();return true;
    }
    /** 沿真实折线投影，允许抓取绳中部而非仅抓取末端。 */
    private double nearestDistance(Vec3d position){
        double best=Double.POSITIVE_INFINITY,result=0;
        for(int i=0;i<SEGMENTS;i++){
            Vec3d segment=nodes[i+1].subtract(nodes[i]);double square=segment.lengthSquared();
            double t=square==0?0:Math.clamp(position.subtract(nodes[i]).dotProduct(segment)/square,0,1);
            double error=nodes[i].add(segment.multiply(t)).squaredDistanceTo(position);
            if(error<best){best=error;result=length*(i+t)/SEGMENTS;}
        }
        return result;
    }
    /** 候选绳按玩家到实际曲线的距离筛选，不能用绳端距离。 */
    public double distanceToRope(Vec3d position){return nodes[0]==null?Double.POSITIVE_INFINITY:pointAt(nearestDistance(position)).distanceTo(position);}
    /** 沿绳距离插值；该距离不作为总长或绳端位置。 */
    private Vec3d pointAt(double distance){
        double at=length<=0?0:Math.clamp(distance/length*SEGMENTS,0,SEGMENTS);
        int index=Math.min(SEGMENTS-1,(int)at);return nodes[index].lerp(nodes[index+1],at-index);
    }
    /**
     * 以机模起落架的最低包络留出头顶净空，单位为世界 Y；只读，不改变飞控。
     * 模型范围来自 client.HelicopterRenderer：横向 21/16、纵向 29/16 格，底面 1/16 格。
     * 两端共用服务端姿态；客户端同时取可见平滑姿态的较低边界，避免转弯插值时穿模。
     */
    private double headCeiling(){
        HelicopterEntity heli=helicopter();
        if(heli==null)return nodes[0].y-HEAD_CLEARANCE;
        double bottom=bellyBottom(heli.flight.pitch,heli.flight.roll);
        if(getWorld().isClient)bottom=Math.min(bottom,Math.min(bellyBottom(heli.visualPitch,heli.visualRoll),
                bellyBottom(heli.previousVisualPitch,heli.previousVisualRoll)));
        return heli.getY()+bottom-HEAD_CLEARANCE;
    }
    /** 俯仰/横滚旋转后的保守机腹下界；起落架收起时仍保留同样的安全空间。 */
    private static double bellyBottom(double pitch,double roll){
        double p=Math.toRadians(pitch),r=Math.toRadians(roll);
        return Math.min(0,Math.cos(p)*Math.cos(r)/16-21.0/16*Math.abs(Math.cos(p)*Math.sin(r))
                -29.0/16*Math.abs(Math.sin(p)));
    }
    /**
     * 沿 net.minecraft.util.math.Vec3d 绳段寻找头顶不超过机腹的首个挂点。
     * 乘员高度使用 net.minecraft.entity.Entity#getHeight；折线倾斜时仍按垂直净空限制。
     * 两端只读；短绳返回末端，最终位置另夹紧，防止收短或贴地时将人物抬进机模。
     */
    private double topDistance(Entity passenger){
        double limit=headCeiling()-passenger.getHeight()-FEET_OFFSET;
        // 客户端节点仍存放快照坐标；净空判断同步减去整绳的锚点平移，和最终人物位置一致。
        HelicopterEntity heli=helicopter();
        if(getWorld().isClient&&heli!=null)limit-=heli.getY()-nodes[0].y;
        for(int i=0;i<SEGMENTS;i++){
            if(nodes[i].y<=limit)return length*i/SEGMENTS;
            if(nodes[i+1].y<=limit){
                double fraction=(nodes[i].y-limit)/(nodes[i].y-nodes[i+1].y);
                return length*(i+fraction)/SEGMENTS;
            }
        }
        return length;
    }
    /**
     * HUD 直接读取服务端许可，服务端 E 再检查挂点和空座；不使用客户端平滑绳形推断资格。
     * net.minecraft.entity.Entity#hasPassenger 复核真实乘骑；同步挂点尚未到达客户端时不提示。
     */
    public boolean canBoardHelicopter(PlayerEntity player){
        HelicopterEntity heli=helicopter();Rider rider=riders.get(player.getUuid());
        if(heli==null||!heli.flight.rope||!hasPassenger(player)||nodes[0]==null||rider==null)return false;
        if(!heli.canBoardRopeCopilot())return false;
        if(getWorld().isClient)return rider.boardReady;
        double reach=BOARD_REACH;
        if(getWorld().getTime()<=rider.boardUntil&&rider.input>=0)reach+=BOARD_GRACE_DISTANCE;
        double top=topDistance(player);
        // 弯绳到顶时的真实挂点可能横向偏移；不能用垂直净空代替到锚点的实际距离。
        double anchorReach=pointAt(top).distanceTo(nodes[0])+reach;
        return rider.distance<=top+reach&&pointAt(rider.distance).squaredDistanceTo(nodes[0])<=anchorReach*anchorReach;
    }
    /**
     * 校验真实乘骑关系后记录 W/S；E 只在顶部且副驾空闲时入舱。
     * Shift 沿用离地三格与三秒二次确认；各人按实际脚下 net.minecraft.world.World#raycast 判断。
     */
    public void control(PlayerEntity player,ControlPayload packet){
        if(!packet.valid()||!hasPassenger(player))return;
        Rider rider=riders.computeIfAbsent(player.getUuid(),id->new Rider(nearestDistance(player.getPos())));
        rider.input=Math.clamp(packet.collective(),-1,1);rider.inputTick=getWorld().getTime();
        if((packet.actions()&ControlPayload.ROPE_BOARD)!=0&&canBoardHelicopter(player)){
            HelicopterEntity heli=helicopter();
            if(heli.boardRopeCopilot(player))return;
        }
        if((packet.actions()&ControlPayload.DISMOUNT)!=0){
            long now=getWorld().getTime();
            if(groundDistance(player)<=3||player.isTouchingWater()||rider.exitUntil>=now){
                Vec3d velocity=getVelocity();player.stopRiding();player.setVelocity(velocity);
            }else{rider.exitUntil=now+60;player.sendMessage(Text.translatable("message.no_24_heli.rope_exit_warning"),true);}
        }
    }
    /** 读取每名乘员脚下四格碰撞/水面，不能用整根绳端高度判断所有玩家。 */
    private double groundDistance(Entity passenger){
        Vec3d feet=passenger.getPos();
        var hit=getWorld().raycast(new RaycastContext(feet.add(0,.05,0),feet.add(0,-4,0),RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.ANY,this));
        return hit.getType()==HitResult.Type.MISS?5:Math.max(0,feet.y-hit.getPos().y);
    }
    /** 离绳、转移或退出清理该人的输入和确认，不影响其他乘员。 */
    @Override protected void removePassenger(Entity passenger){super.removePassenger(passenger);riders.remove(passenger.getUuid());}
    /**
     * Verlet 积分保留每段惯性，固定机腹后迭代约束段长；节点碰撞可使绳子弯曲。
     * net.minecraft.world.World#raycast 防止节点穿障，不对机体施力或更改飞控。
     */
    private void simulate(HelicopterEntity heli){
        length=availableLength(heli);if(nodes[0]==null)initializeNodes(heli.getPos());
        Vec3d[] before=nodes.clone();double segmentLength=length/SEGMENTS;nodes[0]=heli.getPos();
        // 负载仅取真实挂绳玩家，人数越多衰减越强；不重置 previous，挂入和离开时连续保留惯性。
        long load=getPassengerList().stream().filter(passenger->passenger instanceof PlayerEntity).count();
        double damping=LOADED_DAMPING+(DAMPING-LOADED_DAMPING)/(1+LOAD_RESPONSE*load);
        for(int i=1;i<=SEGMENTS;i++){
            Vec3d current=nodes[i];nodes[i]=current.add(current.subtract(previous[i]).multiply(damping)).add(0,-GRAVITY,0);previous[i]=current;
        }
        for(int pass=0;pass<CONSTRAINT_PASSES;pass++){
            nodes[0]=heli.getPos();
            for(int i=0;i<SEGMENTS;i++){
                Vec3d offset=nodes[i+1].subtract(nodes[i]);double distance=offset.length();
                if(distance<.000001)continue;
                Vec3d correction=offset.multiply((distance-segmentLength)/distance);
                if(i==0)nodes[i+1]=nodes[i+1].subtract(correction);
                else{nodes[i]=nodes[i].add(correction.multiply(.5));nodes[i+1]=nodes[i+1].subtract(correction.multiply(.5));}
            }
        }
        for(int i=1;i<=SEGMENTS;i++){
            // 最后一遍从固定端投影，消除迭代误差累积，保证总长不超过展开长度。
            Vec3d offset=nodes[i].subtract(nodes[i-1]);
            nodes[i]=nodes[i-1].add(offset.lengthSquared()<.00000001?new Vec3d(0,-segmentLength,0):offset.normalize().multiply(segmentLength));
        }
        for(int i=1;i<=SEGMENTS;i++){
            var hit=getWorld().raycast(new RaycastContext(before[i],nodes[i],RaycastContext.ShapeType.COLLIDER,RaycastContext.FluidHandling.NONE,this));
            if(hit.getType()!=HitResult.Type.MISS){nodes[i]=hit.getPos().add(before[i].subtract(nodes[i]).normalize().multiply(.03));previous[i]=nodes[i];}
        }
        // 碰撞修正会把节点留在墙前，必须在其后再限制段长；否则飞机远离后绳索可无限拉伸。
        // 同步平移 previous，避免长度修正被下一 tick 误当成额外的甩动速度。
        for(int i=1;i<=SEGMENTS;i++){
            Vec3d offset=nodes[i].subtract(nodes[i-1]);
            if(offset.lengthSquared()>segmentLength*segmentLength){
                Vec3d limited=nodes[i-1].add(offset.normalize().multiply(segmentLength));
                previous[i]=previous[i].add(limited.subtract(nodes[i]));nodes[i]=limited;
            }
        }
        Vec3d old=getPos();setPosition(nodes[SEGMENTS]);setVelocity(getPos().subtract(old));
    }
    /** 绳形与各人挂点同 tick 同步，渲染和乘员使用同一折线。 */
    private void syncShape(){
        NbtCompound shape=new NbtCompound();NbtList points=new NbtList(),positions=new NbtList();shape.putDouble("Length",length);
        for(Vec3d node:nodes){NbtCompound point=new NbtCompound();point.putDouble("X",node.x);point.putDouble("Y",node.y);point.putDouble("Z",node.z);points.add(point);}
        for(Entity passenger:getPassengerList()){
            Rider state=riders.get(passenger.getUuid());if(state==null)continue;
            state.boardReady=passenger instanceof PlayerEntity player&&canBoardHelicopter(player);
            // 只在原始半格范围内续期，宽限区自身不能无限续期。
            if(state.boardReady&&state.distance<=topDistance(passenger)+BOARD_REACH)state.boardUntil=getWorld().getTime()+BOARD_GRACE_TICKS;
            NbtCompound rider=new NbtCompound();rider.putUuid("Id",passenger.getUuid());
            rider.putDouble("Distance",state.distance);rider.putBoolean("BoardReady",state.boardReady);positions.add(rider);
        }
        shape.put("Points",points);shape.put("Riders",positions);dataTracker.set(SHAPE,shape);
    }
    /** 客户端仅插值服务器节点，不积分、不预测攀爬输入。 */
    private void tickClient(){
        if(lerpTicks>0){setPosition(getX()+(lerpX-getX())/lerpTicks,getY()+(lerpY-getY())/lerpTicks,getZ()+(lerpZ-getZ())/lerpTicks);lerpTicks--;}
        NbtCompound shape=dataTracker.get(SHAPE);NbtList points=shape.getList("Points",NbtElement.COMPOUND_TYPE);
        if(points.size()!=SEGMENTS+1)return;length=shape.getDouble("Length");
        for(int i=0;i<=SEGMENTS;i++){
            NbtCompound point=points.getCompound(i);Vec3d target=new Vec3d(point.getDouble("X"),point.getDouble("Y"),point.getDouble("Z"));
            frame[i]=nodes[i]==null?target:nodes[i];nodes[i]=frame[i].lerp(target,.65);
        }
        for(NbtElement element:shape.getList("Riders",NbtElement.COMPOUND_TYPE)){
            NbtCompound saved=(NbtCompound)element;UUID id=saved.getUuid("Id");double distance=saved.getDouble("Distance");
            Rider rider=riders.computeIfAbsent(id,key->new Rider(distance));rider.distance+=(distance-rider.distance)*.65;
            rider.boardReady=saved.getBoolean("BoardReady");
        }
    }
    /** 更新完整绳形和独立挂点；松键保持距离，机体运动仍会带动整绳。 */
    @Override public void tick(){
        super.tick();if(getWorld().isClient){tickClient();return;}
        fallDistance=0;for(Entity passenger:getPassengerList())passenger.fallDistance=0;
        HelicopterEntity heli=helicopter();
        if(heli==null||!heli.flight.rope){
            if(!hasPassengers()){discard();return;}
            if(heli==null&&anchorUuid!=null&&missingTicks++<100)return;
            Vec3d descent=new Vec3d(0,-DESCEND_SPEED,0),before=getPos();move(MovementType.SELF,descent);
            if(nodes[0]!=null)for(int i=0;i<=SEGMENTS;i++)nodes[i]=nodes[i].add(getPos().subtract(before));
            if(isOnGround()||isTouchingWater()){removeAllPassengers();discard();}else if(nodes[0]!=null)syncShape();return;
        }
        if(heli.ropeCarrier()!=this){
            // 同一飞机只保留一个权威绳实体，旧存档中的重复绳不再参与模拟或拉走乘员。
            for(Entity passenger:java.util.List.copyOf(getPassengerList())){
                Vec3d position=passenger.getPos();passenger.stopRiding();passenger.requestTeleport(position.x,position.y,position.z);
            }
            discard();return;
        }
        missingTicks=0;dataTracker.set(ANCHOR,heli.getId());simulate(heli);
        for(Entity passenger:java.util.List.copyOf(getPassengerList())){
            Rider rider=riders.computeIfAbsent(passenger.getUuid(),id->new Rider(topDistance(passenger)));
            // 村民保留原有部署后自动送到地面的救援行为；玩家必须自己按 W/S。
            float input=passenger instanceof PlayerEntity?(getWorld().getTime()-rider.inputTick<10?rider.input:0):-1;
            rider.distance=Math.clamp(rider.distance+(input>0?-CLIMB_SPEED*input:-DESCEND_SPEED*input),topDistance(passenger),length);
            if(!(passenger instanceof PlayerEntity)&&rider.distance>=length&&groundDistance(passenger)<.4)passenger.stopRiding();
        }
        syncShape();
    }
    /** 所有节点统一补偿锚点插值差，不能只拉动第零节点而让第一段凭空折角或伸长。 */
    public Vec3d ropePoint(int index,float delta){
        if(nodes[index]==null)return getLerpedPos(delta);
        Vec3d point=frame[index]==null?nodes[index]:frame[index].lerp(nodes[index],delta);
        HelicopterEntity heli=helicopter();if(heli==null)return point;
        Vec3d anchor=frame[0]==null?nodes[0]:frame[0].lerp(nodes[0],delta);
        return point.add(heli.getLerpedPos(delta).subtract(anchor));
    }
    /** 按整根弯曲绳形做视锥裁剪，避免绳端出屏幕后整绳消失。 */
    public Box ropeBounds(){
        Box bounds=getBoundingBox();
        for(int i=0;i<=SEGMENTS;i++)if(nodes[i]!=null){
            Vec3d before=ropePoint(i,0),after=ropePoint(i,1);bounds=bounds.union(new Box(before,after));
        }
        return bounds.expand(1);
    }
    /** net.minecraft.entity.Entity 的标准服务端位置插值入口。 */
    @Override public void updateTrackedPositionAndAngles(double x,double y,double z,float yaw,float pitch,int steps){lerpX=x;lerpY=y;lerpZ=z;lerpTicks=Math.max(1,steps);}
    /** 挂绳期间不累计跌落伤害，主动脱离后的原版跌落照常处理。 */
    @Override public boolean handleFallDamage(float distance,float multiplier,net.minecraft.entity.damage.DamageSource source){return false;}
    /** net.minecraft.entity.Entity.PositionUpdater 按各人的距离定位到当前曲线。 */
    @Override protected void updatePassengerPosition(Entity passenger,PositionUpdater updater){
        if(nodes[0]==null)return;Rider rider=riders.get(passenger.getUuid());
        Vec3d point=pointAt(Math.max(topDistance(passenger),rider==null?0:rider.distance));
        HelicopterEntity heli=helicopter();
        if(getWorld().isClient&&heli!=null)point=point.add(heli.getPos().subtract(nodes[0]));
        Vec3d target=new Vec3d(point.x,Math.min(point.y+FEET_OFFSET,headCeiling()-passenger.getHeight()),point.z);
        if(!getWorld().isClient&&passenger instanceof PlayerEntity player&&rider!=null&&detachOnCollision(player,rider,target))return;
        updater.accept(passenger,target.x,target.y,target.z);
        passenger.fallDistance=0;
    }
    /**
     * 服务端用 net.minecraft.entity.Entity#adjustMovementForCollisions 扫过整个人物碰撞箱，
     * 仅加入方块/世界边界，不把飞机或同绳乘员当成墙。受阻立即脱绳，按撞击速度平方扣血。
     * net.minecraft.entity.Entity#requestTeleport 覆盖原版离座寻位，保留墙前安全位置；
     * flyIntoWall 使用原版撞墙伤害规则，正常防护/创造无敌仍有效，高速碰撞可以致命。
     * 仅服务端乘员定位入口调用；记录上次权威位置，避免客户端移动包把碰撞起点推到墙后。
     */
    private boolean detachOnCollision(PlayerEntity player,Rider rider,Vec3d target){
        Vec3d start=rider.position==null?player.getPos():rider.position,requested=target.subtract(start);
        Box box=player.getBoundingBox().offset(start.subtract(player.getPos()));
        Vec3d allowed=Entity.adjustMovementForCollisions(player,requested,box,getWorld(),java.util.List.of());
        boolean blockedX=Math.abs(requested.x-allowed.x)>COLLISION_EPSILON;
        boolean blockedY=Math.abs(requested.y-allowed.y)>COLLISION_EPSILON;
        boolean blockedZ=Math.abs(requested.z-allowed.z)>COLLISION_EPSILON;
        boolean embedded=getWorld().getBlockCollisions(player,box.contract(COLLISION_EPSILON)).iterator().hasNext();
        if(!blockedX&&!blockedY&&!blockedZ&&!embedded){rider.position=target;rider.velocity=requested;return false;}
        Vec3d safe=embedded?start:start.add(allowed);
        double speedSquared=Math.max(requested.lengthSquared(),rider.velocity.lengthSquared());
        player.stopRiding();player.requestTeleport(safe.x,safe.y,safe.z);
        player.setVelocity(blockedX||embedded?0:requested.x,blockedY||embedded?0:requested.y,blockedZ||embedded?0:requested.z);
        player.velocityModified=true;player.fallDistance=0;
        player.damage(getDamageSources().flyIntoWall(),(float)(IMPACT_DAMAGE_SCALE*speedSquared));
        return true;
    }
    /** 存档边界恢复有限挂点；旧 Direction 忽略，重载不恢复自动移动。 */
    @Override protected void readCustomDataFromNbt(NbtCompound nbt){
        anchorUuid=nbt.containsUuid("Anchor")?nbt.getUuid("Anchor"):null;
        double saved=nbt.getDouble("Length");length=Double.isFinite(saved)?Math.clamp(saved,0,24):2.5;riders.clear();
        for(NbtElement element:nbt.getList("Riders",NbtElement.COMPOUND_TYPE)){
            NbtCompound rider=(NbtCompound)element;double distance=rider.getDouble("Distance");
            if(rider.containsUuid("Id")&&Double.isFinite(distance))riders.put(rider.getUuid("Id"),new Rider(Math.clamp(distance,0,24)));
        }
    }
    /** 保存位置但不保存按住状态和确认窗口，重载必须重新按 W/S。 */
    @Override protected void writeCustomDataToNbt(NbtCompound nbt){
        if(anchorUuid!=null)nbt.putUuid("Anchor",anchorUuid);nbt.putDouble("Length",length);NbtList saved=new NbtList();
        for(var entry:riders.entrySet()){NbtCompound rider=new NbtCompound();rider.putUuid("Id",entry.getKey());rider.putDouble("Distance",entry.getValue().distance);saved.add(rider);}
        nbt.put("Riders",saved);
    }
}
