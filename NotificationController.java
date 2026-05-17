package al.albus.api;

import al.albus.service.NotificationService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService svc = new NotificationService();

    @GetMapping
    public ResponseEntity<?> notifications(HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        return ok(svc.notifications(uid));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        return ok(Map.of("count", svc.unreadCount(uid)));
    }

    @PutMapping("/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable int id, HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        boolean ok = svc.markRead(id, uid);
        return ok ? ok(Map.of("message", "Notification marked read.")) : bad("Notification not found.");
    }

    @PutMapping("/read-all")
    public ResponseEntity<?> markAllRead(HttpSession session) {
        Integer uid = userId(session);
        if (uid == null) return forbidden();
        return svc.markAllRead(uid) ? ok(Map.of("message", "Notifications marked read.")) : bad("Failed to update notifications.");
    }

    private Integer userId(HttpSession session) {
        Object id = session.getAttribute("userId");
        if (id instanceof Number) return ((Number) id).intValue();
        try {
            return Integer.parseInt(String.valueOf(id));
        } catch (Exception e) {
            return null;
        }
    }

    private ResponseEntity<?> ok(Object data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    private ResponseEntity<?> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", msg));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
    }
}
