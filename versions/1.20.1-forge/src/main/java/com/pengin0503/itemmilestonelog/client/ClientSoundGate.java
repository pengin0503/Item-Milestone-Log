package com.pengin0503.itemmilestonelog.client;

import com.pengin0503.itemmilestonelog.ClientSoundFilter;
import com.pengin0503.itemmilestonelog.Config;
import net.minecraftforge.client.event.sound.PlaySoundEvent;

/**
 * F-150-81 (N194): client-side sound on/off gate. The event is concrete in all five trees and is
 * fired before the sound engine checks volume, so {@code setSound(null)} suppresses only the
 * selected notification without changing volume or the server packet (D-150-223).
 * Sound API calls themselves stay in the existing per-version {@code SoundCompat} implementations.
 */
public final class ClientSoundGate {
    private static volatile boolean mutedLocally;

    private ClientSoundGate() {
    }

    /** The local mirror of the F-150-50 mute key; the server action is unchanged. */
    public static void toggleMuted() {
        mutedLocally = !mutedLocally;
    }

    public static boolean isMuted() {
        return mutedLocally;
    }
    /** Resets the local-only mirror on logout; never persisted and never sent to the server. */
    public static void resetMuted() {
        mutedLocally = false;
    }

    public static void onPlaySound(PlaySoundEvent event) {
        if (event == null || event.getSound() == null) {
            return;
        }
        try {
            boolean play = ClientSoundFilter.shouldPlay(event.getName(), Config.clientSoundFilter(),
                    mutedLocally, Config.MILESTONE_SOUND.get(), Config.WORLD_FIRST_SOUND.get(),
                    Config.PRESET_COMPLETE_SOUND.get(), Config.PROGRESS_ALERT_SOUND.get());
            if (!play) {
                event.setSound(null);
            }
        } catch (IllegalStateException notLoaded) {
            // No config in a unit test / early startup: keep the vanilla sound untouched.
        }
    }
}
