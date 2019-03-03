# AirAudio2
AirTunes server running on Android devices, based on https://github.com/fgp/AirReceiver.

Main features:
1. Create an Android service to auto run when boot.
2. Set display name of AirTunes server.
3. Can play only left/right channel on a single device, so 2 devices can be a real stereo group with iTunes or Airfoil on PC/Mac.

Optimized:
1. Improve performance: reduce buffer allocations and copying, optimize audio decryption, etc.
2. Bug fixs to make it more stable.
