package dev.lich.launchwrapper;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisibleSourcesTest {
    private static URL url(String name) throws Exception {
        return URI.create("file:/visible-sources-test/" + name + ".jar").toURL();
    }

    /** GTNH's MinecraftURLClassPath.addJar: addURL, then take the new jar back out of the sources FML scans. */
    @Test
    void removingTheLastSourceRemovesItFromTheRealList() throws Exception {
        URL lich = url("lich-boot");
        LichSources.hide(lich);
        URL game = url("minecraft");
        URL forge = url("forge");
        List<URL> sources = new CopyOnWriteArrayList<>(List.of(game, lich, forge));
        URL mod = url("thaumcraftneiplugin");
        sources.add(mod); // what addURL does

        VisibleSources view = new VisibleSources(sources);
        assertEquals(List.of(game, forge, mod), view);
        view.remove(view.size() - 1);

        assertEquals(List.of(game, lich, forge), sources);
        assertEquals(List.of(game, forge), new VisibleSources(sources));
    }

    @Test
    void writesLandAtTheMatchingPlace() throws Exception {
        URL lich = url("lich-compat-jdk");
        LichSources.hide(lich);
        URL a = url("a");
        URL b = url("b");
        List<URL> sources = new CopyOnWriteArrayList<>(List.of(a, lich, b));
        VisibleSources view = new VisibleSources(sources);

        view.add(url("end"));
        view.add(1, url("before-b"));
        view.set(0, url("first"));
        assertEquals(List.of(url("first"), lich, url("before-b"), b, url("end")), sources);
        assertEquals(b, view.get(2));
        assertThrows(IndexOutOfBoundsException.class, () -> view.get(4));
        assertThrows(IndexOutOfBoundsException.class, () -> view.remove(-1));
    }
}
