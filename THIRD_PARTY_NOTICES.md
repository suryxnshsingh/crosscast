# Third-party notices

## Code ported or derived from other projects

**ALAC decoder:** `receiver-core/.../audio/AlacDecoder.kt` is a Kotlin port of `alac.c` as distributed with Shairport Sync.
Copyright (c) 2005 David Hammerton. MIT licence; the full text is in the file header.

**Shairport Sync:** <https://github.com/mikebrady/shairport-sync>, used as the reference implementation.
The following are derived from it:
- the classic AirPlay TXT records
- sync-packet latency handling, including the 11025-frame offset
- the Apple-Challenge format
- the "basic" frame-stuffing approach
- the AirPort Express RSA key (originally published with James Laird's Shairport)

Copyright (c) 2011–2013 James Laird; (c) 2014–2026 Mike Brady. MIT licence:

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Libraries

| Library | Licence |
|---|---|
| AndroidX (Core, Activity, Lifecycle, Compose, Compose for TV, Palette, DataStore) | Apache 2.0 |
| Kotlin, kotlinx.coroutines | Apache 2.0 |
| Timber | Apache 2.0 |
| Tests only: JUnit (EPL 1.0), Robolectric (MIT), Roborazzi (Apache 2.0) | — |
