package com.gov.docregistry.health;

import com.gov.docregistry.fabric.FabricGatewayService;
import com.gov.docregistry.ipfs.IpfsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/actuator")
public class HealthController {

    @Autowired
    private FabricGatewayService fabricService;

    @Autowired
    private IpfsService ipfsService;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        
        // Check Fabric connection
        try {
            fabricService.connect();
            health.put("fabric", Map.of("status", "UP", "message", "Connected to Fabric network"));
        } catch (Exception e) {
            health.put("fabric", Map.of("status", "DOWN", "message", e.getMessage()));
        }
        
        // Check IPFS connection
        try {
            String version = ipfsService.getVersion();
            health.put("ipfs", Map.of("status", "UP", "version", version));
        } catch (Exception e) {
            health.put("ipfs", Map.of("status", "DOWN", "message", e.getMessage()));
        }
        
        return ResponseEntity.ok(health);
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("timestamp", System.currentTimeMillis());
        metrics.put("uptime", System.currentTimeMillis() - getStartTime());
        metrics.put("memory", Map.of(
            "used", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            "max", Runtime.getRuntime().maxMemory(),
            "free", Runtime.getRuntime().freeMemory()
        ));
        return ResponseEntity.ok(metrics);
    }

    private long getStartTime() {
        return System.currentTimeMillis() - java.lang.management.ManagementFactory
            .getRuntimeMXBean().getUptime();
    }
}
