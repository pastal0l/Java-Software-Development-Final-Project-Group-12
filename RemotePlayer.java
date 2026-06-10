/**
 * Holds the last known world-space position and look angle of the remote
 * co-op player.  All three position fields are {@code volatile} — they are
 * written by the {@link NetworkClient} reader thread and read on the
 * Swing / game thread without additional synchronisation.
 */
class RemotePlayer {

    volatile double x;
    volatile double y;
    volatile double angle;

    /** Short label rendered above the sprite in the 3-D view. */
    final String label;

    RemotePlayer(String label) {
        this.label = label;
    }
}
