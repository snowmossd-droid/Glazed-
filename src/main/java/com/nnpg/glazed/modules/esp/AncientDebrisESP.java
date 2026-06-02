package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AncientDebrisESP extends Module {

    private final SettingGroup sgGeneral   = settings.createGroup("General");
    private final SettingGroup sgESP       = settings.createGroup("ESP Box");
    private final SettingGroup sgTracer    = settings.createGroup("Tracer");
    private final SettingGroup sgFilter    = settings.createGroup("Filter");
    private final SettingGroup sgNotify    = settings.createGroup("Notifications");
    private final SettingGroup sgThreading = settings.createGroup("Threading");

    // ── ESP Box ──────────────────────────────────────────────────────────────

    private final Setting<ShapeMode> shapeMode = sgESP.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("Sides = full block fill only. Lines = outline only. Both = fill + outline.")
        .defaultValue(ShapeMode.Both)
        .build());

    private final Setting<SettingColor> fillColor = sgESP.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Color of the filled face (inside of the box). Alpha 0 = transparent.")
        .defaultValue(new SettingColor(255, 60, 0, 80))
        .visible(() -> shapeMode.get() != ShapeMode.Lines)
        .build());

    private final Setting<SettingColor> outlineColor = sgESP.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Color of the box outline/edges.")
        .defaultValue(new SettingColor(255, 120, 0, 255))
        .visible(() -> shapeMode.get() != ShapeMode.Sides)
        .build());

    // ── Tracer ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> tracers = sgTracer.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line pointing toward each Ancient Debris block.")
        .defaultValue(true)
        .build());

    private final Setting<SettingColor> tracerColor = sgTracer.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of tracer lines.")
        .defaultValue(new SettingColor(255, 160, 0, 200))
        .visible(tracers::get)
        .build());

    private final Setting<Boolean> tracerClosestOnly = sgTracer.add(new BoolSetting.Builder()
        .name("closest-only")
        .description("Draw tracer only to the single closest block instead of all blocks.")
        .defaultValue(false)
        .visible(tracers::get)
        .build());

    private final Setting<Integer> tracerMaxDist = sgTracer.add(new IntSetting.Builder()
        .name("tracer-max-dist")
        .description("Max distance to draw tracers. 0 = unlimited.")
        .defaultValue(96)
        .min(0).max(512)
        .sliderRange(0, 256)
        .visible(tracers::get)
        .build());

    // ── Filter ───────────────────────────────────────────────────────────────

    private final Setting<Integer> minY = sgFilter.add(new IntSetting.Builder()
        .name("min-y")
        .description("Minimum Y level to scan. Ancient Debris spawns Y8-Y119, peak Y15-Y21.")
        .defaultValue(8)
        .min(-64).max(320)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgFilter.add(new IntSetting.Builder()
        .name("max-y")
        .description("Maximum Y level to scan.")
        .defaultValue(119)
        .min(-64).max(320)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Integer> maxRenderDist = sgFilter.add(new IntSetting.Builder()
        .name("max-render-dist")
        .description("Max distance to render ESP box. 0 = unlimited.")
        .defaultValue(128)
        .min(0).max(512)
        .sliderRange(0, 256)
        .build());

    private final Setting<Integer> maxBlocks = sgFilter.add(new IntSetting.Builder()
        .name("max-blocks")
        .description("Max blocks to render at once.")
        .defaultValue(500)
        .min(50).max(2000)
        .sliderRange(50, 1000)
        .build());

    // ── Notifications ─────────────────────────────────────────────────────────

    private final Setting<Boolean> chatNotify = sgNotify.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Send chat message when new Ancient Debris is found.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> toastNotify = sgNotify.add(new BoolSetting.Builder()
        .name("toast-notify")
        .description("Show toast when Ancient Debris is found.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> limitSpam = sgNotify.add(new BoolSetting.Builder()
        .name("limit-spam")
        .description("Notify per-chunk instead of per-block.")
        .defaultValue(true)
        .build());

    // ── Threading ─────────────────────────────────────────────────────────────

    private final Setting<Boolean> useThreading = sgThreading.add(new BoolSetting.Builder()
        .name("threading")
        .description("Use background threads for chunk scanning.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> threadCount = sgThreading.add(new IntSetting.Builder()
        .name("thread-count")
        .description("Number of worker threads.")
        .defaultValue(2)
        .min(1).max(8)
        .sliderRange(1, 4)
        .visible(useThreading::get)
        .build());

    // ── State ─────────────────────────────────────────────────────────────────

    private static final long RESCAN_INTERVAL_MS = 3000L;

    private final Set<BlockPos>                     debrisPositions = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos>                     scannedChunks   = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ChunkPos, Long> lastScanTime    = new ConcurrentHashMap<>();

    private ExecutorService threadPool;
    private ChunkPos        lastPlayerChunk = null;

    public AncientDebrisESP() {
        super(GlazedAddon.esp, "ancient-debris-esp", "ESP for Ancient Debris blocks in the Nether.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        debrisPositions.clear();
        scannedChunks.clear();
        lastScanTime.clear();
        lastPlayerChunk = null;

        if (useThreading.get()) {
            threadPool = Executors.newFixedThreadPool(threadCount.get(), r -> {
                Thread t = new Thread(r, "AncientDebrisESP-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            });
        }

        if (mc.world == null) return;
        for (Chunk chunk : Utils.chunks()) {
            if (chunk instanceof WorldChunk wc) submitScan(wc, false);
        }
    }

    @Override
    public void onDeactivate() {
        if (threadPool != null) { threadPool.shutdownNow(); threadPool = null; }
        debrisPositions.clear();
        scannedChunks.clear();
        lastScanTime.clear();
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        int px = (int) Math.floor(mc.player.getX() / 16.0);
        int pz = (int) Math.floor(mc.player.getZ() / 16.0);
        ChunkPos cur = new ChunkPos(px, pz);

        if (!cur.equals(lastPlayerChunk)) {
            lastPlayerChunk = cur;
            int viewDist = mc.options.getViewDistance().getValue();

            debrisPositions.removeIf(pos -> {
                ChunkPos cp = new ChunkPos(pos);
                return Math.abs(cp.x - cur.x) > viewDist + 2 || Math.abs(cp.z - cur.z) > viewDist + 2;
            });
            scannedChunks.removeIf(cp ->
                Math.abs(cp.x - cur.x) > viewDist + 2 || Math.abs(cp.z - cur.z) > viewDist + 2);

            long now = System.currentTimeMillis();
            int radius = Math.min(viewDist, 8);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkPos cp = new ChunkPos(cur.x + dx, cur.z + dz);
                    if (!mc.world.isChunkLoaded(cp.x, cp.z)) continue;
                    Long last = lastScanTime.get(cp);
                    if (last != null && now - last < RESCAN_INTERVAL_MS) continue;
                    WorldChunk wc = mc.world.getChunk(cp.x, cp.z);
                    if (wc != null) submitScan(wc, true);
                }
            }
        }
    }

    @EventHandler
    private void onChunkLoad(ChunkDataEvent event) {
        if (event.chunk() instanceof WorldChunk wc) {
            scannedChunks.remove(wc.getPos());
            submitScan(wc, false);
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos     = event.pos;
        BlockState state = event.newState;
        Runnable task = () -> {
            if (isDebris(state, pos.getY())) {
                if (debrisPositions.add(pos)) notifyFound(null, pos.toShortString());
            } else {
                debrisPositions.remove(pos);
            }
        };
        if (useThreading.get() && threadPool != null) threadPool.submit(task);
        else task.run();
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    private void submitScan(WorldChunk chunk, boolean rescan) {
        ChunkPos cp = chunk.getPos();
        if (!rescan && scannedChunks.contains(cp)) return;
        Runnable task = () -> scanChunk(chunk);
        if (useThreading.get() && threadPool != null) threadPool.submit(task);
        else task.run();
    }

    private void scanChunk(WorldChunk chunk) {
        if (mc.world == null) return;
        ChunkPos cp  = chunk.getPos();
        int xStart   = cp.getStartX();
        int zStart   = cp.getStartZ();
        int yMin     = Math.max(chunk.getBottomY(), minY.get());
        int yMax     = Math.min(chunk.getBottomY() + chunk.getHeight(), maxY.get());

        Set<BlockPos> found = new HashSet<>();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section == null || section.isEmpty()) continue;

            int sectionBaseY = chunk.getBottomY() + si * 16;
            int localYMin    = Math.max(0,  yMin - sectionBaseY);
            int localYMax    = Math.min(15, yMax - sectionBaseY);
            if (localYMin > 15 || localYMax < 0) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int ly = localYMin; ly <= localYMax; ly++) {
                        if (section.getBlockState(lx, ly, lz).getBlock() != Blocks.ANCIENT_DEBRIS) continue;
                        found.add(new BlockPos(xStart + lx, sectionBaseY + ly, zStart + lz));
                    }
                }
            }
        }

        debrisPositions.removeIf(pos -> new ChunkPos(pos).equals(cp) && !found.contains(pos));

        int newCount = 0;
        for (BlockPos pos : found) {
            if (debrisPositions.add(pos)) newCount++;
        }

        scannedChunks.add(cp);
        lastScanTime.put(cp, System.currentTimeMillis());

        if (newCount > 0) notifyFound("Chunk " + cp.x + "," + cp.z + " (" + newCount + " blocks)", null);
    }

    private boolean isDebris(BlockState state, int y) {
        return state.getBlock() == Blocks.ANCIENT_DEBRIS && y >= minY.get() && y <= maxY.get();
    }

    // ── Notify ────────────────────────────────────────────────────────────────

    private void notifyFound(String chunkInfo, String blockInfo) {
        if (mc.player == null) return;
        if (limitSpam.get() && chunkInfo == null) return;

        String msg = chunkInfo != null
            ? "§6[AncientDebrisESP] §f" + chunkInfo
            : "§6[AncientDebrisESP] §fFound at " + blockInfo;

        mc.execute(() -> {
            if (chatNotify.get()) mc.player.sendMessage(Text.literal(msg), false);
            if (toastNotify.get()) mc.getToastManager().add(
                new MeteorToast(Items.ANCIENT_DEBRIS, "Ancient Debris", msg));
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || debrisPositions.isEmpty()) return;

        Color fill    = new Color(fillColor.get());
        Color outline = new Color(outlineColor.get());
        Color tracer  = new Color(tracerColor.get());

        Vec3d playerPos  = mc.player.getLerpedPos(event.tickDelta);
        Vec3d eyePos     = playerPos.add(0, mc.player.getEyeHeight(mc.player.getPose()), 0);
        Vec3d look       = mc.player.getRotationVector();
        Vec3d traceStart = mc.options.getPerspective().isFirstPerson()
            ? eyePos.add(look.multiply(0.5))
            : eyePos;

        int espMaxDist    = maxRenderDist.get();
        int tracerMaxDist = tracerMaxDist();
        int rendered      = 0;

        BlockPos closestPos  = null;
        double   closestDist = Double.MAX_VALUE;

        for (BlockPos pos : debrisPositions) {
            if (rendered >= maxBlocks.get()) break;

            Vec3d center = Vec3d.ofCenter(pos);
            double dist  = playerPos.distanceTo(center);

            if (espMaxDist > 0 && dist > espMaxDist) continue;

            event.renderer.box(pos, fill, outline, shapeMode.get(), 0);
            rendered++;

            if (tracers.get() && (tracerMaxDist == 0 || dist <= tracerMaxDist)) {
                if (tracerClosestOnly.get()) {
                    if (dist < closestDist) { closestDist = dist; closestPos = pos; }
                } else {
                    event.renderer.line(traceStart.x, traceStart.y, traceStart.z,
                                        center.x, center.y, center.z, tracer);
                }
            }
        }

        if (tracers.get() && tracerClosestOnly.get() && closestPos != null) {
            Vec3d center = Vec3d.ofCenter(closestPos);
            event.renderer.line(traceStart.x, traceStart.y, traceStart.z,
                                center.x, center.y, center.z, tracer);
        }
    }

    private int tracerMaxDist() {
        return tracerMaxDist.get();
    }

    @Override
    public String getInfoString() {
        return String.valueOf(debrisPositions.size());
    }
}
