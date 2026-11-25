package com.pengin0503.itemmilestonelog.smoketest;

import com.mojang.logging.LogUtils;
import com.pengin0503.itemmilestonelog.ItemMilestoneLog;
import com.pengin0503.itemmilestonelog.client.ClientMilestoneHandler;
import com.pengin0503.itemmilestonelog.client.ItemPickerScreen;
import com.pengin0503.itemmilestonelog.client.MilestoneScreen;
import com.pengin0503.itemmilestonelog.client.PresetListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Layer-2 headless client smoke hook (VERIFY BUILDS ONLY).
 *
 * <p>This source lives under {@code tools/smoketest/} and is copied into throwaway
 * harness projects by {@code tools/smoketest/inject.sh}; production jars never contain it.
 * It is completely inert unless the client JVM runs with {@code -Diml.smoketest=1}.
 *
 * <p>With the property set it drives the client on the client thread: title screen ->
 * singleplayer world (reuses {@code saves/iml-smoke}, otherwise creates it) -> the mod's
 * screens in sequence (opened through the key-binding / GUI-open request path and the
 * MilestoneScreen button actions), holds each, takes a screenshot of each with the game's
 * own {@code Screenshot.grab}, writes {@code iml-smoke/result.json} into the run dir and
 * stops the client (exit code 0 on success, 1 on failure, 2 on watchdog timeout).
 *
 * <p>Version differences (1.21 .. 26.3) are bridged with reflection so a single source
 * compiles against every supported Minecraft version.
 */
@Mod(value = ItemMilestoneLog.MOD_ID, dist = Dist.CLIENT)
public final class SmokeTestClient {
    public static final String PROPERTY = "iml.smoketest";

    public SmokeTestClient() {
        if (!"1".equals(System.getProperty(PROPERTY))) {
            return; // inert: no listener, no thread, no file
        }
        SmokeRun run = new SmokeRun();
        NeoForge.EVENT_BUS.addListener(run::onClientTick);
        run.startWatchdog();
        SmokeRun.LOGGER.info("[IML-SMOKE] smoke test hook armed (world '{}', hold {} ticks)",
                SmokeRun.WORLD, SmokeRun.HOLD_TICKS);
    }

    /** The state machine; runs entirely on the client thread except for the watchdog. */
    static final class SmokeRun {
        static final Logger LOGGER = LogUtils.getLogger();
        static final String WORLD = System.getProperty("iml.smoketest.world", "iml-smoke");
        static final int HOLD_TICKS = Integer.getInteger("iml.smoketest.holdTicks", 40);
        static final int SETTLE_TICKS = Integer.getInteger("iml.smoketest.settleTicks", 40);
        static final int STEP_TIMEOUT_TICKS = Integer.getInteger("iml.smoketest.stepTimeoutTicks", 400);
        static final int WORLD_TIMEOUT_TICKS = Integer.getInteger("iml.smoketest.worldTimeoutTicks", 20 * 240);
        static final long TIMEOUT_MS = Long.getLong("iml.smoketest.timeoutSec", 480L) * 1000L;

        private enum Phase { WAIT_TITLE, CREATE_WORLD, ENTER_WORLD, SETTLE, STEPS, FINISHED }

        private enum StepState { OPEN, HOLD, SHOT, CLOSE }

        private record Step(String name, Runnable open, Predicate<Screen> expected) {
        }

        private static final class StepResult {
            final String name;
            String status = "PENDING";
            long openMs = -1;
            String screenshot;
            String detail = "";

            StepResult(String name) {
                this.name = name;
            }
        }

        private final List<StepResult> results = new ArrayList<>();
        private final List<String> dismissed = new ArrayList<>();
        private final List<Step> steps = new ArrayList<>();
        private Phase phase = Phase.WAIT_TITLE;
        private String worldMode = "";
        private long titleAtMs = -1;
        private long worldAtMs = -1;
        private int phaseTicks;
        private int stepIndex;
        private StepState stepState;
        private long stepStartMs;
        private volatile String shotMessage;
        private MilestoneScreen milestone;
        private volatile boolean finished;

        SmokeRun() {
            steps.add(new Step("MilestoneScreen",
                    ClientMilestoneHandler::requestOpenGui,
                    s -> s instanceof MilestoneScreen));
            steps.add(new Step("ItemPickerScreen",
                    () -> setScreen(Minecraft.getInstance(), new ItemPickerScreen(milestone)),
                    s -> s instanceof ItemPickerScreen));
            steps.add(new Step("PresetListScreen",
                    () -> setScreen(Minecraft.getInstance(), new PresetListScreen(milestone)),
                    s -> s instanceof PresetListScreen));
        }

