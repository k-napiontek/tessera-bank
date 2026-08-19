package bank.tessera.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The Definition of Done's "no Java 9+ construct appears anywhere in the module", asserted against
 * the bytecode that was actually emitted.
 *
 * <p>{@link ToolchainTest} asserts the JVM that ran the suite and the class library it ran against.
 * Neither is quite this statement: both describe the build, and this describes the artefact the
 * build produced. Major version 52 is Java 8. 53 is 9, 61 is 17. Reading it costs eight bytes.
 *
 * <p>What it actually catches is worth stating precisely, because the obvious answer is wrong.
 * Moving {@code tessera.java.level} does not slip past unnoticed - it was tried. Downwards, javac
 * warns about the bootstrap classpath and {@code -Werror} turns that into a failed compile;
 * upwards, JDK 8 cannot target 9 at all. So the compiler level is already well defended.
 *
 * <p>What is not defended is a class file arriving in {@code target/classes} from somewhere other
 * than this module's javac: a generated source compiled by another plugin - WP-10b adds
 * {@code wsimport} - a dependency unpacked into the output directory, or a toolchain that stops
 * being JDK 8 without the enforcer noticing. Those produce a WAR Tomcat 8.5 refuses to load, and
 * the symptom is a {@code NoClassDefFoundError} at deployment rather than anything the build said.
 * Proved by planting a Java 7 class in the output directory, which this test named and refused.
 */
public class BytecodeVersionTest {

    private static final int JAVA_8 = 52;
    private static final File CLASSES = new File("target/classes");

    @Test
    public void everyCompiledClassIsJava8Bytecode() throws IOException {
        List<File> classFiles = new ArrayList<File>();
        collectClassFiles(CLASSES, classFiles);

        assertTrue("no compiled classes under " + CLASSES.getAbsolutePath()
                + " - this test cannot prove anything about an empty directory",
                classFiles.size() > 0);

        for (int i = 0; i < classFiles.size(); i++) {
            File classFile = classFiles.get(i);
            assertEquals(classFile.getPath() + " is not Java 8 bytecode; strata 1 and 2 are pinned"
                    + " to Java 8 and Tomcat 8.5 cannot load anything newer - see CLAUDE.md",
                    JAVA_8, majorVersionOf(classFile));
        }
    }

    /**
     * The first eight bytes of a class file: the magic number {@code 0xCAFEBABE}, then the minor
     * version, then the major.
     */
    private static int majorVersionOf(File classFile) throws IOException {
        DataInputStream in = new DataInputStream(new FileInputStream(classFile));
        try {
            int magic = in.readInt();
            assertEquals(classFile + " is not a class file", 0xCAFEBABE, magic);
            in.readUnsignedShort();
            return in.readUnsignedShort();
        } finally {
            in.close();
        }
    }

    private static void collectClassFiles(File directory, List<File> found) {
        File[] entries = directory.listFiles();
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].isDirectory()) {
                collectClassFiles(entries[i], found);
            } else if (entries[i].getName().endsWith(".class")) {
                found.add(entries[i]);
            }
        }
    }
}
