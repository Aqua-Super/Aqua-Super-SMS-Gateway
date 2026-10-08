# Aqua Super SMS Gateway

PWA-first SMS Gateway prototype for Aqua Super.

## Goal

Provide a small phone-side web/PWA interface for Aqua Super SMS requests, while keeping the main Aqua Super app unchanged.

## Important Android limitation

A normal browser PWA cannot silently send an SMS through the Android SIM. It can open the Android SMS composer using `sms:` links, but direct/background SIM sending requires an Android bridge/service with SMS permission.

Therefore this repository is being developed PWA-first. APK/Android bridge remains an optional later step only if required for true automatic SIM sending.

## Current status

- Main Aqua Super app: unchanged
- PWA gateway: planned/under development
- APK build: not being used for the current approach
