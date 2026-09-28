package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cpw.mods.fml.common.registry.GameData;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import net.minecraft.block.Block;
import net.minecraft.item.Item;

/** Headless placement probe: the real bridge callback, real HBM classes, recording HostWorld. */
public final class MultiblockProbe {
    private MultiblockProbe() {}

    public static String run() throws Exception {
        RecordingWorld world = new RecordingWorld();
        LegacyBridge bridge = new LegacyBridgeImpl();
        bridge.boot(world);
        List<String> wanted = new ArrayList<String>();
        for (Object raw : GameData.getBlockRegistry()) {
            Block b = (Block) raw;
            if (b == null || !isDummyable(b.getClass())) continue;
            String id = GameData.getBlockRegistry().func_148750_c(b);
            if (id != null && !wanted.contains(id)) wanted.add(id);
        }
        StringBuilder out = new StringBuilder("MULTIBLOCK-PROBE\n");
        int n = 0;
        for (String id : wanted) {
            if (n++ >= 40) break;
            world.reset();
            world.put(0, 64, 0, id, 0);
            Block block = GameData.getBlockRegistry().get(id);
            String itemId = GameData.getItemRegistry().func_148750_c(Item.func_150898_a(block));
            bridge.placedBy(id, 0, 64, 0, new ProbePlayer(),
                    new StackData(itemId, 1, 0, null));
            out.append(id).append(" class=").append(GameData.getBlockRegistry().get(id).getClass().getSimpleName())
               .append(" setBlock=").append(world.setCalls).append(" remove=").append(world.removeCalls)
               .append(" states=").append(world.states.size()).append(" calls=").append(world.calls).append('\n');
        }
        bridge.shutdown();
        return out.toString();
    }

    private static boolean isDummyable(Class<?> c) {
        while (c != null) {
            if ("com.hbm.blocks.BlockDummyable".equals(c.getName())) return true;
            c = c.getSuperclass();
        }
        return false;
    }

    static final class RecordingWorld implements HostWorld {
        final Map<String, String> states = new LinkedHashMap<String, String>();
        final List<String> calls = new ArrayList<String>();
        int setCalls, removeCalls;
        private String key(int x, int y, int z) { return x + "," + y + "," + z; }
        void reset() { states.clear(); calls.clear(); setCalls = removeCalls = 0; }
        void put(int x, int y, int z, String id, int meta) { states.put(key(x,y,z), id + "@" + meta); }
        public boolean isRemote() { return false; }
        public long getTotalTime() { return 0; }
        public String getBlockId(int x,int y,int z) { String s=states.get(key(x,y,z)); return s==null?"minecraft:air":s.substring(0,s.indexOf('@')); }
        public int getMeta(int x,int y,int z) { String s=states.get(key(x,y,z)); return s==null?0:Integer.parseInt(s.substring(s.indexOf('@')+1)); }
        public void setBlock(int x,int y,int z,String id,int meta,int flags) { setCalls++; calls.add("set " + key(x,y,z)+" "+id+"@"+meta); put(x,y,z,id,meta); }
        public void setMeta(int x,int y,int z,int meta,int flags) { String id=getBlockId(x,y,z); calls.add("meta "+key(x,y,z)+" "+meta); put(x,y,z,id,meta); }
        public void removeBlock(int x,int y,int z) { removeCalls++; calls.add("remove "+key(x,y,z)); states.remove(key(x,y,z)); }
        public void markBlockDirty(int x,int y,int z) {}
        public void scheduleTick(int x,int y,int z,int delay) {}
        public long randomSeed() { return 1; }
        public void log(String msg) { System.out.println("[multiblock] " + msg); }
    }

    static final class ProbePlayer implements HostPlayer {
        public String getName(){return "MultiblockProbe";} public boolean isSneaking(){return false;}
        public double getX(){return .5;} public double getY(){return 64;} public double getZ(){return .5;}
        public StackData getHeldItem(){return StackData.EMPTY;} public void setHeldItem(StackData s){}
        public void sendMessage(String s){System.out.println("[multiblock-player] "+s);}
        public StackData getInventorySlot(int i){return StackData.EMPTY;} public void setInventorySlot(int i,StackData s){}
        public int getInventorySize(){return 36;} public double getMotionX(){return 0;} public double getMotionY(){return 0;}
        public double getMotionZ(){return 0;} public void setMotion(double x,double y,double z){}
        public void hurt(String t,float a){}
    }
}
