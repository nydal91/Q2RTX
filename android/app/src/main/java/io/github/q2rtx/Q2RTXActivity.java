package io.github.q2rtx;

import android.os.Bundle;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

/**
 * Runs the engine. SDLActivity loads libSDL2.so and libmain.so and calls SDL_main()
 * (Q2RTX's main() in src/unix/system.c) on its own thread. The game module
 * (libgame.so) is dlopen()ed by the engine when a map starts.
 */
public class Q2RTXActivity extends SDLActivity {

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    /** Command line for the engine: GPU driver (GpuDrivers) and test switches (GraphicsTests). */
    @Override
    protected String[] getArguments() {
        java.util.List<String> args = GpuDrivers.engineArguments(this);
        args.addAll(GraphicsTests.engineArguments(this));
        return args.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Make sure the bundled shaders are in place before the engine looks for them.
        GameData.extractBundled(this);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // The engine calls exit() when quitting; make sure we don't linger half-alive.
        if (isFinishing())
            System.exit(0);
    }
}
