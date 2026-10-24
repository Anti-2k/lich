package dev.lich.launchwrapper.testsupport;

import net.minecraft.launchwrapper.IClassTransformer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records, by label, when it saw fixture.Ordered. The no-argument form is the one registered by name. */
public class RecordingTransformer implements IClassTransformer {
    public static final List<String> SEEN = new CopyOnWriteArrayList<>();

    private final String label;

    public RecordingTransformer() {
        this("registered");
    }

    public RecordingTransformer(String label) {
        this.label = label;
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if ("fixture.Ordered".equals(name)) {
            SEEN.add(label);
        }
        return basicClass;
    }
}
