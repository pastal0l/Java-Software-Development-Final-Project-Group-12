/**
 * Value object returned by a DDA ray cast.
 * Carries everything the renderer needs to draw one wall slice.
 */
class RayHit {
    /** Raw (non-corrected) distance to the wall. */
    final double distance;
    /** Tile value of the wall that was hit. */
    final int wallType;
    /** Horizontal texel column within the texture [0, TEX_SIZE). */
    final int textureX;
    /** 0 = X-axis (E/W) wall face, 1 = Y-axis (N/S) wall face. */
    final int side;
    /** The texture array to sample, indexed [column][row]. */
    final int[][] texture;

    RayHit(double distance, int wallType, int textureX, int side, int[][] texture) {
        this.distance = distance;
        this.wallType = wallType;
        this.textureX = textureX;
        this.side = side;
        this.texture = texture;
    }
}
