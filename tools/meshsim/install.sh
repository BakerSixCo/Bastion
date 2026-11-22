#!/usr/bin/env bash
# BASTION mesh simulator: two simulated Meshtastic nodes (no radio hardware)
# that can hear each other, for developing against on a PC.
#
# Run as root inside WSL Ubuntu:   bash install.sh
#
# Layout:
#   bridge br-mesh 10.77.0.1/24 (root namespace)
#   netns mesh-a 10.77.0.11  -> meshtasticd --sim   (API forwarded to :4403)
#   netns mesh-b 10.77.0.12  -> meshtasticd --sim   (API forwarded to :4404)
# Why namespaces: the nodes mesh with each other over Meshtastic's UDP
# multicast (224.0.0.69:4403), and only one process per network stack can
# bind it (found 2026-11-22: with both on one stack, node A never bound).
# Windows/emulator reach the nodes at localhost:4403 / :4404 (WSL forwards
# localhost); the Android emulator uses 10.0.2.2:4403 / :4404.
set -e
export DEBIAN_FRONTEND=noninteractive

command -v meshtasticd >/dev/null || {
  apt-get update -qq
  apt-get install -y -qq software-properties-common
  add-apt-repository -y ppa:meshtastic/beta
  apt-get install -y -qq meshtasticd
}
apt-get install -y -qq socat iproute2 python3-venv >/dev/null
systemctl disable --now meshtasticd 2>/dev/null || true
[ -x /opt/meshcli/bin/meshtastic ] || { python3 -m venv /opt/meshcli && /opt/meshcli/bin/pip -q install meshtastic; }

cat > /usr/local/sbin/meshsim-net <<'EOF'
#!/usr/bin/env bash
# Idempotent: bridge + one namespace per node. Locked: both node services call it.
set -e
exec 9>/run/meshsim-net.lock; flock 9
ip link show br-mesh >/dev/null 2>&1 || {
  ip link add br-mesh type bridge mcast_snooping 0
  ip addr add 10.77.0.1/24 dev br-mesh
  ip link set br-mesh up
}
for spec in a:11 b:12; do
  n=${spec%%:*}; ip4=10.77.0.${spec##*:}
  ip netns list | grep -q "^mesh-$n" && continue
  ip netns add mesh-$n
  ip link add veth-$n type veth peer name eth0 netns mesh-$n
  ip link set veth-$n master br-mesh up
  ip -n mesh-$n addr add $ip4/24 dev eth0
  ip -n mesh-$n link set lo up
  ip -n mesh-$n link set eth0 up
  ip -n mesh-$n route add default via 10.77.0.1
done
EOF
chmod +x /usr/local/sbin/meshsim-net

unit() { # name ip hostport hwid
  mkdir -p /var/lib/meshsim-$1
  cat > /etc/systemd/system/meshsim-$1.service <<EOF
[Unit]
Description=BASTION simulated Meshtastic node $1 (no radio hardware)
After=network.target
[Service]
ExecStartPre=/usr/local/sbin/meshsim-net
ExecStart=/usr/sbin/ip netns exec mesh-$1 /usr/bin/meshtasticd --sim -d /var/lib/meshsim-$1 -p 4403 -h $4
Restart=on-failure
RestartSec=2
[Install]
WantedBy=multi-user.target
EOF
  cat > /etc/systemd/system/meshsim-$1-fwd.service <<EOF
[Unit]
Description=BASTION mesh simulator: forward :$3 to node $1
After=meshsim-$1.service
[Service]
ExecStart=/usr/bin/socat TCP-LISTEN:$3,fork,reuseaddr TCP:$2:4403
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
EOF
}
unit a 10.77.0.11 4403 AA:BB:CC:00:00:0A
unit b 10.77.0.12 4404 AA:BB:CC:00:00:0B

systemctl daemon-reload
/usr/local/sbin/meshsim-net
systemctl enable meshsim-a meshsim-b meshsim-a-fwd meshsim-b-fwd >/dev/null 2>&1
systemctl restart meshsim-a meshsim-b meshsim-a-fwd meshsim-b-fwd
sleep 6

M=/opt/meshcli/bin/meshtastic
cfg() { # hostport long short lat lon — separate calls: one combined call dropped the owner (seen 2026-11-22)
  $M --host localhost:$1 --set-owner "$2" --set-owner-short "$3" >/dev/null 2>&1 || true
  sleep 2
  $M --host localhost:$1 --set position.fixed_position true --setlat "$4" --setlon "$5" >/dev/null 2>&1 || true
  sleep 2
  # Last: changing region/network reboots the node.
  $M --host localhost:$1 --set lora.region US --set network.enabled_protocols 1 >/dev/null 2>&1 || true
}
# Only configure a node that still has its factory name (keeps later changes).
$M --host localhost:4403 --info 2>/dev/null | grep -q "BASTION Sim A" || cfg 4403 "BASTION Sim A" SIMA 39.7392 -104.9903
$M --host localhost:4404 --info 2>/dev/null | grep -q "BASTION Sim B" || cfg 4404 "BASTION Sim B" SIMB 39.7500 -105.0000
sleep 8
systemctl is-active meshsim-a meshsim-b meshsim-a-fwd meshsim-b-fwd
echo "Simulated nodes: localhost:4403 (Sim A), localhost:4404 (Sim B)"
