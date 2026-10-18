package org.DJB.no_24_heli.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtList;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.registry.ModItems;
import org.DJB.no_24_heli.data.PilotEquipment;
import org.DJB.no_24_heli.entity.HelicopterEntity;
import org.DJB.no_24_heli.client.render.PilotEquipmentClient;
import org.DJB.no_24_heli.client.render.CockpitControlsRenderer;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.nio.file.Path;

public final class PilotVerification implements ClientModInitializer {
    private int ticks;
    private boolean started;
    private volatile boolean serverReady;
    private volatile Throwable failure;
    private volatile int heliId;
    private OtherClientPlayerEntity copilot;
    private static final Path ROOT=Path.of("C:/Users/klo/.codex/visualizations/2026/10/18/01a14f1d-f5a8-71f2-9741-064f5b5db3bc/pilot-verification");
    private static void check(boolean value,String name) { if(!value) throw new IllegalStateException(name); }
    private static void shot(MinecraftClient client,String name) throws Exception {
        try(var image=ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) { image.writeTo(ROOT.resolve(name+".png")); }
        No_24_heli.LOGGER.info("PILOT_VERIFY_SHOT {}",name);
    }
    @Override public void onInitializeClient() {
        if(!Boolean.getBoolean("heli.verification"))return;
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            try { tick(client); }
            catch(Throwable error){No_24_heli.LOGGER.error("PILOT_VERIFY_FAILED",error);client.scheduleStop();}
        });
    }
    private void tick(MinecraftClient client) throws Exception {
        if(failure!=null)throw new IllegalStateException("server verification",failure);
        check(client.runDirectory.toPath().toAbsolutePath().normalize().equals(ROOT.toAbsolutePath().normalize()), "isolated game directory");
        client.options.pauseOnLostFocus=false;
        if(client.player==null||client.world==null||client.getServer()==null)return;
        if(!started){started=true;client.getServer().execute(()->{
            try {
                var server=client.getServer();var player=server.getPlayerManager().getPlayer(client.player.getUuid());var world=player.getServerWorld();
                player.stopRiding();player.changeGameMode(GameMode.CREATIVE);world.setTimeOfDay(6000);world.setWeather(0,0,false,false);
                player.getInventory().clear();
                check(player.getInventory().size()==43,"inventory 43");check(player.playerScreenHandler.slots.size()==48,"screen 48");
                player.setStackInHand(Hand.MAIN_HAND,new ItemStack(ModItems.PILOT_HEADSET));ModItems.PILOT_HEADSET.use(world,player,Hand.MAIN_HAND);
                player.setStackInHand(Hand.MAIN_HAND,new ItemStack(ModItems.PILOT_GOGGLES));ModItems.PILOT_GOGGLES.use(world,player,Hand.MAIN_HAND);
                check(player.getInventory().getStack(41).isOf(ModItems.PILOT_HEADSET),"right click headset");
                check(PilotEquipment.hasGoggles(player),"right click goggles");
                check(!player.playerScreenHandler.getSlot(46).canInsert(new ItemStack(Items.DIRT)),"reject dirt");
                check(!player.playerScreenHandler.getSlot(46).canInsert(new ItemStack(ModItems.PILOT_GOGGLES)),"reject wrong gear");
                var stored=player.getInventory().writeNbt(new NbtList());var restored=new PlayerInventory(player);restored.readNbt(stored);
                check(restored.getStack(41).isOf(ModItems.PILOT_HEADSET)&&restored.getStack(42).isOf(ModItems.PILOT_GOGGLES),"NBT reload");
                var copied=new PlayerInventory(player);copied.clone(restored);check(copied.getStack(42).isOf(ModItems.PILOT_GOGGLES),"keep inventory clone");
                copied.dropAll();check(copied.getStack(41).isEmpty()&&copied.getStack(42).isEmpty(),"death drop clears extras");
                player.playerScreenHandler.quickMove(player,46);check(player.getInventory().getStack(41).isEmpty(),"shift remove");
                int source=-1;for(int i=9;i<45;i++)if(player.playerScreenHandler.getSlot(i).getStack().isOf(ModItems.PILOT_HEADSET))source=i;
                check(source>=9,"shift destination");player.playerScreenHandler.quickMove(player,source);check(player.getInventory().getStack(41).isOf(ModItems.PILOT_HEADSET),"shift equip");
                player.playerScreenHandler.onSlotClick(47,0,SlotActionType.PICKUP,player);check(player.getInventory().getStack(42).isEmpty(),"click remove");
                player.playerScreenHandler.onSlotClick(47,0,SlotActionType.PICKUP,player);check(PilotEquipment.hasGoggles(player),"click equip");
                for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)world.setBlockState(new BlockPos(x,150,z),net.minecraft.block.Blocks.STONE.getDefaultState());
                player.teleport(world,0,151,-4,0,0);player.changeGameMode(GameMode.SURVIVAL);player.setHealth(20);
                var heli=new HelicopterEntity(No_24_heli.HELI,world);heli.refreshPositionAndAngles(0,151,0,0,0);heli.flight.fuel=100;world.spawnEntity(heli);heliId=heli.getId();
                player.playerScreenHandler.syncState();serverReady=true;
                No_24_heli.LOGGER.info("PILOT_VERIFY_SERVER_PASS equip/slot/shift/click/NBT/clone/drop");
            }catch(Throwable error){failure=error;}
        });return;}
        if(!serverReady)return;
        ticks++;
        if(ticks==30){check(client.player.getInventory().getStack(41).isOf(ModItems.PILOT_HEADSET),"client S2C headset");check(PilotEquipment.hasGoggles(client.player),"client S2C goggles");client.setScreen(new InventoryScreen(client.player));}
        if(ticks==65)shot(client,"01-survival-slots");
        if(ticks==70){client.setScreen(null);client.getServer().execute(()->client.getServer().getPlayerManager().getPlayer(client.player.getUuid()).changeGameMode(GameMode.CREATIVE));}
        if(ticks==100){
            var screen=new CreativeInventoryScreen(client.player,client.getNetworkHandler().getEnabledFeatures(),false);client.setScreen(screen);
            var method=CreativeInventoryScreen.class.getDeclaredMethod("setSelectedTab",ItemGroup.class);method.setAccessible(true);
            method.invoke(screen,ItemGroups.getGroups().stream().filter(g->g.getType()==ItemGroup.Type.INVENTORY).findFirst().orElseThrow());
            check(screen.getScreenHandler().slots.size()==49,"creative wrapped slots");
        }
        if(ticks==130)shot(client,"02-creative-slots");
        if(ticks==135){client.setScreen(null);client.interactionManager.clickCreativeStack(ItemStack.EMPTY,46);}
        if(ticks==150){client.getServer().execute(()->{try{check(client.getServer().getPlayerManager().getPlayer(client.player.getUuid()).getInventory().getStack(41).isEmpty(),"creative remove packet");}catch(Throwable e){failure=e;}});client.interactionManager.clickCreativeStack(new ItemStack(ModItems.PILOT_HEADSET),46);}
        if(ticks==165){client.getServer().execute(()->{try{var p=client.getServer().getPlayerManager().getPlayer(client.player.getUuid());check(p.getInventory().getStack(41).isOf(ModItems.PILOT_HEADSET),"creative insert packet");check(p.startRiding(p.getServerWorld().getEntityById(heliId),true),"server mount");}catch(Throwable e){failure=e;}});}
        if(ticks==195){
            var heli=(HelicopterEntity)client.world.getEntityById(heliId);
            check(client.player.getVehicle()==heli,"client mount before copilot");
            copilot=new OtherClientPlayerEntity(client.world,new GameProfile(UUID.randomUUID(),"Copilot"));copilot.setId(900000);copilot.refreshPositionAndAngles(heli.getX(),heli.getY(),heli.getZ(),0,0);client.world.addEntity(copilot);copilot.startRiding(heli,true);
            copilot.getInventory().setStack(41,new ItemStack(ModItems.PILOT_HEADSET));copilot.getInventory().setStack(42,new ItemStack(ModItems.PILOT_GOGGLES));
            client.options.setPerspective(Perspective.FIRST_PERSON);client.player.setPitch(25);
        }
        if(ticks==225){check(client.player.getVehicle()!=null,"client mount after copilot");shot(client,"03-cockpit-neutral");}
        if(ticks==230){client.options.forwardKey.setPressed(true);client.options.rightKey.setPressed(true);}
        if(ticks==255){var heli=(HelicopterEntity)client.world.getEntityById(heliId);var rotation=CockpitControlsRenderer.rotation(heli,1);var a=CockpitControlsRenderer.grip(.38,rotation);var b=CockpitControlsRenderer.grip(-.38,rotation);check(Math.abs(a.x-b.x-.76)<1e-5&&Math.abs(a.y-b.y)<1e-5&&Math.abs(a.z-b.z)<1e-5,"linked grips");shot(client,"04-cockpit-deflected");}
        if(ticks==260){client.options.forwardKey.setPressed(false);client.options.rightKey.setPressed(false);client.player.setYaw(-30);client.player.setPitch(28);}
        if(ticks==285)shot(client,"05-copilot-hand");
        if(ticks==290){client.getServer().execute(()->client.getServer().getOverworld().setTimeOfDay(18000));client.player.setYaw(0);client.player.setPitch(8);}
        if(ticks==330){check(PilotEquipmentClient.nightStrength(client.player)==1,"night goggles time="+client.world.getTimeOfDay()+" has="+PilotEquipment.hasGoggles(client.player));check(net.minecraft.client.render.GameRenderer.getNightVisionStrength(client.player,0)==1,"night vision renderer");check(!client.player.hasStatusEffect(StatusEffects.NIGHT_VISION),"no potion mutation");shot(client,"06-night-goggles");}
        if(ticks==335){client.getServer().execute(()->client.getServer().getPlayerManager().getPlayer(client.player.getUuid()).getInventory().setStack(42,ItemStack.EMPTY));}
        if(ticks==370){check(PilotEquipmentClient.nightStrength(client.player)==0,"remove goggles clears vision");shot(client,"07-night-without-goggles");client.getServer().execute(()->{var p=client.getServer().getPlayerManager().getPlayer(client.player.getUuid());p.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION,600));p.getInventory().setStack(42,new ItemStack(ModItems.PILOT_GOGGLES));});}
        if(ticks==390){client.getServer().execute(()->client.getServer().getPlayerManager().getPlayer(client.player.getUuid()).getInventory().setStack(42,ItemStack.EMPTY));}
        if(ticks==420){check(client.player.hasStatusEffect(StatusEffects.NIGHT_VISION),"potion preserved on removal");check(net.minecraft.client.render.GameRenderer.getNightVisionStrength(client.player,0)>0,"potion rendering preserved");No_24_heli.LOGGER.info("PILOT_VERIFY_ALL_PASS");client.scheduleStop();}
    }
}
