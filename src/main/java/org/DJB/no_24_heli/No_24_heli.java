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

/** 模组入口：注册顺序为方块/物品、载具、容器、网络。 */
public class No_24_heli implements ModInitializer {
    public static final String MOD_ID = "no_24_heli";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static Identifier id(String name) { return Identifier.of(MOD_ID, name); }
    private static Item item(String name) { return Registry.register(Registries.ITEM,id(name),new Item(new Item.Settings())); }
    public static final Item ROTOR=item("rotor_assembly"), TAIL=item("tail_rotor"), ENGINE=item("engine_module"), TANK=item("fuel_tank"), FUSELAGE=item("fuselage"), REPAIR=item("repair_kit"), KEROSENE=item("kerosene"), CRUDE=item("crude_oil");
    public static final Item[] PAINTS={item("paint_olive"),item("paint_snow"),item("paint_desert"),item("paint_black"),item("paint_rescue"),item("paint_purple_gold")};
    public static final Block HELIPAD=Registry.register(Registries.BLOCK,id("helipad"),new HelipadBlock(AbstractBlock.Settings.create().strength(4).requiresTool().nonOpaque().pistonBehavior(net.minecraft.block.piston.PistonBehavior.BLOCK)));
    /** 圆盘承重碎片和渲染实体与中心使用同一材质，不能由活塞拆散。 */
    public static final Block HELIPAD_SURFACE=Registry.register(Registries.BLOCK,id("helipad_surface"),new HelipadSurfaceBlock(AbstractBlock.Settings.create().strength(4).requiresTool().nonOpaque().pistonBehavior(net.minecraft.block.piston.PistonBehavior.BLOCK)));
    public static final net.minecraft.block.entity.BlockEntityType<HelipadBlockEntity> HELIPAD_ENTITY=Registry.register(Registries.BLOCK_ENTITY_TYPE,id("helipad"),net.minecraft.block.entity.BlockEntityType.Builder.create(HelipadBlockEntity::new,HELIPAD,HELIPAD_SURFACE).build(null));
    /** 地面雷达的可放置物品与方块使用同一注册 ID。 */
    public static final Block RADAR=Registry.register(Registries.BLOCK,id("radar"),new RadarBlock(AbstractBlock.Settings.create().strength(3).requiresTool().nonOpaque().pistonBehavior(net.minecraft.block.piston.PistonBehavior.BLOCK)));
    public static final Item RADAR_ITEM=Registry.register(Registries.ITEM,id("radar"),new BlockItem(RADAR,new Item.Settings()));
    public static final Item HELIPAD_ITEM=Registry.register(Registries.ITEM,id("helipad"),new HelipadItem(HELIPAD,new Item.Settings()));
    public static final Item HELICOPTER=Registry.register(Registries.ITEM,id("helicopter"),new HelicopterItem(new Item.Settings().maxCount(1)));
    public static final EntityType<HelicopterEntity> HELI=Registry.register(Registries.ENTITY_TYPE,id("helicopter"),EntityType.Builder.<HelicopterEntity>create(HelicopterEntity::new,SpawnGroup.MISC).dimensions(3.4f,2.6f).maxTrackingRange(12).trackingTickInterval(1).build());
    public static final EntityType<RopeSeatEntity> ROPE=Registry.register(Registries.ENTITY_TYPE,id("rope_seat"),EntityType.Builder.<RopeSeatEntity>create(RopeSeatEntity::new,SpawnGroup.MISC).dimensions(.6f,.4f).maxTrackingRange(8).trackingTickInterval(1).build());
    public static final ScreenHandlerType<HeliScreenHandler> SCREEN=Registry.register(Registries.SCREEN_HANDLER,id("service"),new ScreenHandlerType<>(HeliScreenHandler::new,FeatureFlags.VANILLA_FEATURES));
    public static final SoundEvent ROTOR_SOUND=Registry.register(Registries.SOUND_EVENT,id("rotor"),SoundEvent.of(id("rotor")));
    public static final SoundEvent RADIO_SOUND=Registry.register(Registries.SOUND_EVENT,id("radio"),SoundEvent.of(id("radio")));
    public static final SoundEvent ALARM=Registry.register(Registries.SOUND_EVENT,id("alarm"),SoundEvent.of(id("alarm")));
    public static final SoundEvent DAMAGE_SOUND=Registry.register(Registries.SOUND_EVENT,id("rotor_damage"),SoundEvent.of(id("rotor_damage")));
    @Override public void onInitialize() {
        HeliConfig.load();
        org.DJB.no_24_heli.registry.ModItems.register();
        org.DJB.no_24_heli.registry.ModParachutes.initialize();
        org.DJB.no_24_heli.registry.ModAdvancements.initialize();
        RadioTransferServer.register();
        PayloadTypeRegistry.playC2S().register(RadioPayload.ID,RadioPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RadioPayload.ID,(packet,context)->context.server().execute(()->{
            if(context.player().getVehicle() instanceof HelicopterEntity heli)heli.selectRadio(context.player(),packet.track());
        }));
        PayloadTypeRegistry.playC2S().register(NavigationPayload.ID,NavigationPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(NavigationPayload.ID,(p,c)->c.server().execute(()->{
            if(c.player().getVehicle() instanceof HelicopterEntity h)h.navigate(c.player(),p);
        }));
        PayloadTypeRegistry.playS2C().register(RadarContactsPayload.ID,RadarContactsPayload.CODEC);
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
        Registry.register(Registries.ITEM_GROUP,id("equipment"),FabricItemGroup.builder().displayName(Text.translatable("itemGroup.no_24_heli")).icon(()->new ItemStack(HELICOPTER)).entries((ctx,entries)->{
            entries.add(org.DJB.no_24_heli.registry.ModItems.PILOT_HEADSET);
            entries.add(org.DJB.no_24_heli.registry.ModItems.PILOT_GOGGLES);
            entries.add(HELICOPTER); entries.add(org.DJB.no_24_heli.registry.ModParachutes.PARACHUTE); entries.add(ROTOR); entries.add(TAIL); entries.add(ENGINE); entries.add(TANK); entries.add(FUSELAGE); entries.add(KEROSENE); entries.add(CRUDE); entries.add(REPAIR); entries.add(HELIPAD_ITEM); entries.add(RADAR_ITEM); for(Item paint:PAINTS) entries.add(paint);
        }).build());
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
        PayloadTypeRegistry.playC2S().register(org.DJB.no_24_heli.network.CabinDoorPayload.ID,
                org.DJB.no_24_heli.network.CabinDoorPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(org.DJB.no_24_heli.network.CabinDoorPayload.ID,
                (packet,context)->context.server().execute(()->{
                    // 只按当前维度查找，舱位组件复核乘降范围，机内 F 与 Shift 共用危险确认。
                    if(context.player().getWorld().getEntityById(packet.entityId()) instanceof HelicopterEntity heli)
                        heli.cabin.interact(context.player());
                }));
        PayloadTypeRegistry.playC2S().register(ControlPayload.ID,ControlPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ControlPayload.ID,(packet,context)->context.server().execute(()->{
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
