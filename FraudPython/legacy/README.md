# Legacy app

`app_legacy.py` is the original single-file FastAPI service kept here only for
behavioural diff / regression comparison against the new `src/` layout.

It is **not** wired into the Dockerfile or production deployment. Delete after
the new service has soaked in production for a release cycle.
