package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.RuntimeArray;
import org.perlonjava.runtime.runtimetypes.RuntimeList;
import org.perlonjava.runtime.runtimetypes.RuntimeScalar;

/** Java implementations of the Cephes distribution functions used by CPAN statistics modules. */
public final class MathCephes extends PerlModuleBase {
    public static final String XS_VERSION = "0.5308";

    public MathCephes() {
        // Math::Cephes.pm aliases its public functions from this SWIG package.
        super("Math::Cephesc", false);
    }

    public static void initialize() {
        MathCephes module = new MathCephes();
        try {
            module.registerMethod("ndtr", null);
            module.registerMethod("ndtri", null);
            module.registerMethod("chdtrc", null);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Unable to initialize Math::Cephes", e);
        }
    }

    public static RuntimeList ndtr(RuntimeArray args, int ctx) {
        double x = args.get(0).getDouble();
        return scalar(0.5 * regularizedGammaQ(0.5, x * x / 2.0), x < 0.0);
    }

    private static RuntimeList scalar(double upperTail, boolean complement) {
        return new RuntimeScalar(complement ? upperTail : 1.0 - upperTail).getList();
    }

    public static RuntimeList ndtri(RuntimeArray args, int ctx) {
        double p = args.get(0).getDouble();
        if (p <= 0.0) return new RuntimeScalar(Double.NEGATIVE_INFINITY).getList();
        if (p >= 1.0) return new RuntimeScalar(Double.POSITIVE_INFINITY).getList();
        return new RuntimeScalar(inverseNormal(p)).getList();
    }

    public static RuntimeList chdtrc(RuntimeArray args, int ctx) {
        double degreesOfFreedom = args.get(0).getDouble();
        double x = args.get(1).getDouble();
        if (degreesOfFreedom <= 0.0 || x < 0.0) return new RuntimeScalar(0.0).getList();
        return new RuntimeScalar(regularizedGammaQ(degreesOfFreedom / 2.0, x / 2.0)).getList();
    }

    private static double regularizedGammaQ(double a, double x) {
        if (x == 0.0) return 1.0;
        if (x < a + 1.0) {
            double sum = 1.0 / a;
            double term = sum;
            for (int n = 1; n < 10000; n++) {
                term *= x / (a + n);
                sum += term;
                if (Math.abs(term) < Math.abs(sum) * 1.0e-15) break;
            }
            return 1.0 - sum * Math.exp(-x + a * Math.log(x) - logGamma(a));
        }

        double b = x + 1.0 - a;
        double c = 1.0 / 1.0e-300;
        double d = 1.0 / b;
        double h = d;
        for (int i = 1; i < 10000; i++) {
            double an = -i * (i - a);
            b += 2.0;
            d = an * d + b;
            if (Math.abs(d) < 1.0e-300) d = 1.0e-300;
            c = b + an / c;
            if (Math.abs(c) < 1.0e-300) c = 1.0e-300;
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < 1.0e-15) break;
        }
        return Math.exp(-x + a * Math.log(x) - logGamma(a)) * h;
    }

    private static double logGamma(double x) {
        double[] coefficients = {
                676.5203681218851, -1259.1392167224028, 771.32342877765313,
                -176.61502916214059, 12.507343278686905, -0.13857109526572012,
                9.9843695780195716e-6, 1.5056327351493116e-7
        };
        if (x < 0.5) return Math.log(Math.PI) - Math.log(Math.sin(Math.PI * x)) - logGamma(1.0 - x);
        x -= 1.0;
        double sum = 0.99999999999980993;
        for (int i = 0; i < coefficients.length; i++) sum += coefficients[i] / (x + i + 1.0);
        double t = x + coefficients.length - 0.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    // Peter J. Acklam's inverse-normal rational approximation.
    private static double inverseNormal(double p) {
        double[] a = {-39.69683028665376, 220.9460984245205, -275.9285104469687,
                138.3577518672690, -30.66479806614716, 2.506628277459239};
        double[] b = {-54.47609879822406, 161.5858368580409, -155.6989798598866,
                66.80131188771972, -13.28068155288572};
        double[] c = {-0.007784894002430293, -0.3223964580411365, -2.400758277161838,
                -2.549732539343734, 4.374664141464968, 2.938163982698783};
        double[] d = {0.007784695709041462, 0.3224671290700398, 2.445134137142996,
                3.754408661907416};
        if (p < 0.02425) {
            double q = Math.sqrt(-2.0 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        if (p > 0.97575) return -inverseNormal(1.0 - p);
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
    }
}
