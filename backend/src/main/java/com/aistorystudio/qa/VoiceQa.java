package com.aistorystudio.qa;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.util.Arrays;

/**
 * How much "air" a speech recording has: the energy between 4 and 8 kHz compared with the voice's body (300 Hz - 3 kHz), measured on the
 * loud frames only (pauses and music beds do not count). A clear synthetic voice sits around -15 to -20 dB; a muffled one (a vocoder that
 * only produced up to about 4 kHz, or a dull reference clip) at -25 dB or lower and sounds like a bad phone line. Pure Java, no FFmpeg.
 */
public final class VoiceQa {

    private VoiceQa() { }

    /** A voice dull enough to be corrected (and reported when it still is). */
    public static final double MUFFLED_BELOW_DB = -21.0;
    /** About where clear synthetic voices sit; the correction aims here. */
    public static final double TARGET_DB = -17.5;

    private static final int N = 4096;

    /** 4-8 kHz vs 300-3000 Hz in dB (0 = equal, -15 = typical clear voice), or NaN when the audio cannot be read or has too little speech. */
    public static double highRatioDb(byte[] wav) {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav))) {
            AudioFormat f = in.getFormat();
            if (f.getSampleSizeInBits() != 16 || f.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) return Double.NaN;
            byte[] raw = in.readAllBytes();
            int ch = Math.max(1, f.getChannels());
            boolean big = f.isBigEndian();
            int frames = raw.length / (2 * ch);
            double[] x = new double[frames];
            for (int i = 0; i < frames; i++) {
                double sum = 0;
                for (int c = 0; c < ch; c++) {
                    int p = (i * ch + c) * 2;
                    int v = big ? (raw[p] << 8) | (raw[p + 1] & 255) : (raw[p + 1] << 8) | (raw[p] & 255);
                    sum += v / 32768.0;
                }
                x[i] = sum / ch;
            }
            return ratio(x, f.getSampleRate());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    static double ratio(double[] x, float sampleRate) {
        int blocks = x.length / N;
        if (blocks < 6) return Double.NaN;
        double[][] spectra = new double[blocks][];
        double[] energy = new double[blocks];
        double[] window = new double[N];
        for (int i = 0; i < N; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1));
        for (int b = 0; b < blocks; b++) {
            double[] re = new double[N], im = new double[N];
            for (int i = 0; i < N; i++) { re[i] = x[b * N + i] * window[i]; energy[b] += re[i] * re[i]; }
            fft(re, im);
            double[] p = new double[N / 2];
            for (int k = 0; k < N / 2; k++) p[k] = re[k] * re[k] + im[k] * im[k];
            spectra[b] = p;
        }
        double[] sorted = energy.clone();
        Arrays.sort(sorted);
        double threshold = sorted[(int) (blocks * 0.65)];
        double[] avg = new double[N / 2];
        int used = 0;
        for (int b = 0; b < blocks; b++) {
            if (energy[b] < threshold || energy[b] <= 1e-9) continue;
            used++;
            for (int k = 0; k < N / 2; k++) avg[k] += spectra[b][k];
        }
        if (used < 3) return Double.NaN;
        double hz = sampleRate / N, mid = 0, high = 0;
        int midN = 0, highN = 0;
        for (int k = 1; k < N / 2; k++) {
            double f = k * hz;
            if (f >= 300 && f < 3000) { mid += avg[k]; midN++; }
            else if (f >= 4000 && f < 8000) { high += avg[k]; highN++; }
        }
        if (midN == 0 || highN == 0 || mid <= 0 || high <= 0) return Double.NaN;
        return 10 * Math.log10((high / highN) / (mid / midN));
    }

    /** In-place radix-2 FFT. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }

    /**
     * The treble shelf (in dB, 0 = leave the voice alone) that brings a dull voice up to a clear one. Measured on real speech: a +3.2
     * setting took a -25.4 dB voice to -17.3 dB; a voice that was already fine is never touched.
     */
    public static double correctionDb(double ratioDb) {
        if (Double.isNaN(ratioDb) || ratioDb >= MUFFLED_BELOW_DB) return 0;
        double deficit = TARGET_DB - ratioDb;
        return Math.max(0, Math.min(6.5, 0.8 + deficit * 0.95));   // was min(4, 2.2 + 0.14*deficit): a voice measured at -22 dB was still -22 dB after correction
    }
}
