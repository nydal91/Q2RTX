package io.github.q2rtx;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Switches for narrowing down GPU-specific rendering problems (e.g. on Adreno 830 /
 * Snapdragon 8 Elite) without typing in the console. They are passed to the engine as
 * +set arguments, which override saved settings. Every value is always passed, also
 * when "off", so nothing a test changed sticks around in config.cfg afterwards.
 */
final class GraphicsTests {
    /** Ray query shader builds made by build_shaders.sh; "" is the unmodified glslang output. */
    static final String[] SHADERS = { "safe", "opt", "size", "legal", "" };
    static final String[] SHADER_LABELS = {
        "safe (default)", "opt", "size", "legal (least optimized)", "original (unoptimized)"
    };

    private GraphicsTests() {}

    static int shader(Context c) { return Math.max(0, Math.min(SHADERS.length - 1, prefs(c).getInt("t_shader", 0))); }
    static boolean noDenoiser(Context c) { return prefs(c).getBoolean("t_no_denoiser", false); }
    static boolean simpleSky(Context c) { return prefs(c).getBoolean("t_simple_sky", false); }
    static boolean lightLoad(Context c) { return prefs(c).getBoolean("t_light_load", false); }

    static void setShader(Context c, int i) { prefs(c).edit().putInt("t_shader", i).apply(); }
    static void setNoDenoiser(Context c, boolean v) { prefs(c).edit().putBoolean("t_no_denoiser", v).apply(); }
    static void setSimpleSky(Context c, boolean v) { prefs(c).edit().putBoolean("t_simple_sky", v).apply(); }
    static void setLightLoad(Context c, boolean v) { prefs(c).edit().putBoolean("t_light_load", v).apply(); }

    static void reset(Context c) {
        prefs(c).edit().remove("t_shader").remove("t_no_denoiser").remove("t_simple_sky").remove("t_light_load").apply();
    }

    static boolean anyActive(Context c) {
        return shader(c) != 0 || noDenoiser(c) || simpleSky(c) || lightLoad(c);
    }

    static List<String> engineArguments(Context c) {
        List<String> a = new ArrayList<>();
        set(a, "pt_query_shader", SHADERS[shader(c)]);
        set(a, "flt_enable", noDenoiser(c) ? "0" : "1");
        set(a, "physical_sky", simpleSky(c) ? "0" : "2");
        if (lightLoad(c)) {
            // much less ray tracing work per frame (for GPU time-outs / "device lost")
            set(a, "drs_enable", "0");
            set(a, "viewsize", "40");
            set(a, "pt_num_bounce_rays", "0");
            set(a, "pt_reflect_refract", "0");
        } else {
            // the Android defaults from Sys_AddDefaultConfig
            set(a, "drs_enable", "1");
            set(a, "viewsize", "100");
            set(a, "pt_num_bounce_rays", "0.5");
            set(a, "pt_reflect_refract", "1");
        }
        return a;
    }

    private static void set(List<String> a, String name, String value) {
        a.addAll(Arrays.asList("+set", name, value));
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("q2rtx", Context.MODE_PRIVATE);
    }
}
