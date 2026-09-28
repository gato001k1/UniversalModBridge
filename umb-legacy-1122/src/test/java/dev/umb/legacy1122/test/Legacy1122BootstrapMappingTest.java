package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;

class Legacy1122BootstrapMappingTest {

    @Test
    void joinedSrgGroundsNotchBootstrapClassAndMethod() throws Exception {
        File mapping = new File(TestRepo.find(), "research/mappings/joined-1.12.2.srg");
        String text = Files.readString(mapping.toPath(), StandardCharsets.UTF_8);
        assertTrue(text.contains("CL: ni net/minecraft/init/Bootstrap"),
                "joined SRG must ground the 1.12.2 Bootstrap class");
        assertTrue(text.contains("MD: ni/c ()V net/minecraft/init/Bootstrap/func_151354_b ()V"),
                "joined SRG must ground the 1.12.2 bootstrap method");
    }
}
