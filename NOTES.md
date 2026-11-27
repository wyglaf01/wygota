# Full source upload

The complete WygOTA tree (all Java sources, resources, icons, and desktop app) is prepared locally.

To push everything including binary icons:

```bash
cd /path/to/WygOTA   # the prepared project
git remote add origin https://github.com/wyglaf01/WygOTA.git
git fetch origin
git pull origin main --allow-unrelated-histories --no-edit
# or, if you want the full local tree to win:
git push -u origin main --force
```

Local project path in the agent workspace: `/home/workdir/artifacts/WygOTA`
Tarball: `/home/workdir/artifacts/WygOTA.tar.gz`
