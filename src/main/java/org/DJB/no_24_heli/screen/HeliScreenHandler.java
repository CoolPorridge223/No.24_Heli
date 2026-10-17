package org.DJB.no_24_heli.screen;

import org.DJB.no_24_heli.No_24_heli;
import org.DJB.no_24_heli.entity.HelicopterEntity;


import net.minecraft.entity.player.*;
import net.minecraft.inventory.*;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.*;
import net.minecraft.screen.slot.Slot;

/** 一个燃料槽与十八格货仓；属性委托由服务端同步仪表数值。 */
public class HeliScreenHandler extends ScreenHandler {
    private final Inventory inventory;
    private final HelicopterEntity helicopter;
    public final PropertyDelegate stats;
    public HeliScreenHandler(int id,PlayerInventory player){this(id,player,new SimpleInventory(19),new ArrayPropertyDelegate(8),null);}
    public HeliScreenHandler(int id,PlayerInventory player,Inventory inv,PropertyDelegate properties,HelicopterEntity heli){
        super(No_24_heli.SCREEN,id);inventory=inv;stats=properties;helicopter=heli;addProperties(properties);
        addSlot(new Slot(inv,0,8,35){@Override public boolean canInsert(ItemStack s){return s.isOf(No_24_heli.KEROSENE)||s.isOf(No_24_heli.CRUDE);}});
        for(int r=0;r<2;r++)for(int c=0;c<9;c++)addSlot(new Slot(inv,1+r*9+c,8+c*18,76+r*18));
        for(int r=0;r<3;r++)for(int c=0;c<9;c++)addSlot(new Slot(player,9+r*9+c,8+c*18,130+r*18));
        for(int c=0;c<9;c++)addSlot(new Slot(player,c,8+c*18,188));
    }
    @Override public boolean canUse(PlayerEntity player){return helicopter==null||(!helicopter.isRemoved()&&!helicopter.flight.wreck&&player.squaredDistanceTo(helicopter)<64&&(helicopter.hasPassenger(player)||helicopter.isOnGround()));}
    @Override public ItemStack quickMove(PlayerEntity player,int index){
        if(index<0||index>=slots.size())return ItemStack.EMPTY;
        Slot slot=slots.get(index);if(!slot.hasStack())return ItemStack.EMPTY;
        ItemStack stack=slot.getStack(),copy=stack.copy();
        if(index<19){if(!insertItem(stack,19,slots.size(),true))return ItemStack.EMPTY;}
        else if(stack.isOf(No_24_heli.KEROSENE)||stack.isOf(No_24_heli.CRUDE)){if(!insertItem(stack,0,1,false)&&!insertItem(stack,1,19,false))return ItemStack.EMPTY;}
        else if(!insertItem(stack,1,19,false))return ItemStack.EMPTY;
        if(stack.isEmpty())slot.setStack(ItemStack.EMPTY);else slot.markDirty();return copy;
    }
}
