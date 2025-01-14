/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.core.content.res;

import androidx.annotation.ColorInt;
import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.core.graphics.ColorUtils;

/**
 * A color appearance model, based on CAM16, extended to use L* as the lightness dimension, and
 * coupled to a gamut mapping algorithm. Creates a color system, enables a digital design system.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class CamColor {
    // The maximum difference between the requested L* and the L* returned.
    private static final float DL_MAX = 0.2f;
    // The maximum color distance, in CAM16-UCS, between a requested color and the color returned.
    private static final float DE_MAX = 1.0f;
    // When the delta between the floor & ceiling of a binary search for chroma is less than this,
    // the binary search terminates.
    private static final float CHROMA_SEARCH_ENDPOINT = 0.4f;
    // When the delta between the floor & ceiling of a binary search for J, lightness in CAM16,
    // is less than this, the binary search terminates.
    private static final float LIGHTNESS_SEARCH_ENDPOINT = 0.01f;

    // CAM16 color dimensions, see getters for documentation.
    private final float mHue;
    private final float mChroma;
    private final float mJ;
    private final float mQ;
    private final float mM;
    private final float mS;

    // Coordinates in UCS space. Used to determine color distance, like delta E equations in L*a*b*.
    private final float mJstar;
    private final float mAstar;
    private final float mBstar;

    /** Hue in CAM16 */
    @FloatRange(from = 0.0, to = 360.0, toInclusive = false)
    float getHue() {
        return mHue;
    }

    /** Chroma in CAM16 */
    @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
    float getChroma() {
        return mChroma;
    }

    /** Lightness in CAM16 */
    @FloatRange(from = 0.0, to = 100.0)
    float getJ() {
        return mJ;
    }

    /**
     * Brightness in CAM16.
     *
     * <p>Prefer lightness, brightness is an absolute quantity. For example, a sheet of white paper
     * is much brighter viewed in sunlight than in indoor light, but it is the lightest object under
     * any lighting.
     */
    @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
    float getQ() {
        return mQ;
    }

    /**
     * Colorfulness in CAM16.
     *
     * <p>Prefer chroma, colorfulness is an absolute quantity. For example, a yellow toy car is much
     * more colorful outside than inside, but it has the same chroma in both environments.
     */
    @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
    float getM() {
        return mM;
    }

    /**
     * Saturation in CAM16.
     *
     * <p>Colorfulness in proportion to brightness. Prefer chroma, saturation measures colorfulness
     * relative to the color's own brightness, where chroma is colorfulness relative to white.
     */
    @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
    float getS() {
        return mS;
    }

    /** Lightness coordinate in CAM16-UCS */
    @FloatRange(from = 0.0, to = 100.0)
    float getJStar() {
        return mJstar;
    }

    /** a* coordinate in CAM16-UCS */
    @FloatRange(from = Double.NEGATIVE_INFINITY, to = Double.POSITIVE_INFINITY, fromInclusive =
            false, toInclusive = false)
    float getAStar() {
        return mAstar;
    }

    /** b* coordinate in CAM16-UCS */
    @FloatRange(from = Double.NEGATIVE_INFINITY, to = Double.POSITIVE_INFINITY, fromInclusive =
            false, toInclusive = false)
    float getBStar() {
        return mBstar;
    }

    /** Construct a CAM16 color */
    CamColor(float hue, float chroma, float j, float q, float m, float s, float jStar, float aStar,
            float bStar) {
        mHue = hue;
        mChroma = chroma;
        mJ = j;
        mQ = q;
        mM = m;
        mS = s;
        mJstar = jStar;
        mAstar = aStar;
        mBstar = bStar;
    }

    /**
     * Given a hue & chroma in CAM16, L* in L*a*b*, return an ARGB integer. The chroma of the color
     * returned may, and frequently will, be lower than requested. Assumes the color is viewed in
     * the default ViewingConditions.
     */
    public static int toColor(@FloatRange(from = 0.0, to = 360.0) float hue,
            @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
                    float chroma,
            @FloatRange(from = 0.0, to = 100.0) float lStar) {
        return toColor(hue, chroma, lStar, ViewingConditions.DEFAULT);
    }

    /**
     * Create a color appearance model from a ARGB integer representing a color. It is assumed the
     * color was viewed in the default ViewingConditions.
     *
     * The alpha component is ignored, CamColor only represents opaque colors.
     */
    @NonNull
    static CamColor fromColor(@ColorInt int color) {
        float[] outCamColor = new float[7];
        float[] outM3HCT = new float[3];
        fromColorInViewingConditions(color, ViewingConditions.DEFAULT, outCamColor, outM3HCT);
        return new CamColor(outM3HCT[0], outM3HCT[1], outCamColor[0], outCamColor[1],
                outCamColor[2], outCamColor[3], outCamColor[4], outCamColor[5], outCamColor[6]);
    }

    /**
     *
     * Get the values for M3HCT color from ARGB color.
     *
     *<ul>
     *<li>outM3HCT[0] is Hue in M3HCT [0, 360); invalid values are corrected.</li>
     *<li>outM3HCT[1] is Chroma in M3HCT [0, ?); Chroma may decrease because chroma has a
     *different maximum for any given hue and tone.</li>
     *<li>outM3HCT[2] is Tone in M3HCT [0, 100]; invalid values are corrected.</li>
     *</ul>
     *
     *@param color is the ARGB color value we use to get its respective M3HCT values.
     *@param outM3HCT 3-element array which holds the resulting M3HCT components (Hue,
     *      Chroma, Tone).
     */
    public static void getM3HCTfromColor(@ColorInt int color,
            @NonNull float[] outM3HCT) {
        fromColorInViewingConditions(color, ViewingConditions.DEFAULT, null, outM3HCT);
        outM3HCT[2] = CamUtils.lStarFromInt(color);
    }

    /**
     * Create a color appearance model from a ARGB integer representing a color, specifying the
     * ViewingConditions in which the color was viewed. Prefer Cam.fromColor.
     */
    static void fromColorInViewingConditions(@ColorInt int color,
            @NonNull ViewingConditions viewingConditions, @Nullable float[] outCamColor,
            @NonNull float[] outM3HCT) {
        // Transform ARGB int to XYZ, reusing outM3HCT array to avoid a new allocation.
        CamUtils.xyzFromInt(color, outM3HCT);
        float[] xyz = outM3HCT;

        // Transform XYZ to 'cone'/'rgb' responses
        float[][] matrix = CamUtils.XYZ_TO_CAM16RGB;
        float rT = (xyz[0] * matrix[0][0]) + (xyz[1] * matrix[0][1]) + (xyz[2] * matrix[0][2]);
        float gT = (xyz[0] * matrix[1][0]) + (xyz[1] * matrix[1][1]) + (xyz[2] * matrix[1][2]);
        float bT = (xyz[0] * matrix[2][0]) + (xyz[1] * matrix[2][1]) + (xyz[2] * matrix[2][2]);

        // Discount illuminant
        float rD = viewingConditions.getRgbD()[0] * rT;
        float gD = viewingConditions.getRgbD()[1] * gT;
        float bD = viewingConditions.getRgbD()[2] * bT;

        // Chromatic adaptation
        float rAF = (float) Math.pow(viewingConditions.getFl() * Math.abs(rD) / 100.0, 0.42);
        float gAF = (float) Math.pow(viewingConditions.getFl() * Math.abs(gD) / 100.0, 0.42);
        float bAF = (float) Math.pow(viewingConditions.getFl() * Math.abs(bD) / 100.0, 0.42);
        float rA = Math.signum(rD) * 400.0f * rAF / (rAF + 27.13f);
        float gA = Math.signum(gD) * 400.0f * gAF / (gAF + 27.13f);
        float bA = Math.signum(bD) * 400.0f * bAF / (bAF + 27.13f);

        // redness-greenness
        float a = (float) (11.0 * rA + -12.0 * gA + bA) / 11.0f;
        // yellowness-blueness
        float b = (float) (rA + gA - 2.0 * bA) / 9.0f;

        // auxiliary components
        float u = (20.0f * rA + 20.0f * gA + 21.0f * bA) / 20.0f;
        float p2 = (40.0f * rA + 20.0f * gA + bA) / 20.0f;

        // hue
        float atan2 = (float) Math.atan2(b, a);
        float atanDegrees = atan2 * 180.0f / (float) Math.PI;
        float hue =
                atanDegrees < 0
                        ? atanDegrees + 360.0f
                        : atanDegrees >= 360 ? atanDegrees - 360.0f : atanDegrees;
        float hueRadians = hue * (float) Math.PI / 180.0f;

        // achromatic response to color
        float ac = p2 * viewingConditions.getNbb();

        // CAM16 lightness and brightness
        float j = 100.0f * (float) Math.pow(ac / viewingConditions.getAw(),
                viewingConditions.getC() * viewingConditions.getZ());
        float q =
                4.0f
                        / viewingConditions.getC()
                        * (float) Math.sqrt(j / 100.0f)
                        * (viewingConditions.getAw() + 4.0f)
                        * viewingConditions.getFlRoot();

        // CAM16 chroma, colorfulness, and saturation.
        float huePrime = (hue < 20.14) ? hue + 360 : hue;
        float eHue = 0.25f * (float) (Math.cos(huePrime * Math.PI / 180.0 + 2.0) + 3.8);
        float p1 = 50000.0f / 13.0f * eHue * viewingConditions.getNc() * viewingConditions.getNcb();
        float t = p1 * (float) Math.sqrt(a * a + b * b) / (u + 0.305f);
        float alpha = (float) Math.pow(1.64 - Math.pow(0.29, viewingConditions.getN()), 0.73)
                * (float) Math.pow(t, 0.9);
        // CAM16 chroma, colorfulness, saturation
        float c = alpha * (float) Math.sqrt(j / 100.0);
        float m = c * viewingConditions.getFlRoot();
        float s = 50.0f * (float) Math.sqrt((alpha * viewingConditions.getC()) / (
                viewingConditions.getAw() + 4.0f));

        // CAM16-UCS components
        float jstar = (1.0f + 100.0f * 0.007f) * j / (1.0f + 0.007f * j);
        float mstar = 1.0f / 0.0228f * (float) Math.log(1.0f + 0.0228f * m);
        float astar = mstar * (float) Math.cos(hueRadians);
        float bstar = mstar * (float) Math.sin(hueRadians);


        outM3HCT[0] = hue;
        outM3HCT[1] = c;

        if (outCamColor != null) {
            outCamColor[0] = j;
            outCamColor[1] = q;
            outCamColor[2] = m;
            outCamColor[3] = s;
            outCamColor[4] = jstar;
            outCamColor[5] = astar;
            outCamColor[6] = bstar;
        }
    }

    /**
     * Create a CAM from lightness, chroma, and hue coordinates. It is assumed those coordinates
     * were measured in the default ViewingConditions.
     */
    @NonNull
    private static CamColor fromJch(@FloatRange(from = 0.0, to = 100.0) float j,
            @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false) float c,
            @FloatRange(from = 0.0, to = 360.0) float h) {
        return fromJchInFrame(j, c, h, ViewingConditions.DEFAULT);
    }

    /**
     * Create a CAM from lightness, chroma, and hue coordinates, and also specify the
     * ViewingConditions where the color was seen.
     */
    @NonNull
    private static CamColor fromJchInFrame(@FloatRange(from = 0.0, to = 100.0) float j,
            @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false) float c,
            @FloatRange(from = 0.0, to = 360.0) float h, ViewingConditions viewingConditions) {
        float q =
                4.0f
                        / viewingConditions.getC()
                        * (float) Math.sqrt(j / 100.0)
                        * (viewingConditions.getAw() + 4.0f)
                        * viewingConditions.getFlRoot();
        float m = c * viewingConditions.getFlRoot();
        float alpha = c / (float) Math.sqrt(j / 100.0);
        float s = 50.0f * (float) Math.sqrt((alpha * viewingConditions.getC()) / (
                viewingConditions.getAw() + 4.0f));

        float hueRadians = h * (float) Math.PI / 180.0f;
        float jstar = (1.0f + 100.0f * 0.007f) * j / (1.0f + 0.007f * j);
        float mstar = 1.0f / 0.0228f * (float) Math.log(1.0 + 0.0228 * m);
        float astar = mstar * (float) Math.cos(hueRadians);
        float bstar = mstar * (float) Math.sin(hueRadians);
        return new CamColor(h, c, j, q, m, s, jstar, astar, bstar);
    }

    /**
     * Distance in CAM16-UCS space between two colors.
     *
     * <p>Much like L*a*b* was designed to measure distance between colors, the CAM16 standard
     * defined a color space called CAM16-UCS to measure distance between CAM16 colors.
     */
    float distance(@NonNull CamColor other) {
        float dJ = getJStar() - other.getJStar();
        float dA = getAStar() - other.getAStar();
        float dB = getBStar() - other.getBStar();
        double dEPrime = Math.sqrt(dJ * dJ + dA * dA + dB * dB);
        double dE = 1.41 * Math.pow(dEPrime, 0.63);
        return (float) dE;
    }

    /** Returns perceived color as an ARGB integer, as viewed in default ViewingConditions. */
    @ColorInt
    int viewedInSrgb() {
        return viewed(ViewingConditions.DEFAULT);
    }

    /** Returns color perceived in a ViewingConditions as an ARGB integer. */
    @ColorInt
    int viewed(@NonNull ViewingConditions viewingConditions) {
        float alpha =
                (getChroma() == 0.0 || getJ() == 0.0)
                        ? 0.0f
                        : getChroma() / (float) Math.sqrt(getJ() / 100.0);

        float t = (float) Math.pow(alpha / Math.pow(1.64
                - Math.pow(0.29, viewingConditions.getN()), 0.73), 1.0 / 0.9);
        float hRad = getHue() * (float) Math.PI / 180.0f;

        float eHue = 0.25f * (float) (Math.cos(hRad + 2.0) + 3.8);
        float ac = viewingConditions.getAw() * (float) Math.pow(getJ() / 100.0,
                1.0 / viewingConditions.getC() / viewingConditions.getZ());
        float p1 =
                eHue * (50000.0f / 13.0f) * viewingConditions.getNc() * viewingConditions.getNcb();
        float p2 = (ac / viewingConditions.getNbb());

        float hSin = (float) Math.sin(hRad);
        float hCos = (float) Math.cos(hRad);

        float gamma =
                23.0f * (p2 + 0.305f) * t / (23.0f * p1 + 11.0f * t * hCos + 108.0f * t * hSin);
        float a = gamma * hCos;
        float b = gamma * hSin;
        float rA = (460.0f * p2 + 451.0f * a + 288.0f * b) / 1403.0f;
        float gA = (460.0f * p2 - 891.0f * a - 261.0f * b) / 1403.0f;
        float bA = (460.0f * p2 - 220.0f * a - 6300.0f * b) / 1403.0f;

        float rCBase = (float) Math.max(0, (27.13 * Math.abs(rA)) / (400.0 - Math.abs(rA)));
        float rC = Math.signum(rA) * (100.0f / viewingConditions.getFl()) * (float) Math.pow(rCBase,
                1.0 / 0.42);
        float gCBase = (float) Math.max(0, (27.13 * Math.abs(gA)) / (400.0 - Math.abs(gA)));
        float gC = Math.signum(gA) * (100.0f / viewingConditions.getFl()) * (float) Math.pow(gCBase,
                1.0 / 0.42);
        float bCBase = (float) Math.max(0, (27.13 * Math.abs(bA)) / (400.0 - Math.abs(bA)));
        float bC = Math.signum(bA) * (100.0f / viewingConditions.getFl()) * (float) Math.pow(bCBase,
                1.0 / 0.42);
        float rF = rC / viewingConditions.getRgbD()[0];
        float gF = gC / viewingConditions.getRgbD()[1];
        float bF = bC / viewingConditions.getRgbD()[2];


        float[][] matrix = CamUtils.CAM16RGB_TO_XYZ;
        float x = (rF * matrix[0][0]) + (gF * matrix[0][1]) + (bF * matrix[0][2]);
        float y = (rF * matrix[1][0]) + (gF * matrix[1][1]) + (bF * matrix[1][2]);
        float z = (rF * matrix[2][0]) + (gF * matrix[2][1]) + (bF * matrix[2][2]);

        int argb = ColorUtils.XYZToColor(x, y, z);
        return argb;
    }

    /**
     * Given a hue & chroma in CAM16, L* in L*a*b*, and the ViewingConditions in which the
     * color will be viewed, return an ARGB integer.
     *
     * <p>The chroma of the color returned may, and frequently will, be lower than requested. This
     * is a fundamental property of color that cannot be worked around by engineering. For example,
     * a red hue, with high chroma, and high L* does not exist: red hues have a maximum chroma
     * below 10 in light shades, creating pink.
     */
    static @ColorInt int toColor(@FloatRange(from = 0.0, to = 360.0) float hue,
            @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
                    float chroma,
            @FloatRange(from = 0.0, to = 100.0) float lstar,
            @NonNull ViewingConditions viewingConditions) {
        // This is a crucial routine for building a color system, CAM16 itself is not sufficient.
        //
        // * Why these dimensions?
        // Hue and chroma from CAM16 are used because they're the most accurate measures of those
        // quantities. L* from L*a*b* is used because it correlates with luminance, luminance is
        // used to measure contrast for a11y purposes, thus providing a key constraint on what
        // colors
        // can be used.
        //
        // * Why is this routine required to build a color system?
        // In all perceptually accurate color spaces (i.e. L*a*b* and later), `chroma` may be
        // impossible for a given `hue` and `lstar`.
        // For example, a high chroma light red does not exist - chroma is limited to below 10 at
        // light red shades, we call that pink. High chroma light green does exist, but not dark
        // Also, when converting from another color space to RGB, the color may not be able to be
        // represented in RGB. In those cases, the conversion process ends with RGB values
        // outside 0-255
        // The vast majority of color libraries surveyed simply round to 0 to 255. That is not an
        // option for this library, as it distorts the expected luminance, and thus the expected
        // contrast needed for a11y
        //
        // * What does this routine do?
        // Dealing with colors in one color space not fitting inside RGB is, loosely referred to as
        // gamut mapping or tone mapping. These algorithms are traditionally idiosyncratic, there is
        // no universal answer. However, because the intent of this library is to build a system for
        // digital design, and digital design uses luminance to measure contrast/a11y, we have one
        // very important constraint that leads to an objective algorithm: the L* of the returned
        // color _must_ match the requested L*.
        //
        // Intuitively, if the color must be distorted to fit into the RGB gamut, and the L*
        // requested *must* be fulfilled, than the hue or chroma of the returned color will need
        // to be different from the requested hue/chroma.
        //
        // After exploring both options, it was more intuitive that if the requested chroma could
        // not be reached, it used the highest possible chroma. The alternative was finding the
        // closest hue where the requested chroma could be reached, but that is not nearly as
        // intuitive, as the requested hue is so fundamental to the color description.

        // If the color doesn't have meaningful chroma, return a gray with the requested Lstar.
        //
        // Yellows are very chromatic at L = 100, and blues are very chromatic at L = 0. All the
        // other hues are white at L = 100, and black at L = 0. To preserve consistency for users of
        // this system, it is better to simply return white at L* > 99, and black and L* < 0.
        if (chroma < 1.0 || Math.round(lstar) <= 0.0 || Math.round(lstar) >= 100.0) {
            return CamUtils.intFromLStar(lstar);
        }

        hue = hue < 0 ? 0 : Math.min(360, hue);

        // The highest chroma possible. Updated as binary search proceeds.
        float high = chroma;

        // The guess for the current binary search iteration. Starts off at the highest chroma,
        // thus, if a color is possible at the requested chroma, the search can stop after one try.
        float mid = chroma;
        float low = 0.0f;
        boolean isFirstLoop = true;

        CamColor answer = null;

        while (Math.abs(low - high) >= CHROMA_SEARCH_ENDPOINT) {
            // Given the current chroma guess, mid, and the desired hue, find J, lightness in
            // CAM16 color space, that creates a color with L* = `lstar` in the L*a*b* color space.
            CamColor possibleAnswer = findCamByJ(hue, mid, lstar);

            if (isFirstLoop) {
                if (possibleAnswer != null) {
                    return possibleAnswer.viewed(viewingConditions);
                } else {
                    // If this binary search iteration was the first iteration, and this point
                    // has been reached, it means the requested chroma was not available at the
                    // requested hue and L*.
                    // Proceed to a traditional binary search that starts at the midpoint between
                    // the requested chroma and 0.
                    isFirstLoop = false;
                    mid = low + (high - low) / 2.0f;
                    continue;
                }
            }

            if (possibleAnswer == null) {
                // There isn't a CAM16 J that creates a color with L* `lstar`. Try a lower chroma.
                high = mid;
            } else {
                answer = possibleAnswer;
                // It is possible to create a color. Try higher chroma.
                low = mid;
            }

            mid = low + (high - low) / 2.0f;
        }

        // There was no answer: meaning, for the desired hue, there was no chroma low enough to
        // generate a color with the desired L*.
        // All values of L* are possible when there is 0 chroma. Return a color with 0 chroma, i.e.
        // a shade of gray, with the desired L*.
        if (answer == null) {
            return CamUtils.intFromLStar(lstar);
        }

        return answer.viewed(viewingConditions);
    }

    // Find J, lightness in CAM16 color space, that creates a color with L* = `lstar` in the L*a*b*
    // color space.
    //
    // Returns null if no J could be found that generated a color with L* `lstar`.
    @Nullable
    private static CamColor findCamByJ(@FloatRange(from = 0.0, to = 360.0) float hue,
            @FloatRange(from = 0.0, to = Double.POSITIVE_INFINITY, toInclusive = false)
                    float chroma,
            @FloatRange(from = 0.0, to = 100.0) float lstar) {
        float low = 0.0f;
        float high = 100.0f;
        float mid = 0.0f;
        float bestdL = 1000.0f;
        float bestdE = 1000.0f;

        CamColor bestCam = null;
        while (Math.abs(low - high) > LIGHTNESS_SEARCH_ENDPOINT) {
            mid = low + (high - low) / 2;
            // Create the intended CAM color
            CamColor camBeforeClip = CamColor.fromJch(mid, chroma, hue);
            // Convert the CAM color to RGB. If the color didn't fit in RGB, during the conversion,
            // the initial RGB values will be outside 0 to 255. The final RGB values are clipped to
            // 0 to 255, distorting the intended color.
            int clipped = camBeforeClip.viewedInSrgb();
            float clippedLstar = CamUtils.lStarFromInt(clipped);
            float dL = Math.abs(lstar - clippedLstar);

            // If the clipped color's L* is within error margin...
            if (dL < DL_MAX) {
                // ...check if the CAM equivalent of the clipped color is far away from intended CAM
                // color. For the intended color, use lightness and chroma from the clipped color,
                // and the intended hue. Callers are wondering what the lightness is, they know
                // chroma may be distorted, so the only concern here is if the hue slipped too far.
                CamColor camClipped = CamColor.fromColor(clipped);
                float dE = camClipped.distance(
                        CamColor.fromJch(camClipped.getJ(), camClipped.getChroma(), hue));
                if (dE <= DE_MAX) {
                    bestdL = dL;
                    bestdE = dE;
                    bestCam = camClipped;
                }
            }

            // If there's no error at all, there's no need to search more.
            //
            // Note: this happens much more frequently than expected, but this is a very delicate
            // property which relies on extremely precise sRGB <=> XYZ calculations, as well as fine
            // tuning of the constants that determine error margins and when the binary search can
            // terminate.
            if (bestdL == 0 && bestdE == 0) {
                break;
            }

            if (clippedLstar < lstar) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return bestCam;
    }

}
