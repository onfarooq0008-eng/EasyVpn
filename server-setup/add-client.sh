#!/bin/bash
# ============================================================================
# Registers one app user's device as an allowed peer on this VPS.
#
# The app's Settings screen shows BOTH values you need for this, already
# computed correctly for one device: its public key, and its own unique
# address within the 10.8.0.0/24 subnet (derived automatically so different
# users' devices don't collide on the same tunnel IP -- do not just reuse
# 10.8.0.2/32 for every user, or only the most recently connected one will
# actually work).
#
# Usage: sudo bash add-client.sh <client_public_key> <client_ip_in_10.8.0.x/32>
# Example:
#   sudo bash add-client.sh "AbC123...=" 10.8.0.137/32
# ============================================================================
set -e
WG_IFACE="wg0"

CLIENT_PUB="$1"
CLIENT_IP="$2"

if [ -z "$CLIENT_PUB" ] || [ -z "$CLIENT_IP" ]; then
  echo "Usage: sudo bash add-client.sh <client_public_key> <client_ip/32>"
  echo "(Both values are shown on the device's own Settings screen in the app.)"
  exit 1
fi

wg set ${WG_IFACE} peer "${CLIENT_PUB}" allowed-ips "${CLIENT_IP}"
wg-quick save ${WG_IFACE} 2>/dev/null || wg showconf ${WG_IFACE} > /etc/wireguard/${WG_IFACE}.conf.new

echo "Added peer ${CLIENT_PUB} -> ${CLIENT_IP} on ${WG_IFACE}."
echo "Current peers:"
wg show ${WG_IFACE}
