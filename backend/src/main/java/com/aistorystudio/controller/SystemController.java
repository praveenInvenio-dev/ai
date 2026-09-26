package com.aistorystudio.controller;

import com.aistorystudio.system.ResourceMonitorService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Spec section 55: a small status panel showing what hardware is actually
 *  available, so the fallback to 2.5D / CPU rendering is visible rather than
 *  a silent decision the user has no way to see the reason for. */
@RestController
@RequestMapping("/api/system")
@CrossOrigin
public class SystemController {

    private final ResourceMonitorService resourceMonitorService;

    public SystemController(ResourceMonitorService resourceMonitorService) {
        this.resourceMonitorService = resourceMonitorService;
    }

    @GetMapping("/resources")
    public ResourceMonitorService.ResourceStatus resources() {
        return resourceMonitorService.current();
    }
}
