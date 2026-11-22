@echo off
rem Sends a text from simulated node B into the mesh (arrives in Meshtastic on the emulator).
wsl -d Ubuntu -u root -- /opt/meshcli/bin/meshtastic --host localhost:4404 --sendtext %1
