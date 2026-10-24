package org.lwjgl.opengl;

import org.lwjgl.PointerWrapperAbstract;

/**
 * LWJGL 2's sync object; LWJGL 3 passes the pointer itself. The constructor is public (it wasn't in LWJGL
 * 2) so the mirrors of {@code glFenceSync} can wrap what LWJGL 3 returns.
 */
public final class GLSync extends PointerWrapperAbstract {
    public GLSync(long sync) {
        super(sync);
    }
}
