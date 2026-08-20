package bank.tessera.esb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every class this module emits is Java 8 bytecode.
 *
 * <p>{@link ToolchainTest} proves the JVM running the suite is a JDK 8. What it cannot see is a
 * class file arriving in {@code target/classes} from somewhere other than this module's javac - and
 * WP-11a adds exactly that, because the SOAP client is generated and compiled by the JAX-WS plugin.
 * A class compiled for a later release produces a runtime that starts and then fails with
 * {@code UnsupportedClassVersionError} the first time something touches it.
 *
 * <p>The same control as stratum 1's, for the same reason and against the same threat.
 */
class BytecodeVersionTest {

    private static final int JAVA_8 = 52;
    private static final File CLASSES = new File("target/classes");

    @Test
    void everyCompiledClassIsJava8Bytecode() throws IOException {
        List<File> classFiles = new ArrayList<>();
        collectClassFiles(CLASSES, classFiles);

        assertTrue(classFiles.size() > 0,
                "no compiled classes under " + CLASSES.getAbsolutePath()
                        + " - this test cannot prove anything about an empty directory");

        for (File classFile : classFiles) {
            assertEquals(JAVA_8, majorVersionOf(classFile),
                    classFile.getPath() + " is not Java 8 bytecode");
        }
    }

    private static int majorVersionOf(File classFile) throws IOException {
        InputStream stream = new FileInputStream(classFile);
        try (DataInputStream data = new DataInputStream(stream)) {
            assertEquals(0xCAFEBABE, data.readInt(), classFile + " is not a class file");
            data.readUnsignedShort();
            return data.readUnsignedShort();
        }
    }

    private static void collectClassFiles(File directory, List<File> into) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectClassFiles(entry, into);
            } else if (entry.getName().endsWith(".class")) {
                into.add(entry);
            }
        }
    }
}
