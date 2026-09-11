/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package com.djrapitops.plan.delivery.webserver.auth;

import com.djrapitops.plan.delivery.domain.auth.WebPermission;
import com.djrapitops.plan.delivery.web.resolver.request.WebUser;

import java.util.UUID;

/**
 * Player ownership is the authenticated Minecraft UUID, never an editable display name.
 */
public final class PlayerAccess {

    private PlayerAccess() {
        // Utility class.
    }

    public static boolean canAccess(WebUser user, UUID playerUUID) {
        return user.hasPermission(WebPermission.ACCESS_PLAYER)
                || user.hasPermission(WebPermission.ACCESS_PLAYER_SELF)
                && user.getUUID().filter(uuid -> uuid.equals(playerUUID)).isPresent();
    }

    public static boolean canAccessRaw(WebUser user, UUID playerUUID) {
        return canAccess(user, playerUUID) && user.hasPermission(WebPermission.ACCESS_RAW_PLAYER_DATA);
    }
}
