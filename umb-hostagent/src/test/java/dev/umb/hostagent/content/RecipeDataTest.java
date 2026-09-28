package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

class RecipeDataTest {
    @Test
    void parsesCraftingAndSmeltingWithoutRequiringMinecraftClasses() {
        LegacySnapshot s = LegacySnapshot.parseString("{\"items\":[],\"recipes\":{"
                + "\"crafting\":[{\"type\":\"shaped\",\"className\":\"net.minecraft.item.crafting.ShapedRecipes\",\"width\":2,\"height\":1,"
                + "\"items\":[{\"item\":\"minecraft:iron_ingot\",\"meta\":0},null],\"output\":{\"item\":\"example:gear\",\"meta\":0,\"count\":1}}],"
                + "\"smelting\":[{\"input\":{\"item\":\"minecraft:iron_ore\",\"meta\":0},\"output\":{\"item\":\"example:iron\",\"meta\":0},\"xp\":0.7}]}}", "example");
        assertEquals(2, s.recipes.size());
        assertEquals("shaped", s.recipes.get(0).type);
        assertEquals(2, s.recipes.get(0).items.size());
        assertEquals("smelting", s.recipes.get(1).type);
        assertEquals(0.7f, s.recipes.get(1).xp, 0.001f);
    }

    @Test
    void manifestAcceptsOptionalRecipeOutputPath() {
        ModContentManifest m = ModContentManifest.parse(new com.google.gson.Gson().fromJson(
                "{\"mods\":[{\"namespace\":\"Example\",\"snapshot\":\"s.json\",\"recipes\":\"out/recipes\"}]}",
                com.google.gson.JsonObject.class));
        assertEquals(Path.of("out/recipes"), m.records().get(0).recipes());
    }
}
