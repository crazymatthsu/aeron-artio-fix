# Third-party dictionaries bundled in this directory

`FIX42.xml` and `FIX44.xml` are copied verbatim from the **QuickFIX/J** project.

| File | Source |
| --- | --- |
| `FIX42.xml` | <https://raw.githubusercontent.com/quickfix-j/quickfixj/master/quickfixj-messages/quickfixj-messages-fix42/src/main/resources/FIX42.xml> |
| `FIX44.xml` | <https://raw.githubusercontent.com/quickfix-j/quickfixj/master/quickfixj-messages/quickfixj-messages-fix44/src/main/resources/FIX44.xml> |

Fetched from the `master` branch on 2026-09-06. Neither file carries an in-file
licence header upstream, so nothing was stripped; this notice records the
provenance instead.

## Licence

QuickFIX/J is distributed under the **QuickFIX Software License, Version 1.0**
(a BSD-style licence) -- see `LICENSE` in the QuickFIX/J repository
(<https://github.com/quickfix-j/quickfixj/blob/master/LICENSE>). The licence
requires that redistributions retain the copyright notice, this list of
conditions and the disclaimer:

> Copyright (c) 2001-2014 quickfixengine.org. All rights reserved.
>
> This file is part of the QuickFIX FIX Engine. This file may be distributed
> under the terms of the quickfixengine.org license as defined by
> quickfixengine.org and appearing in the file LICENSE included in the
> packaging of this file. This file is provided AS IS with NO WARRANTY OF ANY
> KIND, INCLUDING THE WARRANTY OF DESIGN, MERCHANTABILITY AND FITNESS FOR A
> PARTICULAR PURPOSE.

The FIX protocol specification these dictionaries describe is published by FIX
Protocol Ltd.

## What this project does with them

Nothing is edited in place. `ConvertDictionary` reads these files and writes
Artio dictionaries to `build/artio-dictionaries/`; every change it makes is
listed on stdout and explained in `docs/02-quickfixj-to-artio-dictionary.md`.