        void startWatchdog() {
            Thread t = new Thread(() -> {
                try {
                    while (!finished && uptime() < TIMEOUT_MS) {
                        Thread.sleep(1000L);
                    }
                } catch (InterruptedException ignored) {
                    return;
                }
                if (!finished) {
                    finished = true;
                    LOGGER.error("[IML-SMOKE] FAIL: watchdog timeout after {} ms in phase {}", uptime(), phase);
                    writeResult("FAIL", "watchdog timeout in phase " + phase
                            + (phase == Phase.STEPS ? " step " + stepIndex + " " + stepState : ""));
                    Runtime.getRuntime().halt(2);
                }
            }, "IML-Smoke-Watchdog");
            t.setDaemon(true);
            t.start();
        }

        void onClientTick(ClientTickEvent.Post event) {
            if (finished) {
                return;
            }
            try {
                tick(Minecraft.getInstance());
            } catch (Throwable t) {
                LOGGER.error("[IML-SMOKE] exception in phase {}", phase, t);
                fail("exception in phase " + phase + ": " + t);
            }
        }

        private void tick(Minecraft mc) throws Exception {
            Screen screen = currentScreen(mc);
            phaseTicks++;
            switch (phase) {
                case WAIT_TITLE -> {
                    if (screen != null && screen.getClass().getSimpleName().contains("LoadingErrorScreen")) {
                        // NeoForge "Warning while loading mods": its continue button does setScreen(null).
                        String title = screen.getTitle().getString();
                        LOGGER.warn("[IML-SMOKE] dismissing mod-loading warning screen: {}", title);
                        dismissed.add(screen.getClass().getName() + ": " + title);
                        setScreen(mc, null);
                        return;
                    }
                    if (screen instanceof TitleScreen title) {
                        titleAtMs = uptime();
                        LOGGER.info("[IML-SMOKE] title screen reached at {} ms", titleAtMs);
                        if (mc.getLevelSource().levelExists(WORLD)) {
                            worldMode = "reused";
                            nextPhase(Phase.ENTER_WORLD);
                            mc.createWorldOpenFlows().openWorld(WORLD, () -> fail("openWorld cancelled"));
                        } else {
                            worldMode = "created";
                            nextPhase(Phase.CREATE_WORLD);
                            openCreateWorldScreen(mc, title);
                        }
                    }
                }
                case CREATE_WORLD -> {
                    if (screen instanceof CreateWorldScreen create && phaseTicks > 5) {
                        create.getUiState().setName(WORLD);
                        Method onCreate = CreateWorldScreen.class.getDeclaredMethod("onCreate");
                        onCreate.setAccessible(true);
                        nextPhase(Phase.ENTER_WORLD);
                        onCreate.invoke(create);
                        LOGGER.info("[IML-SMOKE] creating world '{}'", WORLD);
                    } else if (!(screen instanceof CreateWorldScreen)) {
                        phaseTicks = Math.min(phaseTicks, 1);
                    }
                }
                case ENTER_WORLD -> {
                    if (screen != null && screen.getClass().getSimpleName().contains("Disconnected")) {
                        fail("disconnected while entering the world: " + describe(screen) + " "
                                + screen.getTitle().getString());
                    } else if (phaseTicks > WORLD_TIMEOUT_TICKS) {
                        fail("world not entered within " + WORLD_TIMEOUT_TICKS + " ticks (current screen "
                                + describe(screen) + ")");
                    } else if (mc.player != null && mc.level != null && screen == null) {
                        worldAtMs = uptime();
                        LOGGER.info("[IML-SMOKE] in world '{}' ({}) at {} ms", WORLD, worldMode, worldAtMs);
                        nextPhase(Phase.SETTLE);
                    }
                }
                case SETTLE -> {
                    if (screen != null) {
                        phaseTicks = 0;
                    } else if (phaseTicks >= SETTLE_TICKS) {
                        nextPhase(Phase.STEPS);
                        startStep(0);
                    }
                }
                case STEPS -> stepTick(mc, screen);
                case FINISHED -> {
                }
            }
        }

        private void startStep(int index) {
            stepIndex = index;
            Step step = steps.get(index);
            results.add(new StepResult(step.name()));
            stepState = StepState.OPEN;
            phaseTicks = 0;
            stepStartMs = uptime();
            LOGGER.info("[IML-SMOKE] opening {}", step.name());
            step.open().run();
        }

