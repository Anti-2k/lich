package dev.lich.launchwrapper;

import java.net.URL;
import java.util.AbstractList;
import java.util.List;

/**
 * What {@code LaunchClassLoader.getSources()} returns while Lich hides jars from it ({@link LichSources}): its
 * sources without the hidden ones, as a live view. 1.12 hands out its own list, and mods write to it: GTNH's
 * {@code MinecraftURLClassPath.addJar} (UniMixins, GTNHMixins) adds a jar with {@code addURL}, then removes the last
 * source again so FML's mod discovery doesn't find the jar a second time. A copy would drop that removal, and FML
 * would stop on "Found a duplicate mod". Writes go to the real list, at the matching position.
 */
public final class VisibleSources extends AbstractList<URL> {
    private final List<URL> sources;

    public VisibleSources(List<URL> sources) {
        this.sources = sources;
    }

    @Override
    public URL get(int index) {
        return sources.get(sourceIndex(index));
    }

    @Override
    public int size() {
        int size = 0;
        for (URL source : sources) {
            if (!LichSources.isHidden(source)) {
                size++;
            }
        }
        return size;
    }

    @Override
    public URL set(int index, URL element) {
        return sources.set(sourceIndex(index), element);
    }

    @Override
    public void add(int index, URL element) {
        // At the end of the view is at the end of the list, even after hidden entries.
        sources.add(index == size() ? sources.size() : sourceIndex(index), element);
        modCount++;
    }

    @Override
    public URL remove(int index) {
        modCount++;
        return sources.remove(sourceIndex(index));
    }

    /** Where the view's {@code index}-th entry is in the real list. */
    private int sourceIndex(int index) {
        if (index >= 0) {
            int visible = 0;
            for (int i = 0; i < sources.size(); i++) {
                if (!LichSources.isHidden(sources.get(i)) && visible++ == index) {
                    return i;
                }
            }
        }
        throw new IndexOutOfBoundsException("Index " + index + ", size " + size());
    }
}
