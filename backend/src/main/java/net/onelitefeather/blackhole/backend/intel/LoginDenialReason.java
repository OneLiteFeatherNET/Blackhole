package net.onelitefeather.blackhole.backend.intel;

/**
 * Why a login was refused with DENY. The proxy shows a different screen for each one. A DENY without a reason is read as
 * a VPN policy refusal by proxies that predate this field.
 */
public enum LoginDenialReason {
    /** The VPN or hosting policy of the classification refused the address. */
    VPN_POLICY,
    /** An active area ban covers the login's ASN or country. */
    AREA_BAN,
    /** The join guard is STRICT or LOCKDOWN and the address is a VPN, hosting or proxy address. */
    GUARD_STRICT,
    /** The join guard is LOCKDOWN and the owner is not a known player. */
    GUARD_LOCKDOWN
}
