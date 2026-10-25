/** 文件：模组公共入口，负责基础注册、配置加载和服务端网络接收；端侧：COMMON；模组 ID：no_24_heli。 */
package org.DJB.no_24_heli;

import org.DJB.no_24_heli.block.HelipadBlock;
import org.DJB.no_24_heli.block.HelipadBlockEntity;
import org.DJB.no_24_heli.block.HelipadSurfaceBlock;
import org.DJB.no_24_heli.block.RadarBlock;
import org.DJB.no_24_heli.data.AirspaceState;
import org.DJB.no_24_heli.data.HeliConfig;
import org.DJB.no_24_heli.data.PlayerFlightSettings;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.entity.RopeSeatEntity;
import org.DJB.no_24_heli.item.HelicopterItem;
import org.DJB.no_24_heli.item.HelipadItem;
import org.DJB.no_24_heli.network.AirspacePayload;
import org.DJB.no_24_heli.network.ControlPayload;
import org.DJB.no_24_heli.network.DifficultyPayload;
import org.DJB.no_24_heli.network.HelipadMapPayload;
import org.DJB.no_24_heli.network.NavigationPayload;
import org.DJB.no_24_heli.network.RadarContactsPayload;
import org.DJB.no_24_heli.network.RadarPayload;
import org.DJB.no_24_heli.network.RadioPayload;
import org.DJB.no_24_heli.network.SettingsPayload;
import org.DJB.no_24_heli.screen.HeliScreenHandler;
import org.DJB.no_24_heli.server.RadioTransferServer;



import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.block.*;
import net.minecraft.entity.*;
import net.minecraft.item.*;
import net.minecraft.registry.*;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.*;

/**
 * 职责：模组入口与基础注册表，按方块/物品、载具、容器、声音、网络接收器的顺序初始化。
 * 引用：net.fabricmc.api.ModInitializer 提供 onInitialize 生命周期；
 *      Registry/Registries 完成静态注册，PayloadTypeRegistry/ServerPlayNetworking 注册 C2S/S2C。
 * 生命周期：Fabric 加载器在公共端启动时调用一次；只做注册与事件挂接，不写飞行业务逻辑。
 * 端侧/线程：COMMON；onInitialize 在主初始化线程执行，网络接收器内部切回服务端主线程。
 * 排查：内容不出现查注册名/MOD_ID/注册时机；收不到包查 C2S/S2C 方向与 payload ID；
 *      客户端崩溃查是否把客户端类放进公共入口。
 */
