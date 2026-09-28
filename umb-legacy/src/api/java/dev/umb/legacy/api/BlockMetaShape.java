package dev.umb.legacy.api;

/**
 * Legacy compatibility behavior.
 * the real declared shape of one or more metadata values of a single legacy block, extracted by calling the block's own generic vanilla 1.7.10 shape API ({@code setBlockBoundsBasedOnState}, {@code getCollisionBoundingBoxFromPool},...
 */
public final class BlockMetaShape {

    private final int[] metas;
    private final double[] rawBounds;
    private final double[] collisionAabb;
    private final double[] selectionAabb;
    private final double[][] collisionBoxes;
    private final boolean isOpaqueCube;
    private final boolean renderAsNormalBlock;
    private final boolean isFullCube;
    private final String error;

    public BlockMetaShape(int[] metas, double[] rawBounds, double[] collisionAabb, double[] selectionAabb,
                           double[][] collisionBoxes, boolean isOpaqueCube, boolean renderAsNormalBlock,
                           boolean isFullCube, String error) {
        this.metas = metas;
        this.rawBounds = rawBounds;
        this.collisionAabb = collisionAabb;
        this.selectionAabb = selectionAabb;
        this.collisionBoxes = collisionBoxes;
        this.isOpaqueCube = isOpaqueCube;
        this.renderAsNormalBlock = renderAsNormalBlock;
        this.isFullCube = isFullCube;
        this.error = error;
    }

    public int[] metas() { return metas; }
    public double[] rawBounds() { return rawBounds; }
    public double[] collisionAabb() { return collisionAabb; }
    public double[] selectionAabb() { return selectionAabb; }
    public double[][] collisionBoxes() { return collisionBoxes; }
    public boolean isOpaqueCube() { return isOpaqueCube; }
    public boolean renderAsNormalBlock() { return renderAsNormalBlock; }
    public boolean isFullCube() { return isFullCube; }
    public String error() { return error; }

    public boolean isMultiBox() {
        return collisionBoxes != null && collisionBoxes.length > 1;
    }
}
