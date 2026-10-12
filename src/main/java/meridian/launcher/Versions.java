package meridian.launcher;

/**
 * Version strings as Meridian writes them: dot-separated numbers, optionally a {@code -suffix} for
 * a pre-release ({@code 1.2.0-beta.1}, {@code 1.0.0-SNAPSHOT}) and a {@code +build} part that does
 * not count ({@code 1.6.1+0.7.X}).
 */
public final class Versions {

    private Versions() {
    }

    /**
     * {@code >0} when {@code a} is newer than {@code b}, {@code <0} when older, {@code 0} when the
     * same. Numbers compare part by part, a missing part counting as 0. On equal numbers a release
     * is newer than any pre-release of it, and two pre-releases compare by the numbers in their
     * suffix ({@code beta.2} after {@code beta.1}). A null version is older than any other.
     */
    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == null ? (b == null ? 0 : -1) : 1;
        }
        String[] pa = split(a);
        String[] pb = split(b);
        int byNumbers = compareNumbers(pa[0], pb[0]);
        if (byNumbers != 0) {
            return byNumbers;
        }
        if (pa[1] == null || pb[1] == null) {
            return pa[1] == null ? (pb[1] == null ? 0 : 1) : -1;
        }
        return compareNumbers(pa[1], pb[1]);
    }

    /** {@code [numbers, pre-release suffix or null]}, with any {@code +build} part dropped. */
    private static String[] split(String version) {
        String v = version.trim();
        int plus = v.indexOf('+');
        if (plus >= 0) {
            v = v.substring(0, plus);
        }
        int dash = v.indexOf('-');
        return dash < 0 ? new String[]{v, null} : new String[]{v.substring(0, dash), v.substring(dash + 1)};
    }

    /** Compares the runs of digits in two strings, in order; whatever is not a digit is ignored. */
    private static int compareNumbers(String a, String b) {
        String[] na = a.replaceAll("[^0-9]+", " ").trim().split(" ");
        String[] nb = b.replaceAll("[^0-9]+", " ").trim().split(" ");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            long x = i < na.length ? parse(na[i]) : 0;
            long y = i < nb.length ? parse(nb[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long parse(String digits) {
        try {
            return digits.isEmpty() ? 0 : Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