public class No_24_heli implements ModInitializer {
    /** 模组 ID 与全局日志器；所有注册名、网络通道和配置键统一使用该前缀。 */
    public static final String MOD_ID = "no_24_heli";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    /** 统一构造 no_24_heli 命名空间的 Identifier，禁止在各处散落字符串拼接。 */
    public static Identifier id(String name) { return Identifier.of(MOD_ID, name); }
    /** 注册无特殊行为的普通物品；特殊物品在各自的 ModXxx 注册类中创建。 */
    private static Item item(String name) { return Registry.register(Registries.ITEM,id(name),new Item(new Item.Settings())); }
    /** 五种组装零件、维修包、两种燃料；注册名即存档 ID，发布后不能改名。 */
    public static final Item ROTOR=item("rotor_assembly"), TAIL=item("tail_rotor"), ENGINE=item("engine_module"), TANK=item("fuel_tank"), FUSELAGE=item("fuselage"), REPAIR=item("repair_kit"), KEROSENE=item("kerosene"), CRUDE=item("crude_oil");
    /** 六种涂装按存档编号 0..5 排列，数组顺序不能重排，否则旧存档外观会串色。 */
    public static final Item[] PAINTS={item("paint_olive"),item("paint_snow"),item("paint_desert"),item("paint_black"),item("paint_rescue"),item("paint_purple_gold")};
    public static final Block HELIPAD=Registry.register(Registries.BLOCK,id("helipad"),new HelipadBlock(AbstractBlock.Settings.create().strength(4).requiresTool().nonOpaque().pistonBehavior(net.minecraft.block.piston.PistonBehavior.BLOCK)));
    /** 圆盘承重碎片和渲染实体与中心使用同一材质，不能由活塞拆散。 */
    public static final Block HELIPAD_SURFACE=Registry.register(Registries.BLOCK,id("helipad_surface"),new HelipadSurfaceBlock(AbstractBlock.Settings.create().strength(4).requiresTool().nonOpaque().pistonBehavior(net.minecraft.block.piston.PistonBehavior.BLOCK)));
    /** 停机坪中心与承重碎片的方块实体类型；加载索引用于地图和局部雷达，不强制加载远处区块。 */
    public static final net.minecraft.block.entity.BlockEntityType<HelipadBlockEntity> HELIPAD_ENTITY=Registry.register(Registries.BLOCK_ENTITY_TYPE,id("helipad"),net.minecraft.block.entity.BlockEntityType.Builder.create(HelipadBlockEntity::new,HELIPAD,HELIPAD_SURFACE).build(null));
    /** 地面雷达的可放置物品与方块使用同一注册 ID。 */
    public static final Block RADAR=Registry.register(Registries.BLOCK,id("radar"),new RadarBlock(AbstractBlock.Settings.create().strength(3).requiresTool().nonOpaque().pistonBehavior(net.minecraft.block.piston.PistonBehavior.BLOCK)));
    public static final Item RADAR_ITEM=Registry.register(Registries.ITEM,id("radar"),new BlockItem(RADAR,new Item.Settings()));
    /** 停机坪放置物品；整机物品只用于创造/回收，生存制造走停机坪零件组装。 */
    public static final Item HELIPAD_ITEM=Registry.register(Registries.ITEM,id("helipad"),new HelipadItem(HELIPAD,new Item.Settings()));
    public static final Item HELICOPTER=Registry.register(Registries.ITEM,id("helicopter"),new HelicopterItem(new Item.Settings().maxCount(1)));
    /** 直升机注册尺寸 3.4×2.6；真实碰撞由 HelicopterCollision 的分部盒裁剪，不依赖该总尺寸。 */
    public static final EntityType<HelicopterEntity> HELI=Registry.register(Registries.ENTITY_TYPE,id("helicopter"),EntityType.Builder.<HelicopterEntity>create(HelicopterEntity::new,SpawnGroup.MISC).dimensions(3.4f,2.6f).maxTrackingRange(12).trackingTickInterval(1).build());
    /** 多人绳索载体的注册尺寸；绳节点形状与乘员位置由实体自身同步。 */
    public static final EntityType<RopeSeatEntity> ROPE=Registry.register(Registries.ENTITY_TYPE,id("rope_seat"),EntityType.Builder.<RopeSeatEntity>create(RopeSeatEntity::new,SpawnGroup.MISC).dimensions(.6f,.4f).maxTrackingRange(8).trackingTickInterval(1).build());
    /** 燃油/货仓/耐久服务端容器；客户端只按服务端属性委托显示。 */
    public static final ScreenHandlerType<HeliScreenHandler> SCREEN=Registry.register(Registries.SCREEN_HANDLER,id("service"),new ScreenHandlerType<>(HeliScreenHandler::new,FeatureFlags.VANILLA_FEATURES));
    /** 旋翼、电台、警报和旋翼受损声音事件；资源键与 assets/no_24_heli/sounds.json 对应。 */
    public static final SoundEvent ROTOR_SOUND=Registry.register(Registries.SOUND_EVENT,id("rotor"),SoundEvent.of(id("rotor")));
    public static final SoundEvent RADIO_SOUND=Registry.register(Registries.SOUND_EVENT,id("radio"),SoundEvent.of(id("radio")));
    public static final SoundEvent ALARM=Registry.register(Registries.SOUND_EVENT,id("alarm"),SoundEvent.of(id("alarm")));
    public static final SoundEvent DAMAGE_SOUND=Registry.register(Registries.SOUND_EVENT,id("rotor_damage"),SoundEvent.of(id("rotor_damage")));
    /** 生命周期入口：先加载配置，再注册专项内容与网络，顺序影响配方/进度/接收器可用性。 */
    @Override public void onInitialize() {
        HeliConfig.load();
        org.DJB.no_24_heli.entity.CabinPlayerState.initialize();
        org.DJB.no_24_heli.registry.ModItems.register();
        org.DJB.no_24_heli.registry.ModParachutes.initialize();
        org.DJB.no_24_heli.registry.ModAdvancements.initialize();
        RadioTransferServer.register();
        // 电台选曲：只接受机上乘员，具体权限、残骸和 5 tick 冷却由实体复核。
        PayloadTypeRegistry.playC2S().register(RadioPayload.ID,RadioPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RadioPayload.ID,(packet,context)->context.server().execute(()->{
            if(context.player().getVehicle() instanceof HelicopterEntity heli)heli.selectRadio(context.player(),packet.track());
        }));
        // 导航设点：只把坐标意图交给实体，合法性（边界、禁飞区、乘员身份）全部在服务端判断。
        PayloadTypeRegistry.playC2S().register(NavigationPayload.ID,NavigationPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(NavigationPayload.ID,(p,c)->c.server().execute(()->{
            if(c.player().getVehicle() instanceof HelicopterEntity h)h.navigate(c.player(),p);
        }));
        // 雷达/停机坪/空域快照均为 S2C；客户端只显示，真实归属和修改权限仍由服务端校验。
        PayloadTypeRegistry.playS2C().register(RadarContactsPayload.ID,RadarContactsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(org.DJB.no_24_heli.network.NavigationPathPayload.ID,
                org.DJB.no_24_heli.network.NavigationPathPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(HelipadMapPayload.ID,HelipadMapPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AirspacePayload.ID,AirspacePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RadarPayload.ID,RadarPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RadarPayload.ID,RadarPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RadarPayload.ID,(p,c)->c.server().execute(()->{
            var player=c.player();var world=player.getServerWorld();
            // 距离/已加载区块/真实雷达/归属都以服务器为准，不能远程篡改别人的禁飞区。
            if(player.squaredDistanceTo(p.pos().toCenterPos())>64||!world.isChunkLoaded(p.pos())||!world.getBlockState(p.pos()).isOf(RADAR))return;
            var state=AirspaceState.get(world);var zone=state.at(p.pos());if(zone==null)return;
            boolean allowed=zone.owner().equals(player.getUuid())||player.hasPermissionLevel(2);
            boolean saved=!p.update();
            if(p.update()&&allowed&&p.radius()>=AirspaceState.MIN_RADIUS&&p.radius()<=AirspaceState.MAX_RADIUS)
                saved=state.put(p.pos(),p.radius(),zone.owner());
            ServerPlayNetworking.send(player,new RadarPayload(p.pos(),state.at(p.pos()).radius(),p.update(),allowed,saved));
        }));
        // 乘员无法射线选中自己的载具，因此空中维修走物品使用事件。
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player,world,hand)->{
            var stack=player.getStackInHand(hand);
            if((stack.isOf(REPAIR)||stack.isOf(Items.LEAD))&&player.getVehicle() instanceof HelicopterEntity heli){
                if(!world.isClient)heli.interact(player,hand);
                return net.minecraft.util.TypedActionResult.success(stack,world.isClient);
            }
            return net.minecraft.util.TypedActionResult.pass(stack);
        });
        // 创造物品组只影响展示，不改变任何物品的注册 ID 或生存获取方式。
        Registry.register(Registries.ITEM_GROUP,id("equipment"),FabricItemGroup.builder().displayName(Text.translatable("itemGroup.no_24_heli")).icon(()->new ItemStack(HELICOPTER)).entries((ctx,entries)->{
            entries.add(org.DJB.no_24_heli.registry.ModItems.PILOT_HEADSET);
            entries.add(org.DJB.no_24_heli.registry.ModItems.PILOT_GOGGLES);
            entries.add(HELICOPTER); entries.add(org.DJB.no_24_heli.registry.ModParachutes.PARACHUTE); entries.add(ROTOR); entries.add(TAIL); entries.add(ENGINE); entries.add(TANK); entries.add(FUSELAGE); entries.add(KEROSENE); entries.add(CRUDE); entries.add(REPAIR); entries.add(HELIPAD_ITEM); entries.add(RADAR_ITEM); for(Item paint:PAINTS) entries.add(paint);
        }).build());
        // 个人难度：仅改发包者自己的 UUID，飞机上的实际效果由主驾模式决定；回执带服务端权威值。
        PayloadTypeRegistry.playC2S().register(DifficultyPayload.ID,DifficultyPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(DifficultyPayload.ID,DifficultyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(DifficultyPayload.ID,(packet,context)->context.server().execute(()->{
            var player=context.player();var settings=PlayerFlightSettings.get(context.server());
            // 只能修改发包者自己的 UUID，普通玩家和管理员使用相同的个人模式接口。
            boolean valid=packet.mode()>=0&&packet.mode()<HeliConfig.Difficulty.values().length;
            boolean saved=!packet.update()||valid;
            if(packet.update()&&valid){
                var mode=HeliConfig.Difficulty.values()[packet.mode()];settings.set(player.getUuid(),mode);
                if(player.getVehicle() instanceof HelicopterEntity heli&&heli.isPilot(player))heli.applyDifficulty(mode);
            }
            ServerPlayNetworking.send(player,new DifficultyPayload(false,settings.mode(player.getUuid()).ordinal(),true,saved));
        }));
        // 共享配置：管理员或单人房主才能写；写盘失败回滚内存值，避免服务端运行值与文件不一致。
        PayloadTypeRegistry.playC2S().register(SettingsPayload.ID,SettingsPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SettingsPayload.ID,SettingsPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(SettingsPayload.ID,(packet,context)->context.server().execute(()->{
            boolean allowed=context.player().hasPermissionLevel(2)||context.server().isHost(context.player().getGameProfile());
            boolean saved=!packet.update()||allowed;
            if(packet.update()&&allowed){
                boolean old=HeliConfig.INSTANCE.explosionBlockDamage;
                HeliConfig.INSTANCE.explosionBlockDamage=packet.destruction();
                saved=HeliConfig.save();
                if(!saved)HeliConfig.INSTANCE.explosionBlockDamage=old;
            }
            ServerPlayNetworking.send(context.player(),new SettingsPayload(false,HeliConfig.INSTANCE.explosionBlockDamage,allowed,saved));
        }));
        // 舱门/乘降意图：客户端只给实体 ID，服务端按真实位置、座位和门状态决定登机或下机。
        PayloadTypeRegistry.playC2S().register(org.DJB.no_24_heli.network.CabinDoorPayload.ID,
                org.DJB.no_24_heli.network.CabinDoorPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(org.DJB.no_24_heli.network.CabinDoorPayload.ID,
                (packet,context)->context.server().execute(()->{
                    // 只按当前维度查找，舱位组件复核乘降范围，机内 F 与 Shift 共用危险确认。
                    if(context.player().getWorld().getEntityById(packet.entityId()) instanceof HelicopterEntity heli)
                        heli.cabin.interact(context.player());
                }));
        // 飞行输入：按当前载具分派；无载具时只允许“抓住附近绳段”，不创建任何飞行状态。
        PayloadTypeRegistry.playC2S().register(ControlPayload.ID,ControlPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ControlPayload.ID,(packet,context)->context.server().execute(()->{
            // 拖拽与趴地期间不接收救援绳或飞控意图，防止通过自定义乘骑绕过短时行动限制。
            if(org.DJB.no_24_heli.entity.CabinPlayerState.locked(context.player()))return;
            if(context.player().getVehicle() instanceof HelicopterEntity heli) heli.control(context.player(),packet);
            else if(context.player().getVehicle() instanceof RopeSeatEntity rope)rope.control(context.player(),packet);
            else if(packet.valid()&&(packet.actions()&ControlPayload.EXIT_ROPE)!=0&&!context.player().hasVehicle()&&!context.player().isSpectator()){
                var player=context.player();
                player.getWorld().getOtherEntities(player,player.getBoundingBox().expand(27),e->e instanceof RopeSeatEntity)
                    .stream().filter(e->((RopeSeatEntity)e).distanceToRope(player.getPos())<=3)
                    .min(java.util.Comparator.comparingDouble(e->((RopeSeatEntity)e).distanceToRope(player.getPos())))
                    .ifPresent(e->((RopeSeatEntity)e).board(player,true));
            }
        }));
    }
}
