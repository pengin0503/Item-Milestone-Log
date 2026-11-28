package com.pengin0503.itemmilestonelog.compat.client.v121_1;

import com.pengin0503.itemmilestonelog.compat.client.ParticleCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Profile-scoped particle hook. All Minecraft access is reflective on purpose: particle APIs
 * differ between 1.20, the 1.21.x line and 26.x, and the merged-jar link audit checks every
 * shared class against every version. Missing methods fail soft (no particle, no log spam).
 */
public final class ParticleCompatImpl implements ParticleCompat {
    private static final String[] PARTICLE_FIELDS = {"FLAME", "SMOKE", "CRIT"};

    @Override
    public boolean spawn(double x, double y, double z, String particleName) {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft");
            Object mc = minecraftClass.getMethod("getInstance").invoke(null);
            Object level = levelOf(minecraftClass, mc);
            if (level == null) {
                return false;
            }
            Class<?> particleTypes = Class.forName("net.minecraft.core.particles.ParticleTypes");
            Object options = null;
            String[] names = particleName == null || particleName.isBlank()
                    ? PARTICLE_FIELDS : new String[]{particleName};
            for (String name : names) {
                try {
                    Field field = particleTypes.getField(name);
                    if (Modifier.isStatic(field.getModifiers())) {
                        options = field.get(null);
                        break;
                    }
                } catch (ReflectiveOperationException ignored) {
                    // Try the next built-in particle.
                }
            }
            if (options == null) {
                return false;
            }
            Method add = findAddParticle(level.getClass(), options.getClass());
            if (add == null) {
                return false;
            }
            add.invoke(level, options, x, y, z, 0.0D, 0.0D, 0.0D);
            return true;
        } catch (Throwable ignored) {
            // Particles are cosmetic: a failure must never interrupt gameplay.
            return false;
        }
    }

    private static Object levelOf(Class<?> minecraftClass, Object mc) {
        try {
            return minecraftClass.getField("level").get(mc);
        } catch (ReflectiveOperationException ignored) {
            try {
                return minecraftClass.getMethod("level").invoke(mc);
            } catch (ReflectiveOperationException ignoredAgain) {
                return null;
            }
        }
    }

    private static Method findAddParticle(Class<?> levelClass, Class<?> optionsClass) {
        for (Method method : levelClass.getMethods()) {
            if (!"addParticle".equals(method.getName()) || method.getParameterCount() != 7) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (!params[0].isAssignableFrom(optionsClass)) {
                continue;
            }
            boolean doubles = true;
            for (int i = 1; i < 7; i++) {
                doubles &= params[i] == double.class;
            }
            if (doubles) {
                return method;
            }
        }
        return null;
    }
}
