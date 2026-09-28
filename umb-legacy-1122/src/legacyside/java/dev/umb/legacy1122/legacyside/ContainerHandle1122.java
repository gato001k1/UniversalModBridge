package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Reflective 1.12.2 Container adapter; named SRG members are preferred, raw names are fallback. */
final class ContainerHandle1122 implements ContainerHandle {
    private final Object container, player; private final List<Object> machine = new ArrayList<Object>();
    private final String title;
    ContainerHandle1122(Object c, Object p, String title) throws Exception {
        container=c; player=p; this.title=title;
        Object inv = field(p.getClass(), p, "field_71071_by", "bv");
        Object all = field(c.getClass(), c, "field_75151_b", "c");
        if (all instanceof Iterable) for (Object slot : (Iterable<?>) all) {
            Object backing = field(slot.getClass(), slot, "field_75224_c", "b");
            if (backing != inv) machine.add(slot);
        }
    }
    public String title(){return title;}
    public int slotCount(){return machine.size();}
    public SlotData[] slots(){ SlotData[] out=new SlotData[machine.size()]; for(int i=0;i<out.length;i++){Object s=machine.get(i); out[i]=new SlotData(i, integer(s,"field_75223_e","e"), integer(s,"field_75221_f","f"), toStack(invoke(s,"getStack","func_75211_c")));} return out; }
    public void setSlot(int i, StackData s){if(i>=0&&i<machine.size()){Object slot=machine.get(i); invoke(slot,"putStack","func_75215_d",fromStack(slot,s)); invoke(slot,"onSlotChanged","func_75218_e");}}
    public StackData takeSlot(int i,int amount){if(i<0||i>=machine.size())return null; Object x=invoke(machine.get(i),"decrStackSize","func_75209_a",Integer.valueOf(amount)); invoke(machine.get(i),"onSlotChanged","func_75218_e"); return toStack(x);}
    public boolean canPlace(int i,StackData s){return i>=0&&i<machine.size()&&Boolean.TRUE.equals(invoke(machine.get(i),"isItemValid","func_75214_a",fromStack(machine.get(i),s)));}
    public StackData quickMove(int i,int ignored){Object x=invoke(container,"transferStackInSlot","func_82846_b",player,Integer.valueOf(i));return toStack(x);}
    public int[] syncData(){return new int[0];}
    public void close(){invoke(container,"onContainerClosed","func_75134_a",player);}
    private static Object fromStack(Object slot,StackData s){
        if (s == null || s.isEmpty()) return emptyStack(slot);
        try {
            ClassLoader l = slot.getClass().getClassLoader();
            Class<?> rl = Class.forName("net.minecraft.util.ResourceLocation", true, l);
            Object key = rl.getConstructor(String.class).newInstance(s.legacyId);
            Class<?> regs = Class.forName("net.minecraftforge.fml.common.registry.ForgeRegistries", true, l);
            Object items = regs.getField("ITEMS").get(null);
            Object item = invoke(items,"getValue","getValue",key);
            if (item == null) item = invoke(items,"getObject","getObject",key);
            if (item == null) return emptyStack(slot);
            Class<?> stack = Class.forName("net.minecraft.item.ItemStack", true, l);
            Class<?> itemType = Class.forName("net.minecraft.item.Item", true, l);
            for (java.lang.reflect.Constructor<?> c : stack.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 3 && p[0].isAssignableFrom(itemType)
                        && p[1] == Integer.TYPE && p[2] == Integer.TYPE)
                    return c.newInstance(item, Integer.valueOf(s.count), Integer.valueOf(s.damage));
            }
            throw new NoSuchMethodException("ItemStack(Item,int,int)");
        } catch (Throwable t) {
            Throwable root=t; while(root.getCause()!=null && root.getCause()!=root) root=root.getCause();
            System.err.println("[UMB-BRIDGE-1122] stack conversion failed id="+s.legacyId+" root="+root);
            return emptyStack(slot);
        }
    }
    private static Object emptyStack(Object slot){
        try { Class<?> c=Class.forName("net.minecraft.item.ItemStack",true,slot.getClass().getClassLoader());
            Field f=c.getField("EMPTY"); return f.get(null); } catch(Throwable t){ return null; }
    }
    private static StackData toStack(Object x){if(x==null)return StackData.EMPTY; try{if(Boolean.TRUE.equals(invoke(x,"isEmpty","func_190926_b")))return StackData.EMPTY; Object item=invoke(x,"getItem","func_77973_b"); Object key=invoke(item,"getRegistryName","getRegistryName"); int n=integer(x,"getCount","field_77994_a"); int d=integer(x,"getMetadata","func_77960_j"); return new StackData(String.valueOf(key),n,d,null);}catch(Throwable t){return StackData.EMPTY;}}
    private static int integer(Object o,String n,String r){
        Object x=invoke(o,n,r);
        if(x instanceof Number)return ((Number)x).intValue();
        x=readField(o,n,r);
        return x instanceof Number?((Number)x).intValue():0;
    }
    private static Object invoke(Object o,String n,String r,Object...a){if(o==null)return null; for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if((m.getName().equals(n)||m.getName().equals(r))&&m.getParameterTypes().length==a.length)try{m.setAccessible(true);return m.invoke(o,a);}catch(Throwable ignored){} return null;}
    private static Object readField(Object o,String n,String r){
        if(o==null)return null;
        for(Class<?> c=o.getClass();c!=null;c=c.getSuperclass()) for(String x:new String[]{n,r}) try{
            Field f=c.getDeclaredField(x); f.setAccessible(true); return f.get(o);
        }catch(NoSuchFieldException ignored){}catch(Throwable ignored){return null;}
        return null;
    }
    private static Object field(Class<?> c,Object o,String n,String r)throws Exception{for(;c!=null;c=c.getSuperclass())for(String x:new String[]{n,r})try{Field f=c.getDeclaredField(x);f.setAccessible(true);return f.get(o);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(n);}
}
