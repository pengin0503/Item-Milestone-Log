package com.pengin0503.itemmilestonelog.compat.v26_3.client;

import com.pengin0503.itemmilestonelog.compat.client.ScreenshotCompat;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 26.3 screenshot implementation. All Minecraft API access is reflective on purpose: the
 * screenshot entry point varies across the 1.21.x and 26.x lines, and the merged-jar link audit
 * checks every shared class against every version. This class is profile-scoped and fails soft
 * (no screenshot) when the expected overload is absent.
 */
public final class ScreenshotCompatImpl implements ScreenshotCompat {
    @Override
    public boolean capture(Path clientDir, String fileName) {
        if (clientDir == null || fileName == null || fileName.contains("/") || fileName.contains("\\")) {
            return false;
        }
        try {
            Path shots = clientDir.resolve("screenshots");
            Files.createDirectories(shots);
            Class<?> screenshot = Class.forName("net.minecraft.client.Screenshot");
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
            Class<?> renderTarget = Class.forName("com.mojang.blaze3d.pipeline.RenderTarget");
            Class<?> component = Class.forName("net.minecraft.network.chat.Component");
            Object mc = minecraft.getMethod("getInstance").invoke(null);
            Object target = renderTarget.cast(mainRenderTarget(minecraft, mc));
            Method grab = findGrab(screenshot, renderTarget, component);
            if (grab == null) {
                return false;
            }
            Consumer<Object> noop = message -> { };
            if (grab.getParameterCount() == 5) {
                grab.invoke(null, clientDir.toFile(), fileName, target, 1, noop);
            } else {
                grab.invoke(null, clientDir.toFile(), fileName, target, noop);
            }
            return true;
        } catch (Throwable t) {
            // A screenshot must never interrupt gameplay or logout.
            return false;
        }
    }

    private static Object mainRenderTarget(Class<?> minecraft, Object mc) throws ReflectiveOperationException {
        try {
            return minecraft.getMethod("getMainRenderTarget").invoke(mc);
        } catch (NoSuchMethodException e) {
            Object renderer = minecraft.getField("gameRenderer").get(mc);
            return renderer.getClass().getMethod("mainRenderTarget").invoke(renderer);
        }
    }

    private static Method findGrab(Class<?> screenshot, Class<?> renderTarget, Class<?> component) {
        for (Method method : screenshot.getMethods()) {
            if (!"grab".equals(method.getName()) || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 4 && params[0] == File.class && params[1] == String.class
                    && params[2] == renderTarget && params[3] == Consumer.class) {
                return method;
            }
            if (params.length == 5 && params[0] == File.class && params[1] == String.class
                    && params[2] == renderTarget && params[3] == int.class && params[4] == Consumer.class) {
                return method;
            }
        }
        return null;
    }
}
