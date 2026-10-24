#!/usr/bin/env python3
"""Source and artifact guardrails, not a security audit or device acceptance."""
import pathlib, re, subprocess, os
root=pathlib.Path(__file__).resolve().parents[1]
for folder in ['android/app/src/main', 'android/core/src/main', 'android/guardian/src/main', 'windows/FocusGuard.Core', 'windows/FocusGuard.Console']:
    for p in (root/folder).rglob('*'):
        if not p.is_file() or any(x in p.parts for x in ['bin','obj','build']): continue
        if p.suffix not in ['.kt','.cs','.xml','.xaml']: continue
        text=p.read_text()
        assert 'BEGIN PRIVATE KEY' not in text, p
        assert not re.search(r'\bwipeData\s*\(',text), p
        assert 'AccessibilityService' not in text, p
        assert 'SYSTEM_ALERT_WINDOW' not in text, p
core=(root/'android/core/src/main/kotlin/ru/focusguard/core/Model.kt').read_text()
assert '120 * MINUTE' in core and '48 * 60 * MINUTE' in core and '12 * 60 * MINUTE' in core
assert 'check(BuildConfig.LAB_DEVICE)' in (root/'android/app/src/main/kotlin/ru/focusguard/android/PolicyController.kt').read_text()
print('PASS source release guardrails; device and binary manifest inspection required separately')
