# Third-party notices

This product includes work derived from the following open-source projects.

---

## usb-serial-for-android

<https://github.com/mik3y/usb-serial-for-android>

The CH340/CH341 register sequence and baud-rate encoding
(`app/src/main/java/com/trustedconfigurator/browser/drivers/Ch34xDriver.kt`,
`BaudRates.kt`) and the FTDI baud-rate divisor encoding
(`FtdiDriver.kt`, `BaudRates.kt`) follow this project's implementation.

```
MIT License

Copyright (c) 2011-2013 Google Inc.
Copyright (c) 2013 Mike Wakerly

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## Note on provenance

The vendor-chip protocol details in this project were taken from
usb-serial-for-android, which is MIT licensed. They were **not** taken from the
Linux kernel drivers (`ch341`, `ftdi_sio`), which are GPL-licensed. The two
implementations agree because both describe the same hardware, but the code and
constants here derive from the MIT-licensed work above.
