package meridian.core.api;

/**
 * One cell of the fluid layer: which fluid fills it and how high.
 *
 * <p>Fluids sit in a layer of their own, beside the blocks - a cell can hold a block and a fluid
 * at once. A source and the flow it feeds are separate fluids ({@code Water_Source} and
 * {@code Water}), so copying the sources is enough to have the flow back once they are placed.
 *
 * @param id    the fluid's id on this connection
 * @param name  the fluid's name, as the prefab format stores it ({@code "Water_Source"})
 * @param level the server's fill level of the cell, as it sent it
 */
public record FluidCell(int id, String name, int level) {
}
