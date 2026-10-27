package com.lucenadrive.security;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    PASSENGER, DRIVER, SUPPORT, FINANCE, ADMIN, OWNER;

    public static final Set<Role> STAFF = EnumSet.of(SUPPORT, FINANCE, ADMIN, OWNER);

    public boolean isStaff() {
        return STAFF.contains(this);
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
