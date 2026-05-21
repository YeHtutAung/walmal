package com.walmal.auth.application;

import java.util.List;
import java.util.UUID;

public interface StaffNotificationQueryService {

    List<UUID> findActiveUserIdsByRole(String role);
}
