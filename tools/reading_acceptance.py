#!/usr/bin/env python3
"""V1.3.2 real reading-form and icon checks, synthetic data in an isolated package."""
from navigation_acceptance import main

CLASSES = {
    "ui.ReadingRevealTest": 8,
    "ui.ReadingActivityTest": 2,
    "ui.LauncherIconTest": 2,
}

if __name__ == "__main__":
    main(package="com.dowdah.utilitytracker.acceptancev132", classes=CLASSES,
         build_suffix=".acceptancev132", report_prefix="utility-v132-reading-",
         default_classes=tuple(CLASSES), require_gestures=False)
