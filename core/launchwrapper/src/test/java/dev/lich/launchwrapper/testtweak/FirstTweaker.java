package dev.lich.launchwrapper.testtweak;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Primary test tweaker: queues {@link SecondTweaker} through the blackboard, as FML queues its tweakers. */
public class FirstTweaker implements ITweaker {
    public static File gameDir;
    public static String profile;

    private final List<String> args = new ArrayList<>();

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        FirstTweaker.gameDir = gameDir;
        FirstTweaker.profile = profile;
        this.args.addAll(args);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        ((List<String>) Launch.blackboard.get("TweakClasses")).add(SecondTweaker.class.getName());
    }

    @Override
    public String getLaunchTarget() {
        return "dev.lich.launchwrapper.testtarget.Target";
    }

    @Override
    public String[] getLaunchArguments() {
        args.add("--fromFirst");
        return args.toArray(new String[0]);
    }
}
