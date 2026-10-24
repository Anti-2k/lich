package dev.lich.launchwrapper.testtweak;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;

/** Queued by {@link FirstTweaker}; adds one launch argument. */
public class SecondTweaker implements ITweaker {
    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
    }

    @Override
    public String getLaunchTarget() {
        return "never.Used";
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[]{"--fromSecond"};
    }
}
