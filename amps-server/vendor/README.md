# amps-server/vendor

Drop the AMPS release tarball here, then build an image from it with
[`../Containerfile`](../Containerfile), which explains where to download it
and what to run.

Nothing in this directory is committed. The tarball is a third-party,
licensed distribution and it is large; the repository `.gitignore` excludes
`amps-server/vendor/*.tar` and `*.tar.gz`, and this README is the only file
here that git tracks.

You usually do not need any of this. The default image,
`localhost/amps-demo:5.3.5.135`, is built by the sibling `amps-demo` project
from the same recipe, and `amps-server/scripts/amps.sh` and the integration
test harness both default to it. Build your own only when that image is
absent, or when you want a different AMPS version:

```bash
podman images | grep amps        # is the default already there?
```
