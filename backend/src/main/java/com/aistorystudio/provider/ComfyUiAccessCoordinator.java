package com.aistorystudio.provider;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * ComfyUI runs ONE prompt at a time, full stop - regardless of whether it
 * was submitted by ComfyUIImageProvider or ComfyUIVideoProvider, since both
 * talk to the same single ComfyUI instance/queue. Submitting a new prompt
 * while one is executing does not politely queue behind it - it sends a
 * "Global interrupt" that KILLS the in-flight one (confirmed live: a 10s Wan
 * video job completed all 20 sampling steps, then got interrupted during its
 * save step by a concurrent image generation request, and never produced a
 * usable output).
 *
 * ComfyUIImageProvider and ComfyUIVideoProvider previously each held their
 * OWN separate Semaphore, on the mistaken assumption (see the video
 * provider's own prior comment) that keeping them separate would let image
 * generation proceed without waiting on a slow video job. That assumption is
 * backwards for a single ComfyUI instance: two independent semaphores
 * guarantee nothing about what ComfyUI itself does with two concurrent
 * submissions - it will still interrupt one for the other. One shared lock,
 * sized to 1 (matching ComfyUI's real one-prompt-at-a-time behavior), is the
 * only way to actually guarantee this doesn't happen.
 */
@Component
public class ComfyUiAccessCoordinator {

    private final Semaphore slot = new Semaphore(1, true);

    public Semaphore slot() {
        return slot;
    }
}