        private void stepTick(Minecraft mc, Screen screen) throws Exception {
            Step step = steps.get(stepIndex);
            StepResult result = results.get(stepIndex);
            switch (stepState) {
                case OPEN -> {
                    if (screen != null && step.expected().test(screen)) {
                        result.openMs = uptime() - stepStartMs;
                        if (screen instanceof MilestoneScreen ms) {
                            milestone = ms;
                        }
                        stepState = StepState.HOLD;
                        phaseTicks = 0;
                    } else if (phaseTicks > STEP_TIMEOUT_TICKS) {
                        result.status = "FAIL";
                        result.detail = "screen did not open; current=" + describe(screen);
                        fail(step.name() + " did not open (current screen " + describe(screen) + ")");
                    }
                }
                case HOLD -> {
                    if (!step.expected().test(screen)) {
                        result.status = "FAIL";
                        result.detail = "screen closed while held; current=" + describe(screen);
                        fail(step.name() + " closed while held (current " + describe(screen) + ")");
                    } else if (phaseTicks >= HOLD_TICKS) {
                        String file = "iml-smoke-" + (stepIndex + 1) + "-" + step.name() + ".png";
                        result.screenshot = "screenshots/" + file;
                        Files.deleteIfExists(new File(mc.gameDirectory, result.screenshot).toPath());
                        shotMessage = null;
                        grabScreenshot(mc, file, c -> shotMessage = c.getString());
                        stepState = StepState.SHOT;
                        phaseTicks = 0;
                    }
                }
                case SHOT -> {
                    File shot = new File(mc.gameDirectory, result.screenshot);
                    if (shotMessage != null && shot.isFile() && shot.length() > 0) {
                        LOGGER.info("[IML-SMOKE] {} screenshot: {} ({})", step.name(), shot, shotMessage);
                        result.status = "PASS";
                        result.detail = "held " + HOLD_TICKS + " ticks";
                        if (screen instanceof MilestoneScreen) {
                            // Keep the MilestoneScreen open: the next screens are its children.
                            startStep(stepIndex + 1);
                        } else {
                            stepState = StepState.CLOSE;
                            phaseTicks = 0;
                            // Back/Esc path of the sub-screen: returns to its MilestoneScreen parent.
                            screen.onClose();
                        }
                    } else if (phaseTicks > STEP_TIMEOUT_TICKS) {
                        result.status = "FAIL";
                        result.detail = "screenshot not written: " + shotMessage;
                        fail(step.name() + " screenshot not written (" + shotMessage + ")");
                    }
                }
                case CLOSE -> {
                    boolean last = stepIndex == steps.size() - 1;
                    if (screen instanceof MilestoneScreen) {
                        if (last) {
                            succeed(mc);
                        } else {
                            startStep(stepIndex + 1);
                        }
                    } else if (phaseTicks > STEP_TIMEOUT_TICKS) {
                        fail(step.name() + " did not return to MilestoneScreen (current " + describe(screen) + ")");
                    }
                }
            }
        }

        private void succeed(Minecraft mc) {
            Screen screen = currentScreen(mc);
            if (screen != null) {
                screen.onClose();
            }
            finished = true;
            nextPhase(Phase.FINISHED);
            LOGGER.info("[IML-SMOKE] PASS: all {} screens opened; stopping client", steps.size());
            writeResult("PASS", "");
            mc.stop();
        }

        private void fail(String reason) {
            if (finished) {
                return;
            }
            finished = true;
            nextPhase(Phase.FINISHED);
            LOGGER.error("[IML-SMOKE] FAIL: {}", reason);
            writeResult("FAIL", reason);
            Thread exit = new Thread(() -> System.exit(1), "IML-Smoke-Exit");
            exit.setDaemon(true);
            exit.start();
        }

        private void nextPhase(Phase next) {
            phase = next;
            phaseTicks = 0;
        }

        private synchronized void writeResult(String status, String reason) {
            Minecraft mc = Minecraft.getInstance();
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"status\": ").append(json(status)).append(",\n");
            sb.append("  \"reason\": ").append(json(reason)).append(",\n");
            sb.append("  \"world\": ").append(json(WORLD)).append(",\n");
            sb.append("  \"world_mode\": ").append(json(worldMode)).append(",\n");
            sb.append("  \"title_at_ms\": ").append(titleAtMs).append(",\n");
            sb.append("  \"world_at_ms\": ").append(worldAtMs).append(",\n");
            sb.append("  \"finished_at_ms\": ").append(uptime()).append(",\n");
            sb.append("  \"dismissed_screens\": [");
            for (int i = 0; i < dismissed.size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(json(dismissed.get(i)));
            }
            sb.append("],\n");
            sb.append("  \"screens\": [\n");
            for (int i = 0; i < results.size(); i++) {
                StepResult r = results.get(i);
                sb.append("    {\"name\": ").append(json(r.name))
                        .append(", \"status\": ").append(json(r.status))
                        .append(", \"open_ms\": ").append(r.openMs)
                        .append(", \"screenshot\": ").append(json(r.screenshot))
                        .append(", \"detail\": ").append(json(r.detail)).append("}")
                        .append(i == results.size() - 1 ? "\n" : ",\n");
            }
            sb.append("  ]\n}\n");
            try {
                Path dir = mc.gameDirectory.toPath().resolve("iml-smoke");
                Files.createDirectories(dir);
                Path tmp = dir.resolve("result.json.tmp");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, dir.resolve("result.json"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOGGER.error("[IML-SMOKE] could not write result.json", e);
            }
        }

