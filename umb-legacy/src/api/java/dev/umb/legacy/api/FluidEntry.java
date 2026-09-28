package dev.umb.legacy.api;

/** One entry of the live FluidRegistry. */
public final class FluidEntry {

    private final String name;
    private final String className;
    private final String unlocalizedName;
    private final int luminosity;
    private final int density;
    private final int temperature;
    private final int viscosity;
    private final boolean gaseous;

    public FluidEntry(String name, String className, String unlocalizedName,
                      int luminosity, int density, int temperature, int viscosity, boolean gaseous) {
        this.name = name;
        this.className = className;
        this.unlocalizedName = unlocalizedName;
        this.luminosity = luminosity;
        this.density = density;
        this.temperature = temperature;
        this.viscosity = viscosity;
        this.gaseous = gaseous;
    }

    public String name() { return name; }
    public String className() { return className; }
    public String unlocalizedName() { return unlocalizedName; }
    public int luminosity() { return luminosity; }
    public int density() { return density; }
    public int temperature() { return temperature; }
    public int viscosity() { return viscosity; }
    public boolean gaseous() { return gaseous; }
}
