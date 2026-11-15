package btools.mapaccess;

/**
 * BromNav patch: lets the app override the access of single links (node-to-node road sections)
 * while the routing data is decoded, and observe decoded tiles (for the road-legality map layer).
 */
public final class WayOverride {
  public interface Hook {
    /**
     * @param data       way description (lookup bitmap)
     * @param accessType 0 = forbidden by the profile, 1/2 = usable
     * @return the description to use, or null to drop the link
     */
    byte[] apply(byte[] data, int accessType, int lon1, int lat1, int lon2, int lat2, Object wayValidator);
  }

  public interface NodeHook {
    /** @return the node description to use (null = no tags, e.g. to open a barrier) */
    byte[] apply(byte[] data, int lon, int lat);
  }

  public interface TileListener {
    void onTile(OsmNode[] nodes);
  }

  public static volatile Hook hook;
  public static volatile NodeHook nodeHook;
  public static final ThreadLocal<TileListener> tileListener = new ThreadLocal<>();

  public static void install(Hook h) {
    hook = h;
    btools.codec.TagValueCoder.keepForbidden = h != null;
  }
}
