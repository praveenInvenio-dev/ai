package com.aistorystudio.system;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Spec section 54-55: a resource status panel so the fallback behaviour (2.5D
 * instead of an AI animation tier, CPU instead of GPU rendering) is
 * transparent rather than a silent choice the user has to infer.
 *
 * RAM and CPU core count are always real - both come straight from the JVM's
 * own {@link Runtime}, no external process needed. GPU VRAM is queried via
 * `nvidia-smi`, which is only present on a machine with an NVIDIA driver
 * installed; when it isn't (no GPU, AMD/Intel GPU, or a CPU-only box - the
 * default target hardware this whole low-VRAM design is for), this reports
 * gpuAvailable=false with a specific reason rather than a zero/blank value
 * that could be mistaken for "0 MB VRAM".
 */
@Service
public class ResourceMonitorService {

    public record ResourceStatus(
            long ramUsedMb,
            long ramTotalMb,
            int cpuCores,
            boolean gpuAvailable,
            Long gpuVramUsedMb,
            Long gpuVramTotalMb,
            String gpuUnavailableReason
    ) {}

    public ResourceStatus current() {
        Runtime runtime = Runtime.getRuntime();
        long totalMb = runtime.totalMemory() / (1024 * 1024);
        long freeMb = runtime.freeMemory() / (1024 * 1024);
        long usedMb = totalMb - freeMb;
        int cores = runtime.availableProcessors();

        GpuReading gpu = readGpu();

        return new ResourceStatus(usedMb, totalMb, cores,
                gpu.available, gpu.usedMb, gpu.totalMb, gpu.reason);
    }

    private record GpuReading(boolean available, Long usedMb, Long totalMb, String reason) {}

    private GpuReading readGpu() {
        ProcessBuilder pb = new ProcessBuilder(
                "nvidia-smi", "--query-gpu=memory.used,memory.total", "--format=csv,noheader,nounits");
        pb.redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (java.io.IOException e) {
            // The expected, normal case on this project's default target
            // hardware (spec's own 2GB-VRAM reference machine may have no
            // NVIDIA driver at all, or none of the video-processing
            // containers need one to function) - nvidia-smi simply doesn't
            // exist. Not an error to log loudly about.
            return new GpuReading(false, null, null, "nvidia-smi not found - no NVIDIA GPU/driver detected.");
        }

        try {
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new GpuReading(false, null, null, "nvidia-smi did not respond within 5 seconds.");
            }
            String line;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (process.exitValue() != 0 || line == null || line.isBlank()) {
                return new GpuReading(false, null, null, "nvidia-smi returned no GPU data.");
            }
            String[] parts = line.split(",");
            if (parts.length < 2) {
                return new GpuReading(false, null, null, "Could not parse nvidia-smi output: " + line);
            }
            long used = Long.parseLong(parts[0].trim());
            long total = Long.parseLong(parts[1].trim());
            return new GpuReading(true, used, total, null);
        } catch (Exception e) {
            return new GpuReading(false, null, null, "Could not read GPU status: " + e.getMessage());
        }
    }
}
