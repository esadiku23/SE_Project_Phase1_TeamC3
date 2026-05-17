package al.albus.service;

import al.albus.repository.NotificationRepository;

import java.util.List;
import java.util.Map;

public class NotificationService {
    private final NotificationRepository repo = new NotificationRepository();

    public List<Map<String, Object>> notifications(int userId) {
        return repo.findForUser(userId);
    }

    public int unreadCount(int userId) {
        return repo.unreadCount(userId);
    }

    public boolean markRead(int notificationId, int userId) {
        return repo.markRead(notificationId, userId);
    }

    public boolean markAllRead(int userId) {
        return repo.markAllRead(userId);
    }
}
