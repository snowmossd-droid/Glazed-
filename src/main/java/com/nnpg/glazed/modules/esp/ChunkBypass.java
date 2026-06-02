package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.passive.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class ChunkBypass extends Module {

    private final SettingGroup sgGeneral      = settings.createGroup("General");
    private final SettingGroup sgDetection    = settings.createGroup("Detection");
    private final SettingGroup sgRender       = settings.createGroup("Render");
    private final SettingGroup sgNotification = settings.createGroup("Notifications");

    private final Setting<Boolean> showReasons = sgGeneral.add(new BoolSetting.Builder()
        .name("show-reasons")
        .description("Show detection reasons on ESP label.")
        .defaultValue(true)
        .build());

    private final Setting<Double> rotatedThreshold = sgDetection.add(new DoubleSetting.Builder()
        .name("rotated-ds-limit")
        .description("Minimum rotated deepslate blocks to flag a chunk.")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build());

    private final Setting<Boolean> velocityDetection = sgDetection.add(new BoolSetting.Builder()
        .name("velocity-detection")
        .description("Flag chunks via EntityVelocityUpdate packets above Y16.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> suspiciousEntities = sgDetection.add(new BoolSetting.Builder()
        .name("suspicious-entities")
        .description("Flag chunks containing suspicious entities (villager, armor stand, etc).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> onlyWithoutHoles = sgDetection.add(new BoolSetting.Builder()
        .name("only-without-holes")
        .description("Only flag chunks that have no holes near ocean-level.")
        .defaultValue(false)
        .build());

    private final Setting<Double> holeCheckRadius = sgDetection.add(new DoubleSetting.Builder()
        .name("hole-check-radius")
        .description("Radius (blocks) used for hole checking around chunk center.")
        .defaultValue(8)
        .range(0, 64)
        .sliderRange(0, 64)
        .visible(onlyWithoutHoles::get)
        .build());

    private final Setting<ShapeMode> renderMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("render-mode")
        .description("How to render flagged chunks.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> chunkColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-color")
        .description("Color for flagged chunk highlights.")
        .defaultValue(new SettingColor(60, 200, 80, 120))
        .build());

    private final Setting<Double> renderY = sgRender.add(new DoubleSetting.Builder()
        .name("render-y")
        .description("Y level to render the flat chunk box.")
        .defaultValue(63)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build());

    private static final int  MIN_VINE_LENGTH      = 20;
    private static final int  MIN_KELP_LENGTH      = 20;
    private static final int  MIN_Y_LEVEL          = 16;
    private static final int  ENTITY_MAX_DISTANCE  = 256;
    private static final int  THREAD_COUNT         = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final int  MAX_CONCURRENT_SCANS = 50;
    private static final long RESCAN_INTERVAL_MS   = 5000L;
    private static final long QUEUE_REBUILD_MS     = 2000L;

    private final Set<ChunkPos>                              flaggedChunks        = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos>                              notifiedChunks       = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos>                              externalChunks       = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, ChunkAnalysis> chunkData            = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Long>          scannedChunks        = new ConcurrentHashMap<>();
    private final Queue<ChunkPos>                            scanQueue            = new ConcurrentLinkedQueue<>();
    private final AtomicLong                                 activeScans          = new AtomicLong(0L);
    private final ConcurrentMap<Long, ScoredChunk>           foundBases           = new ConcurrentHashMap<>();
    private final Set<Integer>                               detectedEntityIds    = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<ChunkNotification>   pendingNotifications = new ConcurrentLinkedQueue<>();

    private ChunkPos         lastPlayerChunk  = null;
    private ExecutorService  pool             = null;
    private volatile boolean scanning         = false;
    private long             lastQueueRebuild = 0L;

    public ChunkBypass() {
        super(GlazedAddon.esp, "chunk-bypass", "Advanced base detector via block patterns and EntityVelocity packets.");
    }

    @Override
    public void onActivate() {
        pool = Executors.newFixedThreadPool(THREAD_COUNT);
        scanning = true;
        resetAll();
    }

    @Override
    public void onDeactivate() {
        scanning = false;
        if (pool != null) { pool.shutdownNow(); pool = null; }
        resetAll();
    }

    private void resetAll() {
        flaggedChunks.clear(); notifiedChunks.clear(); externalChunks.clear();
        chunkData.clear(); scannedChunks.clear(); scanQueue.clear();
        foundBases.clear(); detectedEntityIds.clear(); pendingNotifications.clear();
        lastPlayerChunk = null; lastQueueRebuild = 0L;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null) {
            scanning = false;
            if (pool != null) { pool.shutdownNow(); pool = null; }
            resetAll();
            return;
        }
        if (mc.player != null) {
            if (suspiciousEntities.get()) checkEntities();
            ChunkNotification n;
            while ((n = pendingNotifications.poll()) != null) {
                try { mc.player.sendMessage(Text.literal("§6[Chunk Bypass] §f" + n.reason + " §7(X:" + n.x + " Z:" + n.z + ")"), false); } catch (Exception ignored) {}
                try { mc.getToastManager().add(new MeteorToast(Items.COMPASS, "Chunk Bypass", "X:" + n.x + " Z:" + n.z)); } catch (Exception ignored) {}
            }
        }
    }

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDataS2CPacket p) {
            ChunkPos pos = new ChunkPos(p.getChunkX(), p.getChunkZ());
            scannedChunks.remove(pos);
            notifiedChunks.remove(pos);
            scanQueue.offer(pos);
        }

        if (velocityDetection.get() && event.packet instanceof EntityVelocityUpdateS2CPacket vp) {
            if (mc.world == null || mc.player == null) return;
            Entity entity = mc.world.getEntityById(vp.getEntityId());
            if (entity == null || entity == mc.player) return;
            if (vp.getVelocityX() == 0 && vp.getVelocityY() == 0 && vp.getVelocityZ() == 0) return;
            BlockPos ePos = entity.getBlockPos();
            if (ePos.getY() <= MIN_Y_LEVEL) return;
            flagExternal(new ChunkPos(ePos), ePos, "Velocity");
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null || !scanning) return;

        int px = (int) Math.floor(mc.player.getX() / 16.0);
        int pz = (int) Math.floor(mc.player.getZ() / 16.0);
        ChunkPos cur = new ChunkPos(px, pz);
        long now = System.currentTimeMillis();

        if (lastPlayerChunk == null) {
            lastPlayerChunk = cur; buildBFSScanQueue(cur); lastQueueRebuild = now;
        } else if (!cur.equals(lastPlayerChunk)) {
            lastPlayerChunk = cur; cleanupDistantChunks(cur); scanQueue.clear(); buildBFSScanQueue(cur); lastQueueRebuild = now;
        } else if (now - lastQueueRebuild >= QUEUE_REBUILD_MS) {
            scanQueue.clear(); buildBFSScanQueue(cur); lastQueueRebuild = now;
        }

        tryStartScans();

        if (!foundBases.isEmpty()) {
            Color fill    = new Color(chunkColor.get());
            Color outline = new Color(chunkColor.get().r, chunkColor.get().g, chunkColor.get().b, Math.min(255, chunkColor.get().a + 60));
            float y = renderY.get().floatValue();

            for (ScoredChunk sc : foundBases.values()) {
                float x0 = sc.cx * 16f, z0 = sc.cz * 16f;
                Box box = new Box(x0, y - 0.1, z0, x0 + 16, y + 0.1, z0 + 16);
                event.renderer.box(box, fill, outline, renderMode.get(), 0);
            }
        }
    }

    private void cleanupDistantChunks(ChunkPos center) {
        int r = mc.options.getViewDistance().getValue() + 2;
        scannedChunks.keySet().removeIf(c -> Math.abs(c.x - center.x) > r || Math.abs(c.z - center.z) > r);
    }

    private void buildBFSScanQueue(ChunkPos center) {
        int radius = mc.options.getViewDistance().getValue();
        HashSet<ChunkPos> visited = new HashSet<>();
        LinkedList<ChunkPos> bfs = new LinkedList<>();
        bfs.offer(center); visited.add(center);
        int[][] off = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
        long now = System.currentTimeMillis();
        while (!bfs.isEmpty()) {
            ChunkPos c = bfs.poll();
            Long t = scannedChunks.get(c);
            if (!flaggedChunks.contains(c) && (t == null || now - t >= RESCAN_INTERVAL_MS)) scanQueue.offer(c);
            for (int[] o : off) {
                ChunkPos nb = new ChunkPos(c.x + o[0], c.z + o[1]);
                if (Math.abs(nb.x - center.x) > radius || Math.abs(nb.z - center.z) > radius || visited.contains(nb)) continue;
                visited.add(nb); bfs.offer(nb);
            }
        }
    }

    private void tryStartScans() {
        if (!scanning || mc.world == null || mc.player == null || pool == null) return;
        long now = System.currentTimeMillis();
        while (activeScans.get() < MAX_CONCURRENT_SCANS && !scanQueue.isEmpty()) {
            ChunkPos pos = scanQueue.poll(); if (pos == null) continue;
            Long last = scannedChunks.get(pos);
            if (last != null && now - last < RESCAN_INTERVAL_MS) continue;
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) continue;
            scannedChunks.put(pos, now); notifiedChunks.remove(pos); activeScans.incrementAndGet();
            pool.submit(() -> { try { analyzeChunk(pos); } finally { activeScans.decrementAndGet(); } });
        }
    }

    private void analyzeChunk(ChunkPos pos) {
        if (mc.world == null || !scanning) return;
        int startX = pos.getStartX(), startZ = pos.getStartZ();
        ChunkAnalysis a = new ChunkAnalysis();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = MIN_Y_LEVEL + 1; y < 319; y++) {
                    if (!scanning) return;
                    BlockPos bp = new BlockPos(startX + x, y, startZ + z);
                    if (isRotatedDeepslate(mc.world.getBlockState(bp))) {
                        a.rotatedCount++;
                        if (a.susBlockPos == null) a.susBlockPos = bp;
                    }
                }
                for (int y = 318; y > MIN_Y_LEVEL; y--) {
                    if (!scanning) return;
                    BlockPos bp = new BlockPos(startX + x, y, startZ + z);
                    if (!mc.world.getBlockState(bp).isOf(Blocks.VINE)) continue;
                    if (vineExtendsToY16(bp) && getVineLength(bp) >= MIN_VINE_LENGTH) {
                        a.hasVine = true;
                        if (a.susBlockPos == null) a.susBlockPos = bp;
                    }
                    break;
                }
                for (int y = MIN_Y_LEVEL + 1; y < 64; y++) {
                    if (!scanning) return;
                    BlockPos bp = new BlockPos(startX + x, y, startZ + z);
                    if (!mc.world.getBlockState(bp).isOf(Blocks.KELP)) continue;
                    if (getKelpLength(bp) >= MIN_KELP_LENGTH) {
                        a.hasKelp = true;
                        if (a.susBlockPos == null) a.susBlockPos = bp;
                    }
                    break;
                }
            }
        }

        chunkData.put(pos, a);
        evaluateChunk(pos, a);
    }

    private void checkEntities() {
        if (mc.world == null || mc.player == null) return;
        for (Entity entity : mc.world.getEntities()) {
            if (entity.getBlockPos().getY() <= MIN_Y_LEVEL) continue;
            if (entity == mc.player) continue;
            boolean suspicious = entity instanceof VillagerEntity
                || entity instanceof PlayerEntity
                || entity instanceof ItemFrameEntity
                || entity instanceof ArmorStandEntity
                || entity instanceof IronGolemEntity
                || entity instanceof FoxEntity
                || entity instanceof CatEntity;
            if (!suspicious) continue;
            if (mc.player.distanceTo(entity) > ENTITY_MAX_DISTANCE) continue;
            if (!detectedEntityIds.add(entity.getId())) continue;
            flagExternal(new ChunkPos(entity.getBlockPos()), entity.getBlockPos(), entityLabel(entity));
        }
    }

    private void flagExternal(ChunkPos cp, BlockPos ePos, String reason) {
        if (!flaggedChunks.add(cp)) return;
        externalChunks.add(cp);
        foundBases.put(ChunkPos.toLong(cp.x, cp.z), new ScoredChunk(cp.x, cp.z));
        ChunkAnalysis a = chunkData.computeIfAbsent(cp, k -> new ChunkAnalysis());
        if (a.susBlockPos == null) a.susBlockPos = ePos;
        if (reason.equals("Velocity")) a.hasVelocity = true;
        else a.hasEntity = true;
        pendingNotifications.add(new ChunkNotification(reason, ePos.getX(), ePos.getZ()));
    }

    private String entityLabel(Entity e) {
        if (e instanceof ArmorStandEntity) return "ArmorStand";
        if (e instanceof ItemFrameEntity)  return "ItemFrame";
        if (e instanceof VillagerEntity)   return "Villager";
        if (e instanceof PlayerEntity)     return "Player";
        return "Entity";
    }

    private boolean isRotatedDeepslate(BlockState state) {
        if (state.getBlock() != Blocks.DEEPSLATE) return false;
        if (!state.contains(Properties.AXIS)) return false;
        return state.get(Properties.AXIS) != Direction.Axis.Y;
    }

    private boolean vineExtendsToY16(BlockPos start) {
        if (mc.world == null) return false;
        for (BlockPos p = start; p.getY() >= MIN_Y_LEVEL; p = p.down()) {
            if (!mc.world.getBlockState(p).isOf(Blocks.VINE)) return false;
            if (p.getY() == MIN_Y_LEVEL) return true;
        }
        return false;
    }

    private int getVineLength(BlockPos start) {
        if (mc.world == null) return 0;
        int l = 0;
        BlockPos p;
        for (p = start; mc.world.getBlockState(p).isOf(Blocks.VINE); p = p.up()) l++;
        for (p = start.down(); p.getY() >= MIN_Y_LEVEL && mc.world.getBlockState(p).isOf(Blocks.VINE); p = p.down()) l++;
        return l;
    }

    private int getKelpLength(BlockPos start) {
        if (mc.world == null) return 0;
        int l = 0;
        BlockPos p;
        for (p = start; isKelp(mc.world.getBlockState(p)); p = p.up()) l++;
        for (p = start.down(); p.getY() >= MIN_Y_LEVEL && isKelp(mc.world.getBlockState(p)); p = p.down()) l++;
        return l;
    }

    private boolean isKelp(BlockState state) {
        return state.isOf(Blocks.KELP) || state.isOf(Blocks.KELP_PLANT);
    }

    private boolean chunkHasHoles(ChunkPos cp) {
        if (mc.world == null) return false;
        int radius = holeCheckRadius.get().intValue();
        int cx = cp.getCenterX(), cz = cp.getCenterZ();
        int air = 0, total = 0;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int y = 60; y <= 70; y++) {
                    if (mc.world.getBlockState(new BlockPos(x, y, z)).isAir()) air++;
                    total++;
                }
            }
        }
        return total > 0 && (double) air / total > 0.1;
    }

    private void evaluateChunk(ChunkPos pos, ChunkAnalysis a) {
        boolean sus = false;
        StringBuilder reasons = new StringBuilder();

        if (a.rotatedCount >= rotatedThreshold.get().intValue()) { sus = true; reasons.append("Rotated:").append(a.rotatedCount).append(" "); }
        if (a.hasVine) { sus = true; reasons.append("Vine "); }
        if (a.hasKelp) { sus = true; reasons.append("Kelp "); }

        if (!sus) {
            if (!externalChunks.contains(pos)) {
                flaggedChunks.remove(pos);
                foundBases.remove(ChunkPos.toLong(pos.x, pos.z));
            }
            return;
        }

        if (onlyWithoutHoles.get() && chunkHasHoles(pos)) return;
        if (!flaggedChunks.add(pos) || !notifiedChunks.add(pos)) return;

        foundBases.put(ChunkPos.toLong(pos.x, pos.z), new ScoredChunk(pos.x, pos.z));

        String rs = reasons.toString().trim();
        int fx = a.susBlockPos != null ? a.susBlockPos.getX() : pos.getStartX() + 8;
        int fz = a.susBlockPos != null ? a.susBlockPos.getZ() : pos.getStartZ() + 8;
        pendingNotifications.add(new ChunkNotification(rs, fx, fz));
    }

    private static class ChunkAnalysis {
        int      rotatedCount = 0;
        boolean  hasVine      = false;
        boolean  hasKelp      = false;
        boolean  hasEntity    = false;
        boolean  hasVelocity  = false;
        BlockPos susBlockPos  = null;
    }

    private static class ScoredChunk {
        final int cx, cz;
        ScoredChunk(int x, int z) { cx = x; cz = z; }
    }

    private static class ChunkNotification {
        final String reason; final int x, z;
        ChunkNotification(String r, int x, int z) { reason = r; this.x = x; this.z = z; }
    }
                                                               }
    
