#!/bin/bash
# nofocus.sh : tell Portal's window it is not the active application, so it stops reading the real mouse.
# In a remote-desktop session (or with nothing in the foreground) Portal took a steady stream of mouse
# movement: the view was pinned looking at the floor and turning, and every scripted aim missed.
powershell -NoProfile -Command "Add-Type 'using System;using System.Runtime.InteropServices;public class NF{[DllImport(\"user32.dll\")]public static extern bool PostMessage(IntPtr h,uint m,IntPtr w,IntPtr l);}'; Get-Process hl2 -ErrorAction SilentlyContinue | % { [NF]::PostMessage(\$_.MainWindowHandle,0x1C,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null; [NF]::PostMessage(\$_.MainWindowHandle,0x0006,[IntPtr]::Zero,[IntPtr]::Zero) | Out-Null }" >/dev/null 2>&1