        // ---- version bridges (reflection) ----

        private static Field screenField;
        private static Method guiScreenGetter;
        private static Method screenSetter;

        /** 1.21 .. 26.1: public field {@code Minecraft.screen}; 26.3: {@code mc.gui.screen()}. */
        static Screen currentScreen(Minecraft mc) {
            try {
                if (screenField == null && guiScreenGetter == null) {
                    try {
                        screenField = Minecraft.class.getField("screen");
                    } catch (NoSuchFieldException e) {
                        guiScreenGetter = mc.gui.getClass().getMethod("screen");
                    }
                }
                return screenField != null ? (Screen) screenField.get(mc) : (Screen) guiScreenGetter.invoke(mc.gui);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot read the current screen", e);
            }
        }

        /** 1.21 .. 26.1: {@code Minecraft.setScreen}; 26.3: {@code mc.gui.setScreen}. */
        static void setScreen(Minecraft mc, Screen screen) {
            try {
                if (screenSetter == null) {
                    try {
                        screenSetter = Minecraft.class.getMethod("setScreen", Screen.class);
                    } catch (NoSuchMethodException e) {
                        screenSetter = mc.gui.getClass().getMethod("setScreen", Screen.class);
                    }
                }
                screenSetter.invoke(screenSetter.getDeclaringClass() == Minecraft.class ? mc : mc.gui, screen);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot set the screen", e);
            }
        }

        /** {@code CreateWorldScreen.openFresh(Minecraft, Screen)} (..1.21.10) or {@code (Minecraft, Runnable)}. */
        static void openCreateWorldScreen(Minecraft mc, Screen parent) throws ReflectiveOperationException {
            for (Method m : CreateWorldScreen.class.getMethods()) {
                if (!m.getName().equals("openFresh") || m.getParameterCount() != 2
                        || m.getParameterTypes()[0] != Minecraft.class) {
                    continue;
                }
                Class<?> second = m.getParameterTypes()[1];
                if (second == Screen.class) {
                    m.invoke(null, mc, parent);
                    return;
                }
                if (second == Runnable.class) {
                    m.invoke(null, mc, (Runnable) () -> setScreen(mc, null));
                    return;
                }
            }
            throw new NoSuchMethodException("CreateWorldScreen.openFresh(Minecraft, Screen|Runnable)");
        }

        /**
         * The game's own screenshot path: {@code Screenshot.grab(File, String, RenderTarget, [int], Consumer)}
         * on the main render target ({@code Minecraft.getMainRenderTarget()} or, on 26.3,
         * {@code gameRenderer.mainRenderTarget()}).
         */
        static void grabScreenshot(Minecraft mc, String fileName, Consumer<Component> callback)
                throws ReflectiveOperationException {
            Object target;
            try {
                target = Minecraft.class.getMethod("getMainRenderTarget").invoke(mc);
            } catch (NoSuchMethodException e) {
                Object renderer = Minecraft.class.getField("gameRenderer").get(mc);
                target = renderer.getClass().getMethod("mainRenderTarget").invoke(renderer);
            }
            Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot");
            for (Method m : screenshot.getMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (!m.getName().equals("grab") || p.length < 4 || p[0] != File.class || p[1] != String.class
                        || p[p.length - 1] != Consumer.class) {
                    continue;
                }
                Object[] args = new Object[p.length];
                args[0] = mc.gameDirectory;
                args[1] = fileName;
                args[2] = target;
                for (int i = 3; i < p.length - 1; i++) {
                    args[i] = 1; // downscale factor (1.21.11+): full resolution
                }
                args[p.length - 1] = callback;
                m.invoke(null, args);
                return;
            }
            throw new NoSuchMethodException("Screenshot.grab(File, String, RenderTarget, ..., Consumer)");
        }

        static long uptime() {
            return ManagementFactory.getRuntimeMXBean().getUptime();
        }

        static String describe(Screen screen) {
            return screen == null ? "none" : screen.getClass().getName();
        }

        static String json(String s) {
            if (s == null) {
                return "null";
            }
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.append('"').toString();
        }
    }
}
